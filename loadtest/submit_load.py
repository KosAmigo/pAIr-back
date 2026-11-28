"""동시 제출 부하 테스트: 응시 N건을 같은 순간에 제출(후속 질문 제출 = 채점 시작)하고, 유실 없이 모두 채점되는지와 걸린 시간을 잰다.

환경은 loadtest/docker-compose.yml (로컬 PostgreSQL + Redis + 가짜 AI 백엔드). 표준 라이브러리만 쓴다.
pAIr-back 폴더에서:
    python loadtest/submit_load.py                          # 100건, 채점 큐 꺼짐(서버 안 스레드 풀)
    python loadtest/submit_load.py --queue                  # 채점 큐(Redis) 켜고
    python loadtest/submit_load.py --restart-after 60       # 제출 60초 뒤 백엔드를 재시작(안전한 종료 → 복구)
    python loadtest/submit_load.py --restart-after 60 --kill  # 강제 종료(SIGKILL) 뒤 다시 시작
    python loadtest/submit_load.py --latency 10s --n 50     # 가짜 AI 지연·건수 바꾸기
결과는 화면과 loadtest/results/<시각>.json 에 남긴다. --keep 이 없으면 끝나고 환경을 지운다.

세는 것
  - 접수: 후속 질문 제출 요청이 200 으로 끝난 건수
  - 완료(DONE) / 실패(ERROR) / 유실: 제한 시간 안에 DONE·ERROR 가 되지 않은 건수
  - 중복 채점: 완료된 채점 기록(scorings DONE)이 2개 이상인 응시 수
  - 제출 응답 시간, 제출부터 채점 완료까지 걸린 시간(p50/p95/최대), 전체 처리 시간과 분당 처리량
"""
import argparse
import json
import os
import statistics
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime

HERE = os.path.dirname(os.path.abspath(__file__))
COMPOSE = ["docker", "compose", "-f", os.path.join(HERE, "docker-compose.yml")]
BASE = "http://localhost:18180"
HEALTH = "http://localhost:18181/actuator/health"
FIRST_USER = 1001


def log(msg):
    print(f"[{datetime.now():%H:%M:%S}] {msg}", flush=True)


def compose(*args, env=None, check=True, capture=False):
    return subprocess.run(COMPOSE + list(args), env=env, check=check, text=True, encoding="utf-8",
                          capture_output=capture)


def psql(sql):
    r = compose("exec", "-T", "db", "psql", "-U", "postgres", "-d", "pair", "-At", "-v", "ON_ERROR_STOP=1", "-c", sql,
                capture=True)
    return r.stdout.strip()


def call(method, path, user, body=None, timeout=60):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(BASE + path, data=data, method=method, headers={
        "Content-Type": "application/json", "X-Pair-User-Id": str(user), "X-Pair-Role": "USER"})
    with urllib.request.urlopen(req, timeout=timeout) as res:
        raw = res.read()
        return json.loads(raw) if raw else None


def wait_healthy(limit=600):
    end = time.time() + limit
    while time.time() < end:
        try:
            with urllib.request.urlopen(HEALTH, timeout=3) as res:
                if b'"UP"' in res.read():
                    return
        except (urllib.error.URLError, OSError):
            pass
        time.sleep(2)
    raise SystemExit("백엔드가 올라오지 않았습니다: docker compose -f loadtest/docker-compose.yml logs backend")


