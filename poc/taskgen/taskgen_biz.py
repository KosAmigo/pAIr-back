"""기술검증 18: AI 과제 생성 (비개발). 관리자 폼(직군, 난이도, 주제, 함정)만으로 LLM 이 비개발 과제
(자료, 숨은 체크리스트, 객관식·단답형 문항)를 만들고, 바로 쓸 수 있는지 자동 확인한다.

확인 기준 (과제 1개)
  1. 형식: 필수 필드, 자료는 자료/ 아래 CSV 1개 이상 + 문서 1개 이상, 문항 4개 이상(객관식 2, 단답형 2 이상),
     객관식은 보기 4개에 정답 1개, 체크리스트에 필수 항목
  2. CSV: 모든 행의 칸 수가 머리글과 같다
  3. 단답형 정답 검산: 문항마다 출제자가 적은 계산식(CSV 칸 참조)을 코드로 계산해 인정 답안과 맞는지 본다
  4. 함정 근거: 함정을 알아챌 근거 문장이 자료에 그대로 있다 (자료만 꼼꼼히 보면 피할 수 있다)
  5. 정답 노출 없음: 과제 설명과 요구사항에 단답형 정답 수치가 없다
  6. 다른 회사 모델이 풀기: 자료와 문항만 주고 Claude 가 풀어 정답과 맞는지 본다 (문항이 풀 수 있고 정답이 맞다)

실행: python -X utf8 -I poc/taskgen/taskgen_biz.py [개수(기본 10)] [모델(기본 gpt-6.1-terra)]
준비: OPENAI_API_KEY, ANTHROPIC_API_KEY 환경 변수
"""
import csv, io, json, os, re, sys, time, urllib.request
from concurrent.futures import ThreadPoolExecutor

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "out", "biz")
N = int(sys.argv[1]) if len(sys.argv) > 1 else 10
MODEL = sys.argv[2] if len(sys.argv) > 2 else "gpt-6.1-terra"
SOLVER = "claude-sonnet-5-5"

FORMS = [
    ("서비스 기획", "중", "저가 요금제 출시 여부", "설문의 전환 의향을 실제 전환율로 보정하지 않음"),
    ("마케팅", "중", "광고 채널 예산 재배분", "가입 수만 보고 가입당 비용(CAC)을 비교하지 않음"),
    ("재무", "상", "신규 매장 출점 타당성", "표의 금액 단위가 천 원인데 원으로 읽음"),
    ("운영", "중", "물류센터 인력 배치", "평균 처리량만 보고 요일별 편차(최대치)를 무시"),
    ("HR", "하", "재택근무 확대 효과", "응답자가 적은 부서의 비율을 전체 경향처럼 해석"),
    ("CS", "중", "상담 챗봇 도입 효과", "건수 비율(%)과 퍼센트포인트(%p)를 혼동"),
    ("커머스 기획", "중", "무료 배송 기준 금액 변경", "합계 행을 데이터 행과 함께 더해 두 번 셈"),
    ("마케팅", "상", "구독 할인 프로모션 성과", "프로모션 기간과 비교 기간의 길이(일수)가 다름"),
    ("서비스 기획", "하", "앱 푸시 알림 빈도 조정", "상관관계를 인과관계로 단정"),
    ("재무", "중", "연간 결제 할인율 결정", "월 단위 이탈률을 연 이탈률로 그대로 씀"),
]

MAT = {"type": "object", "additionalProperties": False, "required": ["path", "content"],
       "properties": {"path": {"type": "string", "description": "자료/이름.csv 또는 자료/이름.md"}, "content": {"type": "string"}}}
CHOICE = {"type": "object", "additionalProperties": False, "required": ["content", "correct"],
          "properties": {"content": {"type": "string"}, "correct": {"type": "boolean"}}}
