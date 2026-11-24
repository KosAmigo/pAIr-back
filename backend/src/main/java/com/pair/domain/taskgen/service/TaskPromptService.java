package com.pair.domain.taskgen.service;

import com.pair.domain.taskgen.dto.TaskGenRequest;

/**
 * 단계별 생성의 프롬프트. 단계마다 짧게, "이렇게 쓴다" 규칙만 둔다(설계 5장). 기준 번호는 pAIr-docs 설계/좋은_과제_기준.md.
 * 과제를 한 번에 쓰던 옛 프롬프트(DevTaskGeneratorService·BizTaskGeneratorService 의 SYSTEM)는 pipeline=oneshot 일 때만 쓴다.
 * 좋은 예는 2026-11-24 검증(v9)에서 '그대로 사용' 판정을 받은 과제(DEV 01, BIZ 04)를 설계도 모양으로 줄인 것이다.
 */
final class TaskPromptService {

    private TaskPromptService() {}

    private static final String PURPOSE = """
            응시자는 AI 와 함께 과제를 푼다. AI 답을 그대로 받으면 놓치는 숨은 결함(E3)이 있고, 자료나 테스트와 대조하면 찾을 수 있어야 한다. \
            사람이 정할 판단 지점(E2)이 있고 그 근거는 자료에 있다(M4). 일부러 꼬거나 자료 밖 지식이 필요하면 안 된다.
            """;

    private static final String DEV_EXAMPLE = """
            {"title":"누적 할인을 반영한 결제액","code":"payment-discounts","category":"기능 구현",\
            "scenario":"결제 화면에서 할인 항목을 차례로 등록하고 합계를 조회하는 코드에 결제액 조회를 더한다.",\
            "api":["PaymentDiscount.addDiscount(String amount)","PaymentDiscount.totalDiscount(): BigDecimal","PaymentDiscount.payable(String price): BigDecimal"],\
            "facts":[{"text":"7월 3일 접수 로그: 할인 0.10, 0.20을 등록한 뒤 합계가 0.30000000000000004로 표시됨","place":"notes"},\
            {"text":"정산 화면은 결제 원금 5.00에 할인 합계 7.00인 건을 0.00으로 표시합니다.","place":"description"},\
            {"text":"리포트 화면은 결제 원금 5.00에 할인 합계 7.00인 건을 -2.00으로 표시합니다.","place":"description"}],\
            "examples":[{"input":"새 PaymentDiscount에 할인 1.25, 2.25를 등록한 뒤 totalDiscount()","expected":"3.50"},\
            {"input":"새 PaymentDiscount에 할인 0.50, 0.25를 등록한 뒤 totalDiscount()","expected":"0.75"},\
            {"input":"할인 1.25, 2.25 등록 뒤 payable(\\"10.00\\")","expected":"6.50"}],\
            "flaws":[{"what":"할인을 double 로 누적해 소수 합계가 어긋난다","where":"PaymentDiscount.addDiscount","evidence":"7월 3일 접수 로그: 할인 0.10, 0.20을 등록한 뒤 합계가 0.30000000000000004로 표시됨","caughtBy":"totalsExampleAmountsExactly"}],\
            "judgments":[{"question":"할인 합계가 원금보다 클 때 결제액을 어떻게 보일 것인가","basis":"정산 화면은 결제 원금 5.00에 할인 합계 7.00인 건을 0.00으로 표시합니다.",\
            "options":[{"text":"0으로 보인다","fact":"정산 화면은 결제 원금 5.00에 할인 합계 7.00인 건을 0.00으로 표시합니다."},\
            {"text":"음수로 보인다","fact":"리포트 화면은 결제 원금 5.00에 할인 합계 7.00인 건을 -2.00으로 표시합니다."}]}],\
            "trapKeywords":["double","부동소수점","정밀도 손실"]}""";

