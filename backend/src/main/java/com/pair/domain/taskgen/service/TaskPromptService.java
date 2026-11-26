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
            "api":["기존: PaymentDiscount.addDiscount(String amount)","기존: PaymentDiscount.totalDiscount(): BigDecimal","추가: PaymentDiscount.payable(String price): BigDecimal"],\
            "facts":[{"text":"7월 3일 접수 로그: 할인 0.10, 0.20을 등록한 뒤 합계가 0.30000000000000004로 표시됨","place":"notes"},\
            {"text":"정산 화면은 결제 원금 5.00에 할인 합계 7.00인 건을 0.00으로 표시합니다.","place":"description"},\
            {"text":"리포트 화면은 결제 원금 5.00에 할인 합계 7.00인 건을 -2.00으로 표시합니다.","place":"description"}],\
            "examples":[{"input":"새 PaymentDiscount에 할인 1.25, 2.25를 등록한 뒤 totalDiscount()","expected":"3.50"},\
            {"input":"새 PaymentDiscount에 할인 0.50, 0.25를 등록한 뒤 totalDiscount()","expected":"0.75"},\
            {"input":"할인 1.25, 2.25 등록 뒤 payable(\\"10.00\\")","expected":"6.50"},{"input":"새 PaymentDiscount에 할인 0.125, 0.125를 등록한 뒤 totalDiscount()","expected":"0.250"}],\
            "flaws":[{"what":"할인을 double 로 누적해 소수 합계가 어긋난다","where":"PaymentDiscount.addDiscount","evidence":"7월 3일 접수 로그: 할인 0.10, 0.20을 등록한 뒤 합계가 0.30000000000000004로 표시됨","caughtBy":"totalsExampleAmountsExactly","wrongAnswer":"합계를 소수 둘째 자리에서 반올림해 맞춘다","separatedBy":"totalsKeepThirdDecimal"}],\
            "judgments":[{"question":"할인 합계가 원금보다 클 때 결제액을 어떻게 보일 것인가","basis":"정산 화면은 결제 원금 5.00에 할인 합계 7.00인 건을 0.00으로 표시합니다.",\
            "options":[{"text":"0으로 보인다","fact":"정산 화면은 결제 원금 5.00에 할인 합계 7.00인 건을 0.00으로 표시합니다."},\
            {"text":"음수로 보인다","fact":"리포트 화면은 결제 원금 5.00에 할인 합계 7.00인 건을 -2.00으로 표시합니다."}]}],\
            "cases":[{"name":"totalsExampleAmountsExactly","situation":"할인 0.10, 0.20을 등록한 뒤 합계","expected":"0.30","basis":"7월 3일 접수 로그: 할인 0.10, 0.20을 등록한 뒤 합계가 0.30000000000000004로 표시됨"},\
            {"name":"payableAfterDiscount","situation":"할인 1.25, 2.25 등록 뒤 payable(\\"10.00\\")","expected":"6.50","basis":"할인 1.25, 2.25 등록 뒤 payable(\\"10.00\\") → 6.50"},\
            {"name":"totalsKeepThirdDecimal","situation":"할인 0.125, 0.125를 등록한 뒤 합계","expected":"0.250","basis":"새 PaymentDiscount에 할인 0.125, 0.125를 등록한 뒤 totalDiscount() → 0.250"}],\
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
            "cases":[{"name":"Q2","situation":"새봄 4월 전환율 4퍼센트, 6월 5퍼센트일 때 심사 변화율","expected":"25","basis":"운영팀은 새봄과 여름의 전환율을 각각 1퍼센트 증가로 기록했다.","ask":"새봄 캠페인의 4월 대비 6월 심사 변화율(퍼센트)","choices":[],"judgment":false},            {"name":"Q1","situation":"통과선과 변화율 비교","expected":"새봄 캠페인만 배정 후보로 정한다.","basis":"배정 후보 심사표의 통과선은 심사 변화율 22퍼센트 이상과 전환 건수 증가 90건 이상이다.","ask":"배정 후보 결론","choices":["새봄 캠페인만 배정 후보로 정한다.","여름 캠페인만 배정 후보로 정한다.","두 캠페인 모두 배정 후보로 정한다.","두 캠페인 모두 보류한다."],"judgment":false}],\
            "trapKeywords":["퍼센트포인트","상대 변화율"]}""";

    private static final String BLUEPRINT_COMMON = """
            당신은 AI 활용 역량 평가 과제의 설계자입니다. 글은 쓰지 말고 설계도(구조)만 냅니다. 응시자는 {{minutes}}분 동안 AI 어시스턴트와 함께 푼다.
            """ + PURPOSE + """
            이렇게 씁니다:
            - scenario: 업무 상황 한두 문장. 결함이나 원인은 말하지 않는다.
            - facts: 응시자 글에 들어갈 기록과 관측을 한 문장씩(사실 하나는 문장 하나. 여러 기록은 사실을 나눠 적고 줄바꿈·"/"로 잇지 않는다. 판단 근거·선택지 fact, 결함 evidence, 사례 basis 도 문장 하나씩이다. 코드가 문장이 여럿인 사실을 거부한다). 무슨 일이 있었나, 표의 값, 견적서·영수증·로그·메모가 말하는 것, 누가 무엇을 말했나만 쓴다. 결함의 근거, 판단의 근거, 배경을 같은 모양으로 섞는다. "~이다/~였다"로 끝나도 기록이나 관측(심사표의 통과선, 그달의 실제 값)이면 사실이다. 정의, 공식, 규칙, 계산 순서·방법, 원인, 고치는 법, "~해야 한다"처럼 어떻게 하라거나 어떻게 계산하라고 말하는 문장은 사실이 아니다(검수가 위반으로 본다).
              사실 아님: "총비용은 공급가액에 봉사료와 부가가치세를 합한 금액이다", "일평균은 전환 수를 집행일 수로 나눈 값이다".
              사실임: "견적서 항목: 대관 8,000,000원, 봉사료 10퍼센트 별도", "청구서 합계란에 '부가세 포함 금액'이라고 적혀 있다", "4월 전환 400건, 집행 20일", "7월 3일 로그: 할인 0.10, 0.20 등록 뒤 합계 0.30000000000000004".
            - flaws: 숨은 결함. 아래 [결함 유형 목록]의 유형 중에서만 고르고 what 은 유형 번호로 시작한다(예: "D3 …"). what 무엇이 틀리나, where 어디를 대조하면 보이나, evidence 응시자가 비교하면 결함을 읽어 낼 수 있는 원자료 한 줄(견적서·청구서 항목, 로그 한 줄. facts 문장 그대로), caughtBy 가 잡는 곳. AI 가 흔히 놓치는 지점에 두고 자료만으로 알 수 있어야 한다.
            - 결함 배치(난이도): 결함의 근거는 자료나 기록 두 곳 이상을 대조할 때만 드러나게 둔다. 설명, 요구사항, CSV 칸 하나나 문서 한 줄만으로는 올바른 읽기가 드러나면 안 된다(예: 단위 열이 함정을 알려 주는 CSV, 정의 한 줄이 정답 해석을 말하는 문서). 결함의 입력 값 바로 곁에 요구사항 예나 NOTES 로그를 두지 않는다(같은 입력을 다룬 예는 다른 값으로).
            - 통과선·승인 기준처럼 기준이 여러 조건이면 facts 에 그 조건들이 어떻게 결합하는지(예: "두 조건을 모두 충족해야 통과한다", "한 조건만 충족해도 통과한다")를 기록 문장으로 반드시 적는다. 결합 방식이 자료에 없으면 응시자가 답을 하나로 정할 수 없다.
            - judgments: 사람이 정할 모호한 선택. question 은 무엇을 정할지만 중립적으로 묻고 선택지나 결론을 담지 않는다. basis(근거 문장, facts 그대로), options 2개 이상(text, fact 그 선택을 뒷받침하는 서로 다른 관측, facts 그대로). 어느 선택을 해도 인정되고, 한쪽으로 못 박는 사실은 쓰지 않는다.
            - cases: 숨은 테스트(DEV)·문항(BIZ)이 확인할 사례 전부. name(DEV 는 테스트 메서드 이름, BIZ 는 Q번호), situation(입력·상황), expected(기대 결과), basis(그 동작을 뒷받침하는 자료의 사실이나 예 문장, facts·examples 그대로). basis 는 (가) 그 사례의 기대 결과를 직접 정하는 예 문장(examples 의 입력→기대 출력)이거나, (나) 그 기대 결과를 끌어낼 수 있는 사실(같은 규칙을 보여 주는 다른 입력의 기록, 영역 규칙 사실, 관측 값)이어야 한다. 관련 말만 하고 기대 결과를 끌어낼 수 없는 문장은 안 된다. 결함을 건드리는 바로 그 입력의 기대 결과를 그대로 적은 문장은 쓰지 않는다(결함이 샌다). 그 입력은 (나)로 근거를 댄다. 답 쪽은 이 사례만 구현하므로, 자료가 정하지 않는 동작(끝의 빈 항목, null, 빈 입력, 경계값 귀속 등)은 그 동작의 예를 facts·examples 에 더해 basis 를 만들거나 사례에서 뺀다. 결함마다 그것을 잡는 사례를 두고 flaws.caughtBy 는 그 사례의 name 으로 쓴다.
            - trapKeywords: 결함의 핵심어 2~5개(응시자 글에 나오면 안 되는 말).
            - title, summary(80자 이내, 결함을 드러내지 않음), code(영문 소문자·하이픈), category, competencies(1~2개 key).
            """;

    private static final String BLUEPRINT_DEV = """
            - api: 응시자 글과 코드가 함께 쓰는 공개 클래스·메서드 시그니처(순수 Java 21, 패키지 하나). 꼴은 반드시 "기존: 클래스.메서드(타입 이름, …): 반환형" 또는 "추가: 클래스.메서드(타입 이름, …): 반환형"이고 public·static 같은 수정자나 패키지 이름은 쓰지 않는다(예: "추가: OrderDiscount.payable(): long"). 시작 코드에 이미 구현돼 있는 것은 "기존: ", 응시자가 새로 만들 것은 "추가: " 로 시작한다. 결함은 "기존:" 코드에 둔다.
            - examples: 입력→기대 출력 예 3~5개. 실제로 맞는 값이다. "기존:" 메서드를 부르는 예는 결함이 있는 시작 코드도 이미 맞게 내는 입력만 쓴다(시작 코드에서 바로 틀리는 예는 결함이 눈에 띄는 것이다). 결함을 건드리는 입력은 숨은 테스트가 쓴다. "추가:" 메서드를 부르는 예는 시작 코드가 틀이라 맞게 낼 수 없으니 예외다.
            - api 의 "추가:" 메서드마다 그 메서드를 부르는 사례(cases)를 1개 이상 둔다(situation 에 호출을 적고, basis 는 examples 의 같은 입력→기대 출력). 숨은 테스트가 그 메서드를 직접 부르기 때문이다. 추가 메서드가 boolean 이면 참이 되는 사례와 거짓이 되는 사례를 둘 다, 숫자·목록·문자열이면 상수(0, 빈 목록, 빈 문자열)와 다른 결과가 나오는 사례를 둔다(구현 없이 상수만 돌려줘도 통과하는 테스트를 막는다). 단 결함을 잡는 사례(caughtBy, separatedBy)는 "추가:" 메서드를 부르지 않는다(틀이 던지는 예외로 결함과 상관없이 항상 실패하기 때문).
            - flaws.caughtBy: 결함을 잡는 숨은 테스트 메서드 이름(영문). 결함은 시작 코드나 공개 테스트의 한 줄에서 바로 보이면 안 된다.
            - flaws.wrongAnswer, flaws.separatedBy: 결함마다 AI 가 낼 법한 흔한 오답 방식(예: 반올림으로 때움, 날짜 대신 시간으로 셈)을 wrongAnswer 에 쓰고, 그 오답과 정답이 다른 결과를 내는 사례를 cases 에 하나 더 두어 separatedBy 에 그 name 을 쓴다(caughtBy 와 다른 사례, "기존:" 메서드만 부른다). 그 사례의 입력은 오답이 틀리게 나오는 값이어야 하고(정수에 가까운 값만 쓰면 오답과 안 갈린다), basis 는 examples 에 같은 입력→기대 출력으로 들어 있거나 그 결과를 끌어낼 수 있는 사실이어야 한다.
            - 요구사항 examples 와 description 에 적는 동작(입력→결과, "거절돼도 처리 건수는 늘지 않는다", 경계의 앞뒤)마다 그것을 확인하는 사례(cases)를 하나 이상 둔다. 사례가 없는 동작은 숨은 테스트가 보지 않아 상수만 돌려줘도 통과한다. "추가:" 메서드를 부르는 examples 는 같은 입력으로 그 메서드를 부르는 사례가 반드시 있어야 한다.
            - facts.place: description(상황·정의·사례) 또는 notes(현상과 재현 값을 적는 접수 메모, 장애 분석 과제만).
            - tables, figures, docs, conclusion, questionPlan 은 비운다.
            """;

    private static final String BLUEPRINT_BIZ = """
            - cases 의 BIZ 필드: ask 는 구할 값을 중립적으로(계산 방법 없이), choices 는 객관식이면 보기 후보 4개(정답 expected 와 같은 값 1개와 함정·흔한 오류 값 3개, 숫자나 짧은 결론만 쓰고 설명 문장은 없음), 단답형이면 빈 배열. 판단 지점(judgments)은 문항으로 묻지 않는다(기획안에서 채점): judgment 는 false 로 둔다. 결론·사실을 묻는 문항은 자료로 답이 하나로 정해져야 한다. 문항 종류는 choices 가 있으면 객관식.
            - tables: CSV 가 될 표(path 자료/이름.csv, columns, rows). 숫자 칸은 쉼표 없는 숫자, 칸 안에 쉼표 없음. 숫자는 여기와 figures 에서만 정한다.
            - figures: 문서에 쓰일 이름 붙은 수(label, value). 부분은 partOf 로 전체를, 합계는 sumOf 로 항목을 적어 정합성을 맞춘다(부분 ≤ 전체, 합계 일치). 정답이 되는 값은 figures 에 두어도 facts 에는 쓰지 않는다.
            - facts 의 모든 숫자는 tables·figures 값이어야 한다. facts.place 는 그 사실이 들어갈 문서 경로(또는 description). 용어 정의는 무엇을 세는지까지만 쓴다.
            - docs: 문서 계획(path 자료/이름.pdf, role 중립적인 이름). 판단과 결함의 근거는 서로 다른 문서에 나눠 대조해야만 드러나게 한다.
            - flaws.caughtBy: 결함을 놓치면 답이 달라지는 문항 번호 "Q2" 또는 "결론". conclusion: 정답 결론과 함정에 빠졌을 때의 결론(서로 다르게).
            - questionPlan: 문항 계획("Q1 객관식 …"). 단답형은 함정에 빠지면 값이 달라지게, 결론 객관식을 1개 이상 둔다. 문항은 서로 다른 자료 둘 이상을 대조해야 풀린다.
            - api, examples 는 비운다.
            """;

    /**
     * E6·M6 설계도 규칙(기준 문서 좋은_과제_기준.md 6장). 자료 전체는 응시 AI 의 첨부 맥락 한도를 넘고 필요한 묶음은 그 일부여야 한다.
     * 과제 깊이의 파일 수 규칙과 부딪히면 이 규칙을 따른다.
     */
    static String e6Blueprint(boolean biz, int minutes) {
        var limits = com.pair.domain.task.dto.AiLimitsDto.forMinutes(minutes);
        int[] r = BlueprintService.materialRange(biz, minutes);
        int cap = (int) (limits.contextChars() * MaterialGateService.NEEDED_MAX_RATIO);
        int allMin = (int) (limits.contextChars() * MaterialGateService.TOTAL_MIN_RATIO);
        String shape = biz
                ? "- fillers: 문서(자료/이름.pdf)와 CSV(자료/이름.csv) 중 풀이에 필요 없는 관련 적은 자료(path, summary 한 줄 내용: 같은 회사의 다른 팀 회의록, 지난 분기 보고, 다른 캠페인 집행표처럼 이 과제의 사실·함정·판단과 상관없는 업무 내용). docs·tables 로 이미 정한 파일과 경로가 겹치면 안 된다. docs·tables·fillers 를 합쳐 파일은 {{all}}개."
                : "- fillers: 풀이에 필요 없는 관련 적은 자료 Java 파일(src/…java, path 와 summary 한 줄 내용: 같은 서비스의 다른 도메인 클래스, 로그 형식, 설정 읽기처럼 이 과제의 결함·판단과 상관없는 코드). 필요한 묶음과 경로가 겹치면 안 된다. neededFiles + fillers 는 {{all}}개 안팎(공개 테스트·NOTES.md 는 목록에 없어도 된다).";
        return ("""
                E6·M6 (자료 분량, 과제 깊이의 파일 수보다 우선):
                - 응시 AI 에는 질문 한 번에 첨부 {{attach}}개, 첨부 글자 합계 {{ctx}}자 한도가 있다. 필요한 묶음만 골라 붙이면 풀리고, 자료 전체는 한도를 넘어 한 번에 다 붙일 수 없어야 한다.
                - neededFiles: 풀이에 꼭 필요한 파일 이름 {{needed}}개({{what}}). 이 묶음의 글자 수 합계는 {{cap}}자 이하가 되게 작게 설계한다. 결함의 근거·판단의 근거가 되는 사실은 필요한 묶음 안의 파일에 두고, 관련 적은 자료에는 두지 않는다.
                """ + shape + """
                - 자료 전체(필요한 묶음 + 관련 적은 자료)는 {{allChars}}자 이상이 되도록 관련 적은 자료를 정한다(싼 모델이 설계도 목록만 보고 만든다: 결함·사실·답은 모르므로 summary 에 쓰지 않는다).
                """).replace("{{attach}}", String.valueOf(limits.maxAttach())).replace("{{ctx}}", String.valueOf(limits.contextChars()))
                .replace("{{needed}}", r[0] == r[1] ? String.valueOf(r[0]) : r[0] + "~" + r[1])
                .replace("{{what}}", biz ? "docs·tables 에 정한 문서·표의 경로" : "시작 코드 src/…java, 공개 테스트 test/…java, NOTES.md 의 경로")
                .replace("{{cap}}", String.valueOf(cap)).replace("{{allChars}}", String.valueOf(allMin))
                .replace("{{all}}", r[2] + "~" + r[3]);
    }

    /** 1단계 설계도 프롬프트. depth 는 제한 시간별 과제 깊이(기존 표) */
    static String blueprint(boolean biz, int minutes, String depth, boolean e6) {
        return blueprint(biz, minutes, depth) + (e6 ? "\n" + e6Blueprint(biz, minutes) : "");
    }

    /** 1단계 설계도 프롬프트. depth 는 제한 시간별 과제 깊이(기존 표) */
    static String blueprint(boolean biz, int minutes, String depth) {
        String body = BLUEPRINT_COMMON + (biz ? BLUEPRINT_BIZ : BLUEPRINT_DEV) + "과제 깊이: {{depth}}\n[결함 유형 목록]\n" + resource("taskgen/flaw-types.md")
                + "\n좋은 예(줄인 설계도):\n" + (biz ? BIZ_EXAMPLE : DEV_EXAMPLE);
        return body.replace("{{minutes}}", String.valueOf(minutes)).replace("{{depth}}", depth) + TaskGenRequest.COMPETENCY_PROMPT;
    }

    /** 2단계(DEV) 작성 프롬프트 */
    static final String WRITING_DEV = """
            당신은 응시자에게 보일 과제 글을 쓰는 작성자입니다. 받은 사실 목록과 예 목록으로 description, requirements, notes 를 씁니다.
            이렇게 씁니다:
            - 사실 문장은 하나도 빼지 말고 값과 표현을 바꾸지 않고 그대로 넣는다. place 가 description 이면 description 에, notes 면 notes 에 둔다. 문장 사이를 잇는 말은 더해도 된다.
            - 사실·예 밖에 쓰는 글은 상황, 인물, 업무 흐름 같은 배경뿐이다. 새 규칙, 조건, 정의, 처리 방식, 용도 설명은 쓰지 않는다(예: "구분자는 문자 그대로 취급한다", "이 API는 ~에 쓰인다"). 새 사실·숫자·원인·이유도 쓰지 않고, 어느 사실이 중요한지 짚지 않는다.
            - 공개 API 에서 "기존:" 메서드는 이미 있는 것이라 추가·구현하라고 쓰지 않는다. "추가:" 메서드만 새로 만들라고 쓴다(어느 쪽도 어디가 틀렸다고 쓰지 않는다).
            - description: 상황과 해야 할 일(기능 추가)을 업무 글투로 3~6문장. '정해야 할 것'이 있으면 "~를 정하고 선택한 이유를 설명하세요"로만 요청한다. 선택지나 어느 쪽이 맞다는 말은 쓰지 않는다.
            - requirements: 예 목록을 한 줄에 하나씩 "입력 → 기대 출력"으로, 값은 그대로 쓴다. 예 밖의 규칙 문장은 쓰지 않는다.
            - notes: place 가 notes 인 사실을 접수 메모로(현상과 재현 값만). 없으면 빈 문자열.
            - taskFollowUp: 후속 질문 1개. "정해야 할 것"과 일반 질문 틀(왜 그렇게 정했나, 조건이 바뀌면 어떻게 하겠나, 이대로 내보내겠나)로만 쓰고 이 과제의 말로 다듬는다. 사실 목록의 값·기록·자료의 칸이나 란은 쓰지 않고, 어디를 확인하라는 말도 쓰지 않는다.
            - 한국어, 실제 업무 글처럼. 한자(漢字)와 중국어 글자는 쓰지 않는다.
            """;

    /** 2단계(BIZ) 작성 프롬프트 */
    static String writingBiz(int minutes, boolean e6) {
        String base = writingBiz(minutes);
        if (!e6) return base;
        int hi = Math.min(minutes == 20 ? 4000 : 10000, (int) (com.pair.domain.task.dto.AiLimitsDto.forMinutes(minutes).contextChars() * 0.55));
        return base + "\n- E6·M6: 문서 전체 분량 규칙은 위보다 이 규칙이 우선한다. 문서 계획의 문서를 합쳐 " + hi + "자 이하로 쓴다(CSV 와 함께 필요한 묶음이 되어 응시 AI 의 첨부 한도 안에 들어야 한다). 쪽 수는 1~3쪽씩.";
    }

    static String writingBiz(int minutes) {
        return """
                당신은 응시자에게 보일 과제 글과 자료 문서를 쓰는 작성자입니다. 받은 사실 목록, 문서 계획, CSV 표로 description, requirements, documents 를 씁니다.
                이렇게 씁니다:
                - 사실 문장은 하나도 빼지 말고 값과 표현을 바꾸지 않고 그대로, place 의 문서에 넣는다. 각 사실은 가장 자연스러운 한 곳에 정확히 한 번만 쓰고 다른 쪽이나 다른 문서에서 같은 문장을 되풀이하지 않는다(다른 곳에서 필요하면 다른 말로 이어 쓰되 새 사실은 더하지 않는다). 사실을 따로 떠 있는 한 줄로 두지 말고 다른 내용과 같은 문단 안에 섞는다.
                - 사실·CSV 밖에 쓰는 글은 상황, 인물, 업무 흐름 같은 배경뿐이다. 새 규칙, 조건, 정의, 계산 방법은 쓰지 않는다. 새 사실·숫자·원인·이유도 쓰지 않고(숫자는 사실 문장과 CSV 값만), 어느 사실이 중요한지, 어느 쪽 선택이 맞는지 짚지 않는다.
                - 문서마다 문서 계획의 path 와 같은 경로로 쪽별 글(pages)을 쓴다. 쪽 하나는 900자 이내 30줄 이내, 첫 줄은 짧은 제목. 마크다운 기호, 이모지, 특수 기호는 쓰지 않는다. 문서 전체 분량은 합쳐 {{size}}. 배경 설명은 숫자 없이 채운다.
                - "~해야 한다", 두 대상이 다르다는 지적, 원인 설명은 쓰지 않는다.
                - 문서는 실제 회사의 업무 문서(회의 메모, 정산 기록, 집행 확인서, 보고서)처럼 쓴다. 문서 안에서 과제, 응시자, AI 어시스턴트, 기획안 양식, 이 쪽이 무엇을 다루는지에 대한 설명(예: "이 페이지는 ~를 정리한다", "~결론을 덧붙이지 않는다")은 쓰지 않는다. 담당자가 한 일과 본 값을 기록처럼 쓴다. 문서의 모든 문장은 사실, 숫자, 판단, 요청 중 하나를 담는다. 사람의 동작만 서술하는 문장(서류를 펼쳤다, 정돈했다, 묶어 두었다, 메모에 옮겼다 같은 것)과 서류를 정리하는 모습만 그리는 문장, 담당자·팀이 검토 중이다·의견을 요청한다·기록되어 있지 않다처럼 상태만 말하는 문장은 쓰지 않는다(같은 꼴이 3개 이상 되풀이되면 다시 쓰게 된다).
                  나쁜 예: "담당자는 자료를 검토 중이다." "팀은 의견을 요청한다." "이 항목은 기록되어 있지 않다."
                  좋은 예: "4월 전환 400건, 집행 20일이었다.", "운영팀은 새봄과 여름의 전환율을 각각 1퍼센트 증가로 기록했다."(받은 사실), "6월 말까지 배정 후보 두 곳의 의견을 회신해 주세요."(요청: 누가 무엇을 언제까지)
                - description: 상황, 응시자의 역할, 할 일(1페이지 기획안, 먼저 할 작업 하나와 근거, 리스크와 대응, 문항 답)을 3~5문장. '정해야 할 것'은 "~를 정하고 근거를 설명하세요"로만 요청하고 선택지나 결론은 담지 않는다. 실존 회사 이름은 쓰지 않는다.
                - taskFollowUp: 후속 질문 1개. "정해야 할 것"과 일반 질문 틀(왜 그렇게 정했나, 조건이 바뀌면 어떻게 하겠나, 이대로 내보내겠나)로만 쓰고 이 과제의 말로 다듬는다. 사실의 값·자료의 칸이나 란은 쓰지 않고, 어디를 확인하라는 말도 쓰지 않는다.
                - requirements: 기획안에 담을 것을 2~4개 짧게(결론과 자료 수치를 인용한 근거 3개 이상, 선택과 근거, 리스크와 대응). 정답이 되는 숫자는 쓰지 않는다.
                - 한국어, 실제 업무 글처럼. 한자(漢字)와 중국어 글자는 쓰지 않는다.
                """.replace("{{size}}", minutes == 20 ? "1,500~4,000자" : "3,000~10,000자");
    }

    /** 2'단계(DEV) 시작 코드 프롬프트(E6·M6 켜짐): 필요한 묶음의 경로로만 만들고 관련 적은 자료는 만들지 않는다 */
    static String code(String depth, boolean e6, int minutes) {
        if (!e6) return code(depth);
        int cap = (int) (com.pair.domain.task.dto.AiLimitsDto.forMinutes(minutes).contextChars() * MaterialGateService.NEEDED_MAX_RATIO);
        return code(depth) + "\n- E6·M6: 설계도 neededFiles 의 경로마다 그 경로의 파일을 만든다(공개 테스트 test/…java 는 neededFiles 에 없어도 만든다). 그 밖의 src 파일은 만들지 않고, fillers 의 경로는 다른 단계가 만든다. "
                + "neededFiles 파일들의 글자 수 합계는 " + cap + "자 이하로 작게 쓰고, 파일 규모 문장(파일 몇 개)은 이 규칙이 우선한다.";
    }

    /** 단계 4' 프롬프트: 결함을 모르는 싼 모델이 관련 적은 자료를 쓴다(E6·M6) */
    static String filler(boolean biz) {
        return biz ? """
                당신은 회사의 업무 자료를 쓰는 작성자입니다. 상황과 만들 파일 목록(경로와 한 줄 내용)을 받아 파일마다 실제 업무에서 함께 쌓여 있을 법한 자료를 씁니다.
                이렇게 씁니다:
                - files: 목록의 path 마다 하나, path 는 목록과 한 글자도 다르지 않게. .pdf 는 content 에 글(첫 줄은 짧은 제목, 문단은 빈 줄로 나눔)을 쓰고, .csv 는 content 에 쉼표로 구분한 표(첫 줄 머리글, 칸 안에 쉼표 없음, 숫자는 쉼표 없는 숫자)를 쓴다. 쪽 나누기는 코드가 한다.
                - 한 줄 내용에 맞는 일반 업무 내용만 쓴다: 회의록, 집행 기록, 담당자 메모, 지난 기록 표. 숫자와 날짜, 이름은 자유롭게 지어도 되지만 서로 맞게 쓴다. 이 과제의 답·결론·함정·판단은 모르니 쓰려고 하지 않는다. 결론을 내리거나 무엇이 맞다고 짚는 문장, 계산 방법, 규칙 설명도 쓰지 않는다.
                - 다른 파일(함께 있는 파일 이름)의 내용을 지어서 인용하지 않는다. 문서 안에서 과제, 응시자, AI, 이 자료의 용도를 말하지 않는다. 마크다운 기호, 이모지는 쓰지 않는다. 실존 회사 이름은 쓰지 않는다.
                - 분량은 입력의 파일 하나당 글자 수를 채운다(짧으면 다시 만든다). 한국어로 쓰고 한자(漢字)와 중국어 글자는 쓰지 않는다.
                """ : """
                당신은 서비스 개발자입니다. 상황과 만들 Java 파일 목록(경로와 한 줄 내용)을 받아 파일마다 실제 서비스에 함께 있을 법한 코드를 씁니다.
                이렇게 씁니다:
                - files: 목록의 path 마다 하나, path 는 목록과 한 글자도 다르지 않게, content 는 파일 전체. package 선언은 경로의 폴더(src/ 아래)와 같게 쓴다.
                - 순수 Java 21 의 JDK 클래스만 쓴다. 다른 파일(함께 있는 파일 이름)의 클래스는 부르거나 상속하거나 import 하지 않는다. 각 파일은 혼자 컴파일되어야 하고 다른 파일과 클래스 이름이 겹치지 않게 한다. public 클래스는 파일 이름과 같게.
                - 주석은 쓰지 않는다(Javadoc 포함). 한 줄 내용에 맞는 평범한 업무 코드(필드, 생성자, 메서드, 검증, 포맷)를 쓴다. main 메서드와 테스트는 쓰지 않는다. 이 과제의 버그·정답은 모르니 쓰려고 하지 않는다.
                - 분량은 입력의 파일 하나당 글자 수를 채운다(짧으면 다시 만든다). 필드와 메서드를 충분히 쓰고, 한자(漢字)와 중국어 글자는 쓰지 않는다.
                """;
    }

    /** 2'단계(DEV) 시작 코드 프롬프트. depth 는 제한 시간별 과제 깊이 */
    static String code(String depth) {
        return """
                당신은 개발 과제의 시작 코드와 공개 테스트를 쓰는 개발자입니다. 설계도의 api 를 구현한 시작 코드와 공개 테스트를 씁니다.
                이렇게 씁니다:
                - 순수 Java 21 + JUnit 5 만. 외부 라이브러리, 파일, 네트워크, Spring 은 쓰지 않는다. 경로는 src/<패키지>/X.java, test/<패키지>/XTest.java.
                - api 의 "추가:" 메서드는 시작 코드에 반드시 틀로 둔다: 설계도 시그니처 그대로(이름, 매개변수 타입, 반환 타입) 선언하고 본문은 throw new UnsupportedOperationException("TODO"); 한 줄만. 빼면 숨은 테스트가 컴파일되지 않는다. 구현은 하지 않는다. 공개 테스트는 "기존:" 메서드만 쓴다. "기존:" 메서드는 구현한다.
                - 시작 코드는 컴파일되고 공개 테스트를 통과한다. flaws.where 의 결함은 평범한 구현으로 들어 있어야 하고, 공개 테스트는 "기존:" 메서드의 examples 를 확인한다. 시작 코드는 모든 기존 examples 에서 이미 맞는 값을 내야 하고, 결함은 examples 밖의 입력에서만 드러난다.
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
                - hiddenTests: JUnit 5 숨은 테스트(test/…java)로 설계도 cases 만 구현한다. api 의 "추가:" 메서드는 숨은 테스트가 직접 호출해 단정한다. 사례마다 @Test 메서드 하나(이름은 case.name), 단정은 case.expected 만 확인한다. cases 에 없는 동작(빈 입력, null 대체값처럼 자료에 근거 없는 것)은 단정하지 않는다. 시작 코드에서 실패하고 올바른 풀이에서 통과한다. 결함은 보이는 예 밖의 입력(같은 원인의 다른 경계나 다른 분기)에서 잡는다. 보이는 예는 시작 코드가 이미 통과한다. 사례만 하드코딩한 풀이가 실패하게 한다. judgments 의 어느 선택지를 골라도 통과하도록 선택과 상관없이 성립하는 결과만 단정한다.
                - 동시성·시간 테스트는 assertTimeoutPreemptively 로 10초 안에 끝내고, 대기 장치는 타임아웃돼도 테스트를 실패시키지 않으며 특정 구현 구조를 전제하지 않는다.
                - solution: 바뀐 src 파일의 전체 내용. 테스트 파일은 바꾸지 않는다. 시작 코드와 바뀐 줄 수(추가+삭제)는 {{lines}}줄 이상.
                - altSolutions: 정답과 구조가 다른 올바른 풀이 1~2개(note 한 줄, files 바뀐 src 파일 전체. 경로는 src/ 로 시작하는 파일만, NOTES.md 같은 다른 파일은 넣지 않는다). 공개·숨은 테스트를 모두 통과해야 한다.
                - wrongFixes: 결함을 놓친 그럴듯한 수정 2~3개(reason 한 줄, files). 설계도 flaws.wrongAnswer 마다 그 방식의 오답 하나를 만들고, flaws.separatedBy 사례의 테스트가 그 오답을 떨어뜨리게 한다(오답이 숨은 테스트를 모두 통과하면 안 된다). 컴파일되고 공개 테스트는 통과하지만 숨은 테스트는 1개 이상 실패한다. 정답과 같은 동작의 다른 풀이는 오답이 아니다.
                - answer: 정답 설명(결함이 무엇인지, 판단 지점에서 어떤 선택이 인정되는지)을 마크다운으로. 응시자에게 보이는 description·requirements 에 없는 요구나 동작(예: 활성일 전 방문 거절)을 새로 쓰지 않는다. 설명에 쓴 동작은 모두 보이는 글에 있는 것이다. 한자(漢字)와 중국어 글자는 쓰지 않는다.
                """.replace("{{lines}}", minutes == 20 ? "2" : minutes == 40 ? "6" : "12");
    }

    /** 3단계(BIZ) 문항 글 프롬프트: 결함을 모르는 단계 */
    static final String QUESTIONS_BIZ = """
            당신은 응시자에게 보일 문항 글을 쓰는 작성자입니다. 상황, 응시자에게 보이는 글, 문항 목록(구할 값과 보기 후보)을 받습니다.
            이렇게 씁니다:
            - questions: 문항 목록의 id 마다 하나, 순서대로. content 는 구할 값만 묻는 한두 문장. 계산 방법·순서·원인·어느 자료를 어떻게 쓰라는 말은 쓰지 않는다.
            - 단답형 문구에는 단위와 반올림 방법(예: 소수 첫째 자리까지)을 적는다.
            - 객관식 choices 는 받은 보기 후보를 값 그대로, 설명 문장 없이 숫자나 짧은 결론만 쓴다(단위는 붙여도 된다). 보기 순서는 자유.
            - 보기와 문구에 다른 문항의 값을 넣지 않는다. 어느 보기가 맞는지 짚지 않는다. 한자(漢字)와 중국어 글자는 쓰지 않는다.
            """;

    /** 4단계(BIZ) 정답 쪽 프롬프트. depth 는 제한 시간별 과제 깊이 */
    static String answerBiz(String depth) {
        return """
                당신은 비개발 과제의 정답 쪽을 쓰는 출제자입니다. 설계도, 응시자에게 이미 보이는 글·문서·CSV, 문항 글을 받습니다. 응시자에게 보이는 글은 쓰지 않고 정답 키만 씁니다.
                이렇게 씁니다:
                - keys: 문항마다 하나(id 는 사례 name, 예 Q2). 문항 수는 설계도 cases 와 같다. {{depth}}
                - 단답형: accepted 는 설계도 cases 의 expected 를 자료 숫자로 확인한 값(첫 번째는 문항 문구의 단위의 숫자만, 쉼표 없이), calc 는 자료로 확인하는 검산식. 객관식과 판단 지점 문항은 accepted 와 calc 를 빈 값으로 둔다.
                - calc: v("자료/파일.csv", "열=값;열=값", "열 이름") 은 조건에 맞는 정확히 한 행의 칸 값. 사칙연산, 괄호, round(x, 자릿수)만.
                - sources: 문항마다 근거 자료 2개 이상(서로 다른 문서 또는 CSV 의 경로와 쪽 번호. CSV 는 pages 를 빈 문자열로). 정답은 자료를 함께 봐야 알 수 있게 한다.
                - commonWrongAnswers: 단답형마다 함정에 빠진 값 1~2개(value 숫자만, reason). 정답과 0.5퍼센트 넘게 달라야 한다. 객관식은 빈 배열.
                - checklist: 마크다운. '## 필수 항목'(번호 목록: 결론, 자료 수치를 인용한 근거 3개, 함정 관련 계산, 판단 지점의 선택과 근거, 리스크와 대응), '## 참고 계산', '## 흔한 함정'. 한국어로만 쓰고 한자(漢字)와 중국어 글자는 쓰지 않는다.
                """.replace("{{depth}}", depth);
    }

    private static final java.util.Map<String, String> RESOURCES = new java.util.concurrent.ConcurrentHashMap<>();

    /** 관문 검사 점검의 프롬프트(resources/taskgen/review/<이름>.md) */
    static String check(String name) {
        return resource("taskgen/review/" + name + ".md");
    }

    static String resource(String path) {
        return RESOURCES.computeIfAbsent(path, r -> {
            try (var in = new org.springframework.core.io.ClassPathResource(r).getInputStream()) {
                return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            } catch (java.io.IOException e) {
                throw new IllegalStateException("프롬프트 파일(" + r + ")을 읽지 못했습니다", e);
            }
        });
    }
}