QUESTION = {"type": "object", "additionalProperties": False,
            "required": ["type", "content", "choices", "accepted", "calc", "source"],
            "properties": {
                "type": {"type": "string", "enum": ["SINGLE", "SHORT"]},
                "content": {"type": "string"},
                "choices": {"type": "array", "items": CHOICE, "description": "SINGLE 만 4개, SHORT 는 빈 배열"},
                "accepted": {"type": "array", "items": {"type": "string"}, "description": "SHORT 인정 답안(첫 번째는 숫자만, 예: 8430). SINGLE 은 빈 배열"},
                "calc": {"type": "string", "description": "SHORT 정답 계산식. CSV 칸은 v(\"자료/파일.csv\", \"열=값;열=값\", \"열 이름\") 로 참조, 사칙연산과 round 만. SINGLE 은 빈 문자열"},
                "source": {"type": "string", "description": "근거 자료 경로"}}}
SCHEMA = {"type": "object", "additionalProperties": False,
          "required": ["title", "description", "requirements", "followUps", "materials", "checklist", "trap", "questions"],
          "properties": {
              "title": {"type": "string"},
              "description": {"type": "array", "items": {"type": "string"}},
              "requirements": {"type": "array", "items": {"type": "string"}},
              "followUps": {"type": "array", "items": {"type": "string"}},
              "materials": {"type": "array", "items": MAT},
              "checklist": {"type": "string", "description": "숨은 채점 체크리스트(마크다운). '## 필수 항목' 아래 번호 목록, 참고 계산, 흔한 함정"},
              "trap": {"type": "object", "additionalProperties": False, "required": ["description", "evidence"],
                       "properties": {"description": {"type": "string"},
                                      "evidence": {"type": "string", "description": "함정을 알아챌 수 있는 자료 속 문장(자료에 있는 그대로 복사)"}}},
              "questions": {"type": "array", "items": QUESTION}}}

SYSTEM = """당신은 AI 활용 역량 평가 플랫폼의 비개발 과제 출제자입니다. 응시자는 60분 동안 AI 어시스턴트와 함께 자료를 분석해 1페이지 기획안을 쓰고, 자료에 근거한 문항에 답합니다.
규칙:
- 실제 회사처럼 보이는 가상의 회사와 수치를 씁니다. 실존 회사 이름은 쓰지 않습니다.
- 자료(materials)는 자료/ 아래에 CSV 1~2개(쉼표 구분, 첫 줄 머리글, 칸 안에 쉼표 없음)와 문서(.md) 1~2개입니다. 합쳐서 3,000~10,000자.
- 함정은 자료를 꼼꼼히 보면 피할 수 있어야 합니다. 함정을 알아챌 근거 문장을 자료에 넣고, trap.evidence 에 그 문장을 그대로 복사합니다. 과제 설명에는 함정을 알려 주지 않습니다.
- checklist 는 숨은 채점 기준입니다. '## 필수 항목' 아래 번호 목록(결론, 자료 수치를 인용한 근거 3개, 함정 관련 계산, 리스크와 대응), '## 참고 계산'(정답 계산 과정), '## 흔한 함정'을 씁니다.
- questions 는 객관식(SINGLE) 2~3개와 단답형(SHORT) 2~3개입니다. 객관식은 보기 4개에 정답 1개. 단답형은 자료의 수치로 계산하는 숫자 하나가 답이고, calc 에 계산식을 씁니다. 함정과 관련된 문항을 1개 이상 넣습니다.
- calc 형식: v("자료/파일.csv", "열=값;열=값", "열 이름") 이 그 조건에 맞는 한 행의 칸 값입니다. 사칙연산, 괄호, round(x, 자릿수) 만 씁니다. 예: round(v("자료/매출.csv", "월=2026-10;요금제=베이직", "가입자 수") * 0.34 * (9900 - 5900) / 10000)
- 단답형 문항에는 답의 단위와 반올림 방법(예: 만 원 단위, 소수 첫째 자리 반올림)을 적습니다. accepted 첫 번째는 그 단위의 숫자만 씁니다(쉼표 없이).
- followUps 는 후속 질문 3개: 직접 검증한 것, 채택하지 않은 AI 제안과 이유, 동료가 같은 결론을 얻을 지시 순서.
- 모든 글은 한국어로 씁니다."""


def post(url, body, headers):
    req = urllib.request.Request(url, data=json.dumps(body).encode(), headers=dict(headers, **{"Content-Type": "application/json"}))
    with urllib.request.urlopen(req, timeout=600) as r:
        return json.load(r)