    private static final String BIZ_EXAMPLE = """
            {"title":"캠페인 개선 예산과 추진 순서 검토","code":"campaign-review","category":"기획안 작성",\
            "scenario":"가상의 구독 서비스 회사가 다음 분기 캠페인 개선 예산 배정 후보와 개선 작업 순서를 검토한다.",\
            "tables":[{"path":"자료/캠페인 실적.csv","columns":["캠페인","월","방문자 수","전환 건수"],\
            "rows":[["새봄","4월","10000","400"],["새봄","6월","10000","500"],["여름","4월","10000","500"],["여름","6월","10000","600"]]}],\
            "figures":[{"label":"통과선 심사 변화율","value":"22"},{"label":"통과선 전환 건수 증가","value":"90"},{"label":"운영팀 기록 증가","value":"1"},{"label":"이전 기준 전환율","value":"2"},{"label":"이전 현재 전환율","value":"3"},{"label":"이전 심사 변화율","value":"50"},{"label":"6월 상담 처리 여력","value":"1050"}],\
            "docs":[{"path":"자료/검토 안내.pdf","role":"검토 안내"},{"path":"자료/운영 메모.pdf","role":"운영 메모"}],\
            "facts":[{"text":"배정 후보 심사표의 통과선은 심사 변화율 22퍼센트 이상과 전환 건수 증가 90건 이상이다.","place":"자료/검토 안내.pdf"},\
            {"text":"이전 검토의 심사표에는 기준 시점 전환율 2퍼센트, 현재 시점 전환율 3퍼센트인 건의 심사 변화율이 50퍼센트로 적혀 있다.","place":"자료/검토 안내.pdf"},\
            {"text":"운영팀은 새봄과 여름의 전환율을 각각 1퍼센트 증가로 기록했다.","place":"자료/운영 메모.pdf"},\
            {"text":"6월의 두 캠페인 신청 완료 건을 처리할 수 있는 상담팀 여력은 합계 1050건이었다.","place":"자료/운영 메모.pdf"}],\
            "flaws":[{"what":"운영 메모의 1퍼센트는 전환율 변화 폭이고 심사표 변화율은 기준 시점 대비 상대 변화율이다","where":"운영 메모 대 검토 안내·캠페인 실적.csv",\
            "evidence":"운영팀은 새봄과 여름의 전환율을 각각 1퍼센트 증가로 기록했다.","caughtBy":"Q2"}],\
            "conclusion":{"correct":"새봄 캠페인만 배정 후보로 정한다.","wrong":"두 캠페인 모두 보류한다."},\
            "questionPlan":["Q1 객관식 배정 후보 결론","Q2 단답형 새봄 심사 변화율","Q3 객관식 6월 상담 여력"],\
            "trapKeywords":["퍼센트포인트","상대 변화율"]}""";

    private static final String BLUEPRINT_COMMON = """
            당신은 AI 활용 역량 평가 과제의 설계자입니다. 글은 쓰지 말고 설계도(구조)만 냅니다. 응시자는 {{minutes}}분 동안 AI 어시스턴트와 함께 푼다.
            """ + PURPOSE + """
            이렇게 씁니다:
            - scenario: 업무 상황 한두 문장. 결함이나 원인은 말하지 않는다.
            - facts: 응시자 글에 들어갈 기록과 관측을 한 문장씩. 무슨 일이 있었나, 표의 값, 견적서·영수증·로그·메모가 말하는 것, 누가 무엇을 말했나만 쓴다. 결함의 근거, 판단의 근거, 배경을 같은 모양으로 섞는다. 정의, 공식, 규칙, 계산 순서, "~이다/~포함된다"꼴 문장, 원인, 고치는 법, "~해야 한다"는 사실이 아니다(코드가 걸러 다시 쓰게 한다).
              사실 아님: "총비용은 공급가액에 봉사료와 부가가치세를 합한 금액이다", "일평균은 전환 수를 집행일 수로 나눈 값이다".
              사실임: "견적서 항목: 대관 8,000,000원, 봉사료 10퍼센트 별도", "청구서 합계란에 '부가세 포함 금액'이라고 적혀 있다", "4월 전환 400건, 집행 20일", "7월 3일 로그: 할인 0.10, 0.20 등록 뒤 합계 0.30000000000000004".
            - flaws: 숨은 결함. what 무엇이 틀리나, where 어디를 대조하면 보이나, evidence 응시자가 비교하면 결함을 읽어 낼 수 있는 원자료 한 줄(견적서·청구서 항목, 로그 한 줄. facts 문장 그대로), caughtBy 가 잡는 곳. AI 가 흔히 놓치는 지점에 두고 자료만으로 알 수 있어야 한다.
            - judgments: 사람이 정할 모호한 선택. question 은 무엇을 정할지만 중립적으로 묻고 선택지나 결론을 담지 않는다. basis(근거 문장, facts 그대로), options 2개 이상(text, fact 그 선택을 뒷받침하는 서로 다른 관측, facts 그대로). 어느 선택을 해도 인정되고, 한쪽으로 못 박는 사실은 쓰지 않는다.
            - trapKeywords: 결함의 핵심어 2~5개(응시자 글에 나오면 안 되는 말).
            - title, summary(80자 이내, 결함을 드러내지 않음), code(영문 소문자·하이픈), category, competencies(1~2개 key).
            """;

