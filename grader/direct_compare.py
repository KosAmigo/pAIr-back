"""'LLM이 항목 점수를 직접 매기는 방식'과 지금 방식(체크 판정 + 코드 감점)의 비교 실험.

- 같은 샘플 7개, 같은 정보(과제, 숨은 정답/체크리스트, 대화, 이벤트, 제출 파일, 제공 자료, 후속 답변),
  같은 기준 문장(v4 판정 기준)을 주되, 체크별 판정 대신 항목별 점수를 직접 받는다.
- 자동 체크(숨은 테스트, 첫 유효 결과 시간 등)는 기준 실험(v4)의 결과를 그대로 쓴다. 판정 부분만 비교한다.
- 두 모델(Haiku 5.5, gpt-6.1-terra)의 항목 감점을 평균(내림)한다. 지금 채점과 같은 합치기.
- 비교: 반복 채점 총점의 차이(일관성), 정답표로 만든 기대 총점과의 차이(정확도).

실행: python -I grader/direct_compare.py [반복 횟수=5]
결과: grader/runs/direct-<시각>/ (응답 원문, 요약)
"""
import collections, glob, json, os, statistics, sys, time, urllib.request
from concurrent.futures import ThreadPoolExecutor

sys.stdout.reconfigure(encoding="utf-8")
HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
TASKS = os.path.join(ROOT, "backend", "src", "main", "resources", "tasks")
BASE_RUN = os.path.join(HERE, "runs", "20261119-112042")  # v4, Haiku + terra, 5회
VERSION = json.load(open(glob.glob(os.path.join(HERE, "versions", "*v4*.json"))[0], encoding="utf-8"))
EXPECTED = json.load(open(os.path.join(HERE, "samples", "expected.json"), encoding="utf-8"))["expected"]
REPEAT = int(sys.argv[1]) if len(sys.argv) > 1 else 5

SYSTEM = """당신은 AI 활용 역량 평가의 채점자입니다. 응시자가 AI 어시스턴트와 함께 과제를 푼 기록을 보고,
평가 항목마다 점수를 직접 매깁니다.
규칙:
- 항목마다 '판정 부분 만점'이 있습니다. 0점부터 만점 사이 정수로 점수를 매깁니다.
- 항목 아래 적힌 기준 문장과 감점 크기를 참고해 전체적으로 판단합니다. 애매하면 응시자에게 유리하게 봅니다.
- reason 은 응시자에게 보여 줄 한국어 한두 문장입니다.
- AI 답변이 길거나 정중하다고 유리하게 보지 않습니다. 응시자가 무엇을 지시하고, 검증하고, 결과물에 남겼는지를 봅니다.
- AI 답변의 ```edit 블록은 파일 수정 제안입니다. 이벤트 요약에 "aiEdit ... 적용/거절"로 남습니다. 적용한 제안은 응시자가 결과물에 넣은 것으로 봅니다.
- "[시스템 오류]"로 표시된 AI 답변과 그 때문에 짧게 다시 묻는 질문은 감점 근거로 쓰지 않습니다."""

SCHEMA = {
    "type": "object", "additionalProperties": False, "required": ["items"],
    "properties": {"items": {"type": "array", "items": {
        "type": "object", "additionalProperties": False, "required": ["key", "score", "reason"],
        "properties": {"key": {"type": "string"}, "score": {"type": "integer"}, "reason": {"type": "string"}}}}},
}


def jl(path):
    return [json.loads(l) for l in open(path, encoding="utf-8") if l.strip()]


def read_tree(d):
    out = {}
    for p in sorted(glob.glob(os.path.join(d, "**", "*"), recursive=True)):
        if os.path.isfile(p):
            out[os.path.relpath(p, d).replace("\\", "/")] = open(p, encoding="utf-8").read()
    return out