def pct(values, p):
    if not values:
        return None
    s = sorted(values)
    return round(s[min(len(s) - 1, int(round(p / 100 * (len(s) - 1))))], 2)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--n", type=int, default=100, help="동시 제출 건수 (기본 100)")
    ap.add_argument("--users", type=int, default=10, help="응시자 수 (1~100, 기본 10)")
    ap.add_argument("--task", default="order-deadlock", help="자가진단 과제 코드 (기본 order-deadlock: 코드 실행 채점 포함)")
    ap.add_argument("--latency", default="30s", help="가짜 AI 호출 1번 지연 (기본 30s, 실제 채점 모델과 비슷)")
    ap.add_argument("--parallel", type=int, default=4, help="동시 채점 수 (기본 4 = 운영 기본값)")
    ap.add_argument("--queue", action="store_true", help="채점 큐(Redis) 켜기")
    ap.add_argument("--restart-after", type=int, default=0, help="제출 N초 뒤 백엔드 재시작 (0 = 안 함)")
    ap.add_argument("--kill", action="store_true", help="재시작을 강제 종료(SIGKILL)로")
    ap.add_argument("--timeout", type=int, default=3600, help="채점 완료를 기다리는 최대 초 (기본 3600)")
    ap.add_argument("--keep", action="store_true", help="끝나고 환경을 지우지 않기")
    ap.add_argument("--no-build", action="store_true", help="이미지를 다시 빌드하지 않기")
    a = ap.parse_args()
    users = [FIRST_USER + i for i in range(max(1, min(a.users, 100)))]

    env = dict(os.environ, LOADTEST_AI_LATENCY=a.latency, LOADTEST_QUEUE=str(a.queue).lower(),
               LOADTEST_PARALLEL=str(a.parallel))
    log(f"환경을 띄웁니다 (큐={'켜짐' if a.queue else '꺼짐'}, 가짜 AI 지연={a.latency}, 동시 채점={a.parallel})")
    compose("down", "-v", env=env, check=False, capture=True)
    compose("up", "-d", *([] if a.no_build else ["--build"]), env=env)
    try:
        wait_healthy()
        psql(open(os.path.join(HERE, "seed.sql"), encoding="utf-8").read())
        run(a, users, env)
    finally:
        if not a.keep:
            compose("down", "-v", env=env, check=False, capture=True)