    private static final String BLUEPRINT_DEV = """
            - api: 응시자 글과 코드가 함께 쓰는 공개 클래스·메서드 시그니처(순수 Java 21, 패키지 하나).
            - examples: 입력→기대 출력 예 3~5개. 실제로 맞는 값이고, 결함이 있는 시작 코드도 이미 맞게 내는 입력만 쓴다(시작 코드에서 바로 틀리는 예는 결함이 눈에 띄는 것이다). 결함을 건드리는 입력은 숨은 테스트가 쓴다.
            - flaws.caughtBy: 결함을 잡는 숨은 테스트 메서드 이름(영문). 결함은 시작 코드나 공개 테스트의 한 줄에서 바로 보이면 안 된다.
            - facts.place: description(상황·정의·사례) 또는 notes(현상과 재현 값을 적는 접수 메모, 장애 분석 과제만).
            - tables, figures, docs, conclusion, questionPlan 은 비운다.
            """;

    private static final String BLUEPRINT_BIZ = """
            - tables: CSV 가 될 표(path 자료/이름.csv, columns, rows). 숫자 칸은 쉼표 없는 숫자, 칸 안에 쉼표 없음. 숫자는 여기와 figures 에서만 정한다.
            - figures: 문서에 쓰일 이름 붙은 수(label, value). 부분은 partOf 로 전체를, 합계는 sumOf 로 항목을 적어 정합성을 맞춘다(부분 ≤ 전체, 합계 일치). 정답이 되는 값은 figures 에 두어도 facts 에는 쓰지 않는다.
            - facts 의 모든 숫자는 tables·figures 값이어야 한다. facts.place 는 그 사실이 들어갈 문서 경로(또는 description). 용어 정의는 무엇을 세는지까지만 쓴다.
            - docs: 문서 계획(path 자료/이름.pdf, role 중립적인 이름). 판단과 결함의 근거는 서로 다른 문서에 나눠 대조해야만 드러나게 한다.
            - flaws.caughtBy: 결함을 놓치면 답이 달라지는 문항 번호 "Q2" 또는 "결론". conclusion: 정답 결론과 함정에 빠졌을 때의 결론(서로 다르게).
            - questionPlan: 문항 계획("Q1 객관식 …"). 단답형은 함정에 빠지면 값이 달라지게, 결론 객관식을 1개 이상 둔다. 문항은 서로 다른 자료 둘 이상을 대조해야 풀린다.
            - api, examples 는 비운다.
            """;

    /** 1단계 설계도 프롬프트. depth 는 제한 시간별 과제 깊이(기존 표) */
    static String blueprint(boolean biz, int minutes, String depth) {
        String body = BLUEPRINT_COMMON + (biz ? BLUEPRINT_BIZ : BLUEPRINT_DEV) + "과제 깊이: {{depth}}\n좋은 예(줄인 설계도):\n" + (biz ? BIZ_EXAMPLE : DEV_EXAMPLE);
        return body.replace("{{minutes}}", String.valueOf(minutes)).replace("{{depth}}", depth) + TaskGenRequest.COMPETENCY_PROMPT;
    }

    /** 2단계(DEV) 작성 프롬프트 */
    static final String WRITING_DEV = """
            당신은 응시자에게 보일 과제 글을 쓰는 작성자입니다. 받은 사실 목록과 예 목록으로 description, requirements, notes 를 씁니다.
            이렇게 씁니다:
            - 사실 문장은 하나도 빼지 말고 값과 표현을 바꾸지 않고 그대로 넣는다. place 가 description 이면 description 에, notes 면 notes 에 둔다. 문장 사이를 잇는 말은 더해도 된다.
            - 사실·예 밖에 쓰는 글은 상황, 인물, 업무 흐름 같은 배경뿐이다. 새 규칙, 조건, 정의, 처리 방식, 용도 설명은 쓰지 않는다(예: "구분자는 문자 그대로 취급한다", "이 API는 ~에 쓰인다"). 새 사실·숫자·원인·이유도 쓰지 않고, 어느 사실이 중요한지 짚지 않는다.
            - description: 상황과 해야 할 일(기능 추가)을 업무 글투로 3~6문장. '정해야 할 것'이 있으면 "~를 정하고 선택한 이유를 설명하세요"로만 요청한다. 선택지나 어느 쪽이 맞다는 말은 쓰지 않는다.
            - requirements: 예 목록을 한 줄에 하나씩 "입력 → 기대 출력"으로, 값은 그대로 쓴다. 예 밖의 규칙 문장은 쓰지 않는다.
            - notes: place 가 notes 인 사실을 접수 메모로(현상과 재현 값만). 없으면 빈 문자열.
            - 한국어, 실제 업무 글처럼.
            """;