def clock(start, at):
    from datetime import datetime
    f = lambda s: datetime.fromisoformat(s.replace("Z", "+00:00"))
    s = max(0, int((f(at) - f(start)).total_seconds()))
    return f"{s // 3600:02d}:{s % 3600 // 60:02d}:{s % 60:02d}"


def transcript(turns, start):
    sb = []
    for t in turns:
        text = t["text"]
        if t["role"] == "ai" and not text.strip():
            text = "[시스템 오류] AI 답변이 비어 있음 (추론 모델이 출력 한도 안에서 답을 끝내지 못함. 응시자 책임 아님)"
        sb.append(f"#T{t['turn']:02d} {'응시자' if t['role'] == 'user' else 'AI'} {clock(start, t['at'])}\n{text}\n\n")
    return "".join(sb) or "(대화 없음)\n"


def events(evs, start):
    sb = []
    for e in evs:
        ty = e.get("type")
        if ty not in ("run", "build", "save", "firstValid", "submit", "aiEdit"):
            continue
        line = f"{clock(start, e['at'])} {ty}"
        if ty == "run":
            line += f" passed={e.get('passed', 0)} failed={e.get('failed', 0)} compiled={str(e.get('compiled', False)).lower()}"
        if ty == "build":
            line += f" compiled={str(e.get('compiled', False)).lower()}"
        if ty == "save":
            line += f" {e.get('path')} ({e.get('length', 0)}자)"
        if ty == "aiEdit":
            line += f" #T{e.get('turn', 0):02d} 수정 제안 {'적용' if e.get('action') == 'apply' else '거절'} {e.get('path')}"
        sb.append(line + "\n")
    return "".join(sb) or "(기록 없음)\n"


def base_results():
    by = collections.defaultdict(list)
    for f in sorted(glob.glob(os.path.join(BASE_RUN, "results", "*.json"))):
        r = json.load(open(f, encoding="utf-8"))
        by[r["attemptId"]].append(r)
    return by


def judge_max(item):
    s = sum(c["points"] * (4 if "1개당" in c["label"] else 1) for c in item["checks"] if c["method"] == "JUDGE")
    return min(s, item["max"])


def build(aid, ref):
    d = os.path.join(HERE, "samples", aid)
    a = json.load(open(os.path.join(d, "attempt.json"), encoding="utf-8"))
    task = json.load(open(os.path.join(TASKS, a["taskId"], "task.json"), encoding="utf-8"))
    dev = task["type"] == "dev"
    hidden = read_tree(os.path.join(TASKS, a["taskId"], "hidden"))
    original = read_tree(os.path.join(TASKS, a["taskId"], "files"))
    files = read_tree(os.path.join(d, "workspace"))
    p = [f"# 과제\n{task['title']} ({'개발' if dev else '비개발'})\n", *[x + "\n" for x in task["description"]], "\n## 요구사항\n",
         *[f"- {x}\n" for x in task["requirements"]]]
    if dev:
        p.append("\n## 숨은 정답 설명 (응시자는 보지 못함)\n" + hidden.get("answer.md", "") + "\n")
    else:
        p.append("\n## 숨은 체크리스트 (응시자는 보지 못함)\n" + hidden.get("checklist.md", "") + "\n")
    p.append("\n# 평가 항목 (점수를 매길 대상)\n")
    keys = []
    for it in ref["items"]:
        js = [c for c in it["checks"] if c["method"] == "JUDGE"]
        if not js:
            continue
        keys.append(it["key"])
        p.append(f"## {it['key']} ({it['name']}) 판정 부분 만점 {judge_max(it)}점\n")
        for c in js:
            vc = VERSION["checks"]
            hint = ((vc.get(c["code"]) or vc.get(f"{c['code']}-{'dev' if dev else 'biz'}") or {}).get("hint")) or ""
            p.append(f"- {c['label']} (-{c['points']}점)\n  기준: {hint}\n")
    b = ["\n# 대화 기록 (응시자는 AI가 대화창에 붙여 넣은 내용만 볼 수 있다는 것을 안내받았음)\n",
         transcript(jl(os.path.join(d, "turns.jsonl")), a["startedAt"]), "\n",
         "# 응시 이벤트 요약 (저장, 빌드, 테스트 실행)\n", events(jl(os.path.join(d, "events.jsonl")), a["startedAt"]), "\n",
         "# 최종 제출 파일\n"]
    for path, content in files.items():
        if path.startswith("logs/") or path.startswith("자료/"):
            continue
        b.append(f"## {path} ({'수정됨' if content != original.get(path) else '원본 그대로'})\n```\n{content}\n```\n")
    if not dev:
        b.append("\n# 제공 자료 (수치 대조용)\n")
        for path, content in files.items():
            if path.startswith("자료/"):
                b.append(f"## {path}\n```\n{content}\n```\n")
    b.append("\n# 후속 질문 답변\n")
    qs = a.get("followUpQuestions") or task["followUps"]
    ans = a.get("followUpAnswers") or []
    for i, q in enumerate(qs):
        x = ans[i] if i < len(ans) else ""
        b.append(f"Q{i + 1}. {q}\nA. {x if x.strip() else '(무응답)'}\n\n")
    b.append(f"\n평가 항목마다({', '.join(keys)}) key, score, reason 을 채워 items 배열로 답하세요.")
    return "".join(p), "".join(b)