def generate(form):
    job, level, topic, trap = form
    user = f"직군: {job}\n난이도: {level}\n주제: {topic}\n함정: {trap}\n위 조건으로 과제를 만들어 주세요."
    t = time.time()
    res = post("https://api.openai.com/v1/chat/completions",
               {"model": MODEL, "reasoning_effort": "medium", "max_completion_tokens": 32000,
                "messages": [{"role": "system", "content": SYSTEM}, {"role": "user", "content": user}],
                "response_format": {"type": "json_schema", "json_schema": {"name": "task", "strict": True, "schema": SCHEMA}}},
               {"Authorization": "Bearer " + os.environ["OPENAI_API_KEY"]})
    return json.loads(res["choices"][0]["message"]["content"]), res["usage"], time.time() - t


def num(s):
    s = re.sub(r"[,\s원%명건개]", "", str(s))
    try:
        return float(s)
    except ValueError:
        return None


def tables(task):
    out = {}
    for m in task["materials"]:
        if m["path"].endswith(".csv"):
            out[m["path"]] = list(csv.reader(io.StringIO(m["content"].strip())))
    return out


def calc(expr, tabs):
    def v(path, where, col):
        rows = tabs[path]; head = [h.strip() for h in rows[0]]
        conds = [c.split("=", 1) for c in where.split(";") if c.strip()]
        hit = [r for r in rows[1:] if all(r[head.index(k.strip())].strip() == val.strip() for k, val in conds)]
        if len(hit) != 1:
            raise ValueError(f"{path} {where}: {len(hit)}행")
        return num(hit[0][head.index(col.strip())])
    if re.search(r"[A-Za-z_]+\s*\(", expr) and set(re.findall(r"([A-Za-z_]+)\s*\(", expr)) - {"v", "round"}:
        raise ValueError("허용되지 않은 함수")
    return eval(expr, {"__builtins__": {}}, {"v": v, "round": round})


def solve(task):
    """자료와 문항만 Claude 에게 주고 답을 받는다. 답: [문항 번호별 보기 번호(1~4) 또는 숫자]"""
    mats = "\n\n".join(f"## {m['path']}\n{m['content']}" for m in task["materials"])
    qs = []
    for i, q in enumerate(task["questions"], 1):
        line = f"Q{i}. ({'객관식' if q['type'] == 'SINGLE' else '단답형'}) {q['content']}"
        if q["type"] == "SINGLE":
            line += "\n" + "\n".join(f"  {j}) {c['content']}" for j, c in enumerate(q["choices"], 1))
        qs.append(line)
    prompt = (f"다음 자료만 근거로 문항에 답하세요.\n\n{mats}\n\n# 문항\n" + "\n".join(qs) +
              '\n\n계산을 꼼꼼히 한 뒤, 마지막 줄에 JSON 하나만 출력하세요. 형식: {"answers": ["2", "8430", ...]} '
              "(객관식은 보기 번호, 단답형은 문항이 정한 단위의 숫자만)")
    res = post("https://api.anthropic.com/v1/messages",
               {"model": SOLVER, "max_tokens": 16000, "messages": [{"role": "user", "content": prompt}]},
               {"x-api-key": os.environ["ANTHROPIC_API_KEY"], "anthropic-version": "2023-06-01"})
    text = "".join(b.get("text", "") for b in res["content"])
    m = re.findall(r"\{[^{}]*\"answers\"[^{}]*\}", text)
    return json.loads(m[-1])["answers"], res["usage"]


def close(a, b):
    return a is not None and b is not None and abs(a - b) <= max(0.01, abs(b) * 0.005)