    /** 2단계(BIZ) 작성 프롬프트 */
    static String writingBiz(int minutes) {
        return """
                당신은 응시자에게 보일 과제 글과 자료 문서를 쓰는 작성자입니다. 받은 사실 목록, 문서 계획, CSV 표로 description, requirements, documents 를 씁니다.
                이렇게 씁니다:
                - 사실 문장은 하나도 빼지 말고 값과 표현을 바꾸지 않고 그대로, place 의 문서에 넣는다. 사실을 따로 떠 있는 한 줄로 두지 말고 다른 내용과 같은 문단 안에 섞는다.
                - 사실·CSV 밖에 쓰는 글은 상황, 인물, 업무 흐름 같은 배경뿐이다. 새 규칙, 조건, 정의, 계산 방법은 쓰지 않는다. 새 사실·숫자·원인·이유도 쓰지 않고(숫자는 사실 문장과 CSV 값만), 어느 사실이 중요한지, 어느 쪽 선택이 맞는지 짚지 않는다.
                - 문서마다 문서 계획의 path 와 같은 경로로 쪽별 글(pages)을 쓴다. 쪽 하나는 900자 이내 30줄 이내, 첫 줄은 짧은 제목. 마크다운 기호, 이모지, 특수 기호는 쓰지 않는다. 문서 전체 분량은 합쳐 {{size}}. 배경 설명은 숫자 없이 채운다.
                - "~해야 한다", 두 대상이 다르다는 지적, 원인 설명은 쓰지 않는다.
                - description: 상황, 응시자의 역할, 할 일(1페이지 기획안, 먼저 할 작업 하나와 근거, 리스크와 대응, 문항 답)을 3~5문장. '정해야 할 것'은 "~를 정하고 근거를 설명하세요"로만 요청하고 선택지나 결론은 담지 않는다. 실존 회사 이름은 쓰지 않는다.
                - requirements: 기획안에 담을 것을 2~4개 짧게(결론과 자료 수치를 인용한 근거 3개 이상, 선택과 근거, 리스크와 대응). 정답이 되는 숫자는 쓰지 않는다.
                - 한국어, 실제 업무 글처럼.
                """.replace("{{size}}", minutes == 20 ? "1,500~4,000자" : "3,000~10,000자");
    }

    /** 2'단계(DEV) 시작 코드 프롬프트. depth 는 제한 시간별 과제 깊이 */
    static String code(String depth) {
        return """
                당신은 개발 과제의 시작 코드와 공개 테스트를 쓰는 개발자입니다. 설계도의 api 를 구현한 시작 코드와 공개 테스트를 씁니다.
                이렇게 씁니다:
                - 순수 Java 21 + JUnit 5 만. 외부 라이브러리, 파일, 네트워크, Spring 은 쓰지 않는다. 경로는 src/<패키지>/X.java, test/<패키지>/XTest.java.
                - 시작 코드는 컴파일되고 공개 테스트를 통과한다. flaws.where 의 결함은 평범한 구현으로 들어 있어야 하고, 공개 테스트는 examples 를 확인한다. 시작 코드는 모든 examples 에서 이미 맞는 값을 내야 하고, 결함은 examples 밖의 입력에서만 드러난다.
                - 주석은 쓰지 않는다(Javadoc 포함). 결함이나 수정할 곳을 암시하는 이름도 쓰지 않는다. 실제 업무 코드처럼 쓴다.
                - 숨은 테스트, 정답, 다른 풀이는 쓰지 않는다(다음 단계가 한다).
                - 규모: {{depth}}
                """.replace("{{depth}}", depth);
    }