def post(url, body, headers):
    req = urllib.request.Request(url, data=json.dumps(body).encode(), headers=dict(headers, **{"Content-Type": "application/json"}))
    for i in range(3):
        try:
            with urllib.request.urlopen(req, timeout=600) as r:
                return json.load(r)
        except urllib.error.HTTPError as e:
            if e.code < 500 and e.code != 429:
                raise RuntimeError(e.read().decode()[:500])
            time.sleep(5 * (i + 1))
    raise RuntimeError("재시도 실패")


def claude(prefix, body):
    res = post("https://api.anthropic.com/v1/messages",
               {"model": "claude-haiku-5-5", "max_tokens": 16000,
                "system": [{"type": "text", "text": SYSTEM + "\n\n" + prefix, "cache_control": {"type": "ephemeral"}}],
                "messages": [{"role": "user", "content": body}],
                "output_config": {"effort": "medium", "format": {"type": "json_schema", "schema": SCHEMA}}},
               {"x-api-key": os.environ["ANTHROPIC_API_KEY"], "anthropic-version": "2023-06-01"})
    text = "".join(x.get("text", "") for x in res["content"] if x.get("type") == "text")
    u = res["usage"]
    usd = (u["input_tokens"] * 0.10 + u.get("cache_creation_input_tokens", 0) * 0.125 + u.get("cache_read_input_tokens", 0) * 0.01
           + u["output_tokens"] * 0.50) / 1e6
    return json.loads(text), usd


def openai(prefix, body):
    res = post("https://api.openai.com/v1/chat/completions",
               {"model": "gpt-6.1-terra", "reasoning_effort": "medium", "max_completion_tokens": 16000,
                "messages": [{"role": "system", "content": SYSTEM + "\n\n" + prefix}, {"role": "user", "content": body}],
                "response_format": {"type": "json_schema", "json_schema": {"name": "scores", "strict": True, "schema": SCHEMA}}},
               {"Authorization": "Bearer " + os.environ["OPENAI_API_KEY"]})
    return json.loads(res["choices"][0]["message"]["content"]), res["usage"]


def expected_total(aid, runs):
    """자동 체크는 기준 실험 그대로, 판정 체크는 정답표대로(정답표에 없는 칸은 기준 실험의 중앙값)."""
    exp = EXPECTED.get(aid, {})
    total = 0
    for i, it in enumerate(runs[0]["items"]):
        ded = 0
        for j, c in enumerate(it["checks"]):
            vals = [r["items"][i]["checks"][j]["deducted"] for r in runs]
            if c["method"] == "JUDGE" and c["code"] in exp:
                ded += (max(statistics.median(vals), c["points"]) if exp[c["code"]] else 0)
            else:
                ded += statistics.median(vals)
        total += max(0, it["max"] - ded)
    return total


