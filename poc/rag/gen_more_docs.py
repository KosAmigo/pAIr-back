"""15번 재측정용: 실제 과제 규모(자료 12개 안팎)를 흉내 내려고 무비랩 사내 문서 8개(문서당 3쪽)를 더 만든다.
PDF 추출(11번)은 이미 검증했으므로 추출 결과와 같은 형식("--- N쪽 ---" 줄로 쪽 구분)의 txt 로 바로 만든다.
결과: poc/rag/more/*.txt (git 에 올림. 같은 자료로 다시 잴 수 있게)
실행: python -I poc/rag/gen_more_docs.py   (OPENAI_API_KEY 필요)
"""
import json, os, sys, urllib.request, concurrent.futures as cf

MODEL = "gpt-6-luna"
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "more")
CONTEXT = ("영상 구독 서비스 '무비랩'(가입자 약 10만 명, 요금제: 베이직 9,900원, 스탠다드 13,900원, 프리미엄 17,900원)의 사내 문서. "
           "회사는 월 5,900원 라이트 요금제 출시를 검토 중이다. 문서는 이 검토와 직접 관계없거나 일부만 관계있는 실무 문서다.")
DOCS = [
    ("고객센터_문의_분석", "2026년 3분기 고객센터 문의 유형 분석 보고서. 유형별 건수와 비율 표, 처리 시간, 결제·화질·로그인 문의 사례"),
    ("마케팅_캠페인_결과", "2026년 하반기 마케팅 캠페인(친구 초대, 첫 달 할인, 제휴 카드) 성과 보고. 채널별 비용, 가입 수, 가입당 비용 표"),
    ("콘텐츠_투자_계획", "2027년 콘텐츠 투자 계획. 독점 신작 편수, 장르별 예산 표, 라이선스 갱신 일정, 시청 시간 목표"),
    ("앱_이용_행태_분석", "앱 이용 행태 분석. 기기별 시청 비중, 시간대별 접속, 동시 접속 사용률, 화질 설정 분포 표"),
    ("결제_환불_정책", "결제·환불 정책 개정안. 결제 실패 재시도, 환불 기준, 연간 결제 할인율, 요금제 변경 시 정산 규칙"),
    ("인프라_비용_보고", "스트리밍 인프라 비용 보고. CDN 트래픽 비용, 화질별 전송량, 월별 서버 비용 표, 비용 절감 과제"),
    ("2027_조직_OKR", "2027년 상반기 조직별 OKR. 팀별 목표와 핵심 결과 수치(가입자, 이탈률, 매출, 시청 시간)"),
    ("경쟁사_동향_브리프", "국내 영상 구독 경쟁사 동향 월간 브리프. 경쟁사 요금 개편, 광고형 요금제 반응, 번들 제휴 소식"),
]


def ask(name, topic):
    prompt = (f"{CONTEXT}\n\n문서 제목: {name.replace('_', ' ')}\n내용: {topic}\n\n"
              "이 문서를 한국어로 3쪽 분량(쪽당 900~1,200자)으로 써라. 숫자가 들어간 표 1~2개(공백으로 칸을 나눈 일반 텍스트)와 "
              "구체적인 수치를 넣는다. 작성 부서와 날짜를 첫 줄 아래에 쓴다. 마크다운 기호는 쓰지 않는다. "
              "쪽마다 맨 앞에 '--- 1쪽 ---', '--- 2쪽 ---', '--- 3쪽 ---' 줄을 넣고, 다른 설명 없이 문서만 출력한다.")
    body = {"model": MODEL, "reasoning_effort": "low", "max_completion_tokens": 8000,
            "messages": [{"role": "user", "content": prompt}]}
    req = urllib.request.Request("https://api.openai.com/v1/chat/completions", data=json.dumps(body).encode(),
                                 headers={"Authorization": "Bearer " + os.environ["OPENAI_API_KEY"], "Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=300) as r:
        res = json.load(r)
    text = res["choices"][0]["message"]["content"].strip()
    if text.count("쪽 ---") < 3:
        raise RuntimeError(f"{name}: 쪽 표시가 3개 미만")
    with open(os.path.join(OUT, name + ".txt"), "w", encoding="utf-8") as f:
        f.write(text + "\n")
    return name, len(text), res.get("usage", {})


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    with cf.ThreadPoolExecutor(8) as ex:
        for name, n, usage in ex.map(lambda d: ask(*d), DOCS):
            print(f"{name}: {n}자, 토큰 {usage.get('prompt_tokens')}/{usage.get('completion_tokens')}")
    sys.exit(0)