    /** 3단계(DEV) 정답 쪽 프롬프트 */
    static String answerDev(int minutes) {
        return """
                당신은 개발 과제의 정답 쪽을 쓰는 출제자입니다. 설계도와, 응시자에게 이미 보이는 글·시작 코드를 받습니다.
                이렇게 씁니다:
                - hiddenTests: JUnit 5 숨은 테스트(test/…java). 시작 코드에서 실패하고 올바른 풀이에서 통과한다. flaws.caughtBy 이름의 메서드를 그 이름 그대로 둔다. 결함은 보이는 예 밖의 입력(같은 원인의 다른 경계나 다른 분기)에서 잡는다. 보이는 예는 시작 코드가 이미 통과한다. 사례만 하드코딩한 풀이가 실패하게 한다. judgments 의 어느 선택지를 골라도 통과하도록 선택과 상관없이 성립하는 결과만 단정한다.
                - 동시성·시간 테스트는 assertTimeoutPreemptively 로 10초 안에 끝내고, 대기 장치는 타임아웃돼도 테스트를 실패시키지 않으며 특정 구현 구조를 전제하지 않는다.
                - solution: 바뀐 src 파일의 전체 내용. 테스트 파일은 바꾸지 않는다. 시작 코드와 바뀐 줄 수(추가+삭제)는 {{lines}}줄 이상.
                - altSolutions: 정답과 구조가 다른 올바른 풀이 1~2개(note 한 줄, files 바뀐 src 파일 전체). 공개·숨은 테스트를 모두 통과해야 한다.
                - wrongFixes: 결함을 놓친 그럴듯한 수정 2~3개(reason 한 줄, files). 컴파일되고 공개 테스트는 통과하지만 숨은 테스트는 1개 이상 실패한다. 정답과 같은 동작의 다른 풀이는 오답이 아니다.
                - answer: 정답 설명(결함이 무엇인지, 판단 지점에서 어떤 선택이 인정되는지)을 마크다운으로.
                - taskFollowUp: 이 과제에 맞춘 후속 질문 1개(검증 방법을 묻는다. 결함 핵심어와 숨은 테스트 이름은 쓰지 않는다).
                """.replace("{{lines}}", minutes == 20 ? "2" : minutes == 40 ? "6" : "12");
    }

    /** 3단계(BIZ) 정답 쪽 프롬프트. depth 는 제한 시간별 과제 깊이 */
    static String answerBiz(String depth) {
        return """
                당신은 비개발 과제의 정답 쪽을 쓰는 출제자입니다. 설계도와, 응시자에게 이미 보이는 글·문서·CSV 를 받습니다.
                이렇게 씁니다:
                - questions: questionPlan 의 번호와 순서대로. 객관식(SINGLE)은 보기 4개에 정답 1개, 단답형(SHORT)은 자료 숫자로 계산하는 숫자 하나. 개수는 {{depth}}
                - flaws.caughtBy 의 문항이 결함을 놓치면 답이 달라지게 한다. 결론 객관식의 정답 보기는 conclusion.correct, 함정에 빠진 사람이 고를 보기는 conclusion.wrong 과 같은 문구로 쓴다.
                - 문구는 구할 값만 묻고 계산 방법과 순서는 정하지 않는다. 보기에는 다른 문항의 정답 값을 넣지 않는다.
                - 판단 지점은 객관식으로 묻지 않는다(어느 선택도 인정되므로 한쪽만 정답인 문항이 되면 안 된다). 기획안에서 근거로 채점한다. 단답형 문구에는 단위와 반올림 방법을 적고, accepted 첫 번째는 그 단위의 숫자만(쉼표 없이).
                - sources: 문항마다 근거 자료 2개 이상(서로 다른 문서 또는 CSV 의 경로와 쪽 번호. CSV 는 pages 를 빈 문자열로). 정답은 자료를 함께 봐야 알 수 있게 한다.
                - calc: v("자료/파일.csv", "열=값;열=값", "열 이름") 은 조건에 맞는 정확히 한 행의 칸 값. 사칙연산, 괄호, round(x, 자릿수)만. 객관식은 빈 문자열.
                - commonWrongAnswers: 단답형마다 함정에 빠진 값 1~2개(value 숫자만, reason). 정답과 0.5퍼센트 넘게 달라야 한다. 객관식은 빈 배열.
                - checklist: 마크다운. '## 필수 항목'(번호 목록: 결론, 자료 수치를 인용한 근거 3개, 함정 관련 계산, 판단 지점의 선택과 근거, 리스크와 대응), '## 참고 계산', '## 흔한 함정'.
                - taskFollowUp: 이 과제에 맞춘 후속 질문 1개(검증 방법을 묻는다. 결함 핵심어는 쓰지 않는다).
                """.replace("{{depth}}", depth);
    }
}