def main():
    base = base_results()
    out = os.path.join(HERE, "runs", "direct-" + time.strftime("%Y%m%d-%H%M%S"))
    os.makedirs(out)
    jobs = []
    for aid, runs in base.items():
        prefix, body = build(aid, runs[0])
        for r in range(1, REPEAT + 1):
            jobs.append((aid, r, prefix, body))

    def one(job):
        aid, r, prefix, body = job
        c, cu = claude(prefix, body)
        o, ou = openai(prefix, body)
        json.dump({"attemptId": aid, "repeat": r, "claude": c, "openai": o, "claudeUsd": cu, "openaiUsage": ou},
                  open(os.path.join(out, f"{aid}__{r}.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)
        return job, c, o, cu, ou

    with ThreadPoolExecutor(6) as ex:
        done = list(ex.map(one, jobs))

    totals = collections.defaultdict(list)
    cost = 0.0
    for (aid, r, _, _), c, o, cu, ou in done:
        cost += cu + (ou["prompt_tokens"] - ou.get("prompt_tokens_details", {}).get("cached_tokens", 0)) * 1.0 / 1e6 \
            + ou.get("prompt_tokens_details", {}).get("cached_tokens", 0) * 0.1 / 1e6 + ou["completion_tokens"] * 5.0 / 1e6
        ref = base[aid][0]
        cs = {x["key"]: x["score"] for x in c["items"]}
        os_ = {x["key"]: x["score"] for x in o["items"]}
        total = 0
        for it in ref["items"]:
            auto = statistics.median([sum(ch["deducted"] for ch in rr["items"][ref["items"].index(it)]["checks"] if ch["method"] == "AUTO")
                                      for rr in base[aid]])
            jm = judge_max(it)
            if jm:
                dc = jm - max(0, min(jm, cs.get(it["key"], jm)))
                do = jm - max(0, min(jm, os_.get(it["key"], jm)))
                jd = (dc + do) // 2
            else:
                jd = 0
            total += max(0, it["max"] - auto - jd)
        totals[aid].append(total)

    lines = ["샘플 | 직접 점수 총점 | 차이 | 지금 방식(v4) 총점 | 차이 | 기대 총점 | 직접 평균 오차 | v4 평균 오차"]
    sp_d, sp_v, err_d, err_v = [], [], [], []
    for aid in base:
        d = totals[aid]
        v = [r["total"] for r in base[aid]]
        e = expected_total(aid, base[aid])
        ed = sum(abs(x - e) for x in d) / len(d)
        ev = sum(abs(x - e) for x in v) / len(v)
        sp_d.append(max(d) - min(d)); sp_v.append(max(v) - min(v)); err_d.append(ed); err_v.append(ev)
        lines.append(f"{aid} | {d} | {max(d) - min(d)} | {v} | {max(v) - min(v)} | {e:g} | {ed:.1f} | {ev:.1f}")
    lines.append(f"일관성(총점 차이): 직접 최대 {max(sp_d)} 평균 {sum(sp_d) / len(sp_d):.1f} / v4 최대 {max(sp_v)} 평균 {sum(sp_v) / len(sp_v):.1f}")
    lines.append(f"기대 총점과의 평균 오차: 직접 {sum(err_d) / len(err_d):.1f}점 / v4 {sum(err_v) / len(err_v):.1f}점")
    lines.append(f"비용(추정): ${cost:.3f} / {len(done)}건")
    open(os.path.join(out, "summary.txt"), "w", encoding="utf-8").write("\n".join(lines) + "\n")
    print("\n".join(lines))
    print("결과:", out)


if __name__ == "__main__":
    main()