def run(a, users, env):
    # 1) 응시 N건을 만들고 풀이를 제출해 후속 질문 단계(FOLLOWUP)로 둔다
    log(f"응시 {a.n}건 준비 (응시자 {len(users)}명, 과제 {a.task})")
    owners = {}

    def prepare(i):
        user = users[i % len(users)]
        aid = call("POST", "/api/attempts", user, {"taskId": a.task})["attemptId"]
        call("POST", f"/api/attempts/{aid}/submit", user, {})
        owners[aid] = user

    with ThreadPoolExecutor(20) as pool:
        list(pool.map(prepare, range(a.n)))

    # 2) 모두 같은 순간에 후속 질문을 제출한다 (= 채점 시작)
    barrier = threading.Barrier(a.n)
    submitted, latency, rejected = {}, [], {}

    def fire(aid):
        user = owners[aid]
        barrier.wait()
        t = time.time()
        try:
            call("POST", f"/api/attempts/{aid}/followups", user, {"answers": ["부하 테스트 답변"]}, timeout=120)
            submitted[aid] = t
            latency.append(time.time() - t)
        except Exception as e:  # noqa: BLE001 - 실패 이유를 그대로 남긴다
            rejected[aid] = repr(e)

    log(f"{a.n}건 동시 제출")
    with ThreadPoolExecutor(a.n) as pool:
        list(pool.map(fire, list(owners)))
    t0 = min(submitted.values()) if submitted else time.time()
    log(f"접수 {len(submitted)}건, 거절 {len(rejected)}건, 제출 응답 p95 {pct(latency, 95)}초")

    # 3) 재시작 시나리오
    restart = None
    if a.restart_after > 0:
        def do_restart():
            nonlocal restart
            time.sleep(a.restart_after)
            log("백엔드 강제 종료(SIGKILL)" if a.kill else "백엔드 재시작(안전한 종료)")
            s = time.time()
            if a.kill:
                compose("kill", "backend", env=env)
            else:
                compose("stop", "backend", env=env)
            stopped = time.time()
            compose("start", "backend", env=env)
            wait_healthy()
            restart = {"mode": "kill" if a.kill else "graceful", "at": round(s - t0, 1),
                       "stopSeconds": round(stopped - s, 1), "downSeconds": round(time.time() - s, 1)}
            log(f"백엔드가 다시 올라왔습니다 (내려가는 데 {restart['stopSeconds']}초, 전체 중단 {restart['downSeconds']}초)")
        threading.Thread(target=do_restart, daemon=True).start()

    # 4) 응시자별 목록으로 상태를 본다
    finished = {}
    status = {}
    deadline = time.time() + a.timeout
    last_report = 0
    while time.time() < deadline and len(finished) < len(submitted):
        for user in users:
            try:
                for row in call("GET", "/api/attempts", user, timeout=10):
                    aid = row["id"]
                    if aid not in submitted:
                        continue
                    status[aid] = row["status"]
                    if row["status"] in ("DONE", "ERROR") and aid not in finished:
                        finished[aid] = time.time()
            except (urllib.error.URLError, OSError, ValueError):
                pass  # 재시작 중
        if time.time() - last_report > 30:
            done = sum(1 for s in status.values() if s == "DONE")
            log(f"진행: 완료 {done} / 끝남 {len(finished)} / 접수 {len(submitted)} ({round(time.time() - t0)}초)")
            last_report = time.time()
        time.sleep(3)
    while restart is None and a.restart_after > 0 and time.time() < deadline:
        time.sleep(1)

    # 5) 집계
    done = [aid for aid in submitted if status.get(aid) == "DONE"]
    error = [aid for aid in submitted if status.get(aid) == "ERROR"]
    lost = [aid for aid in submitted if aid not in finished]
    ids = ",".join(submitted) or "0"
    dup = int(psql(f"select count(*) from (select attempt_id from scorings where status='DONE' and attempt_id in ({ids}) "
                   f"group by attempt_id having count(*) > 1) d") or 0)
    interrupted = int(psql(f"select count(*) from scorings where status='ERROR' and attempt_id in ({ids})") or 0)
    turnaround = [finished[aid] - submitted[aid] for aid in done]
    total = (max(finished[aid] for aid in done) - t0) if done else None
    result = {
        "at": datetime.now().isoformat(timespec="seconds"),
        "config": {"n": a.n, "users": len(users), "task": a.task, "fakeAiLatency": a.latency, "parallel": a.parallel,
                   "queue": a.queue, "restart": restart},
        "accepted": len(submitted), "rejected": len(rejected), "done": len(done), "error": len(error), "lost": len(lost),
        "duplicateScorings": dup, "interruptedScoringRecords": interrupted,
        "submitLatencySec": {"p50": pct(latency, 50), "p95": pct(latency, 95), "max": pct(latency, 100)},
        "turnaroundSec": {"p50": pct(turnaround, 50), "p95": pct(turnaround, 95), "max": pct(turnaround, 100),
                          "mean": round(statistics.mean(turnaround), 1) if turnaround else None},
        "allDoneSec": round(total, 1) if total else None,
        "perMinute": round(len(done) / (total / 60), 1) if total else None,
        "lostIds": lost, "errorIds": error, "rejectedReasons": list(rejected.values())[:5],
    }
    os.makedirs(os.path.join(HERE, "results"), exist_ok=True)
    out = os.path.join(HERE, "results", f"{datetime.now():%Y%m%d-%H%M%S}.json")
    with open(out, "w", encoding="utf-8") as f:
        json.dump(result, f, ensure_ascii=False, indent=2)
    print(json.dumps(result, ensure_ascii=False, indent=2))
    log(f"결과: {out}")
    ok = result["lost"] == 0 and result["rejected"] == 0 and result["error"] == 0 and dup == 0
    log("유실 0건, 실패 0건, 중복 0건" if ok else "확인 필요: 유실·실패·중복이 있습니다")
    if not ok:
        sys.exit(1)


if __name__ == "__main__":
    main()