def validate(task):
    v, detail = {}, {}
    mats = task["materials"]; qs = task["questions"]
    singles = [q for q in qs if q["type"] == "SINGLE"]; shorts = [q for q in qs if q["type"] == "SHORT"]
    v["형식"] = (all(m["path"].startswith("자료/") for m in mats)
                and any(m["path"].endswith(".csv") for m in mats) and any(m["path"].endswith(".md") for m in mats)
                and len(singles) >= 2 and len(shorts) >= 2
                and all(len(q["choices"]) == 4 and sum(c["correct"] for c in q["choices"]) == 1 for q in singles)
                and all(q["accepted"] and num(q["accepted"][0]) is not None for q in shorts)
                and "필수 항목" in task["checklist"] and len(task["followUps"]) == 3)
    tabs = tables(task)
    v["CSV 형식"] = bool(tabs) and all(len(set(len(r) for r in rows if r)) == 1 for rows in tabs.values())
    checks = []
    for q in shorts:
        try:
            got = calc(q["calc"], tabs); want = num(q["accepted"][0])
            checks.append([q["accepted"][0], round(got, 3), close(got, want)])
        except Exception as e:
            checks.append([q["accepted"][0], f"계산 실패: {e}"[:120], False])
    v["단답형 정답 검산"] = bool(checks) and all(c[2] for c in checks)
    detail["검산"] = checks
    body = "\n".join(m["content"] for m in mats)
    ev = task["trap"]["evidence"].strip().strip("\"'")
    v["함정 근거가 자료에 있음"] = len(ev) >= 8 and re.sub(r"\s+", "", ev) in re.sub(r"\s+", "", body)
    # 시험 시간 안내("60분 동안")는 정답이 아니므로 빼고 본다 (04번 오탐: 단답형 정답 60과 겹침)
    brief = re.sub(r"\d+분\s*동안", "", " ".join(task["description"] + task["requirements"]))
    v["정답 노출 없음"] = not any(re.search(r"(?<![\d.])" + re.escape(q["accepted"][0]) + r"(?![\d.])", brief) for q in shorts)
    try:
        answers, usage = solve(task)
        res = []
        for q, a in zip(qs, answers + [None] * len(qs)):
            if q["type"] == "SINGLE":
                want = next(j for j, c in enumerate(q["choices"], 1) if c["correct"])
                res.append(str(a).strip() == str(want))
            else:
                res.append(any(close(num(a), num(x)) for x in q["accepted"]))
        detail["풀이"] = {"답": answers, "정답 여부": res}
        v["다른 모델이 풀면 정답과 일치"] = all(res)
        detail["풀이 토큰"] = usage
    except Exception as e:
        detail["풀이"] = f"실패: {e}"[:200]
        v["다른 모델이 풀면 정답과 일치"] = False
    return v, detail


def one(i):
    form = FORMS[i % len(FORMS)]
    d = os.path.join(OUT, f"{i + 1:02d}")
    os.makedirs(d, exist_ok=True)
    try:
        task, usage, sec = generate(form)
    except Exception as e:
        return i, form, None, {"오류": str(e)[:300]}, None, 0
    json.dump(task, open(os.path.join(d, "task.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=2)
    v, detail = validate(task)
    json.dump({"form": form, "checks": v, "detail": detail, "usage": usage, "seconds": round(sec)},
              open(os.path.join(d, "result.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=2)
    return i, form, v, detail, usage, sec


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    print(f"[설정] {N}개, 생성 {MODEL}, 풀이 {SOLVER}", flush=True)
    passed, tin, tout, secs = 0, 0, 0, []
    with ThreadPoolExecutor(5) as ex:
        for i, form, v, detail, usage, sec in ex.map(one, range(N)):
            if v is None:
                print(f"[{i + 1:02d}] {form[2]}: 생성 실패 {detail}", flush=True); continue
            ok = all(v.values()); passed += ok; secs.append(sec)
            tin += usage["prompt_tokens"]; tout += usage["completion_tokens"]
            fails = [k for k, x in v.items() if not x]
            print(f"[{i + 1:02d}] {form[2]} ({form[1]}): {'통과' if ok else '실패 ' + ', '.join(fails)} / 검산 {detail['검산']} / "
                  f"풀이 {detail['풀이'] if isinstance(detail['풀이'], str) else detail['풀이']['정답 여부']} / 생성 {round(sec)}초", flush=True)
    print(f"[결과] {passed}/{N} 통과, 생성 토큰 입력 {tin} 출력 {tout}, 평균 {sum(secs) / max(1, len(secs)):.0f}초", flush=True)
