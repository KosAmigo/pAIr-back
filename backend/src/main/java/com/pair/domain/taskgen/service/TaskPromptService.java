package com.pair.domain.taskgen.service;

import com.pair.domain.task.dto.AiLimitsDto;
import com.pair.domain.taskgen.dto.TaskGenRequest;

/**
 * 단계별 생성의 프롬프트(설계 pAIr-docs 설계/과제_생성_파이프라인.md 3장, 기준 설계/좋은_과제_기준.md 의 E·M·Q 번호).
 * 2026-11-26 g5: 지시는 영어로 짧게, 응시자에게 보이는 글(사실·예·근거 문장·문서·문항)과 말투 예는 한국어로 둔다.
 * 설계도 프롬프트 끝의 체크리스트는 형식 검사(BlueprintService.problems·materialProblems, BizNumbersService.blueprintProblems)와 1:1 로 맞춘다.
 * 점검 기록: taskgen-runs/_verify/prompt-audit-2026-11-26.md
 * 과제를 한 번에 쓰던 옛 프롬프트(DevTaskGeneratorService·BizTaskGeneratorService 의 SYSTEM)는 pipeline=oneshot 일 때만 쓴다.
 */
final class TaskPromptService {

    private TaskPromptService() {}

    /** 모든 생성 단계가 같이 쓰는 한국어 말투 규칙(한자·일본어 섞임: g2 11 설계도, g3 04 코드, g4 01 4', g4 08 정답) */
    static final String KOREAN_STYLE = """
            Korean output rules (every Korean text you write):
            - Natural Korean as written at a real company. Keep one sentence ending style per document (~다/~했다 or ~합니다).
            - Hangul only for Korean words. Never write Chinese characters (漢字) or Japanese, not even one word: "전체" not "全体", "설정" not "設定", "이번" not "本次".
            - No translationese.
              나쁜 예: "본 캠페인에 있어서 전환율의 산출은 담당자에 의해 진행되어졌다." "상기 기준에 의거하여 처리됨."
              좋은 예: "담당자가 4월 캠페인 전환율을 계산했다." "4월 전환 400건, 집행 20일이었다."
            - Code identifiers, file paths and numbers stay exactly as given.
            """;

    static final String DEV_EXAMPLE = """
            {"title":"정산 기록에 맞춘 적립금과 결제액","summary":"주문 계산 코드에 적립금 사용 뒤 결제액 조회를 더한다","code":"order-points","category":"기능 구현","competencies":["verification"],\
            "scenario":"쇼핑몰 주문 화면에서 상품을 담고 적립금을 조회하는 코드에 적립금 사용 뒤 결제액 조회를 더한다.",\
            "api":["기존: OrderCheckout.addItem(String name, long price, int quantity)","기존: OrderCheckout.points(): long","추가: OrderCheckout.payable(long usedPoints): long"],\
            "facts":[{"text":"8월 2일 정산 기록: 12,345원 상품 1개 주문에 적립금 120원이 지급되었다.","place":"notes"},\
            {"text":"8월 9일 정산 기록: 9,990원 상품과 5,050원 상품을 함께 주문한 건에 적립금 140원이 지급되었다.","place":"notes"},\
            {"text":"8월 12일 고객 문의: 적립금 내역 화면에서 지급일이 하루 늦게 보인다는 문의가 접수되었다.","place":"notes"},\
            {"text":"고객센터는 적립금 25,000원으로 20,000원어치를 주문하려던 고객에게 결제액 0원으로 안내했다.","place":"description"},\
            {"text":"제휴몰 연동 주문은 주문 금액보다 큰 적립금 사용 요청을 거절했다.","place":"description"}],\
            "examples":[{"input":"새 OrderCheckout에 addItem(\\"노트\\", 10000, 2)를 한 뒤 points()","method":"OrderCheckout.points","args":[],"expected":"200"},\
            {"input":"새 OrderCheckout에 addItem(\\"노트\\", 10000, 2)를 한 뒤 payable(500)","method":"OrderCheckout.payable","args":[["usedPoints","500"]],"expected":"19500"},\
            {"input":"새 OrderCheckout에 addItem(\\"노트\\", 10000, 2)를 한 뒤 payable(0)","method":"OrderCheckout.payable","args":[["usedPoints","0"]],"expected":"20000"}],\
            "exampleSetups":[{"method":"OrderCheckout.points","text":"담은 상품: 노트 10,000원 2개"},{"method":"OrderCheckout.payable","text":"담은 상품: 노트 10,000원 2개"}],"exampleNote":"",\
            "flaws":[{"what":"R3 적립금을 주문 합계에서 한 번 반올림해 정산 기록(상품별 10원 단위 버림)과 어긋난다","where":"RewardPolicy.pointsFor (src/order/RewardPolicy.java)","evidence":"8월 2일 정산 기록: 12,345원 상품 1개 주문에 적립금 120원이 지급되었다.","caughtBy":"pointsFollowSettlementRecord","wrongAnswer":"주문 합계의 적립금을 10원 단위로 버린다","separatedBy":"pointsPerItemForMixedOrder"}],\
            "judgments":[{"question":"적립금 사용액이 주문 금액보다 클 때 어떻게 처리할 것인가","basis":"고객센터는 적립금 25,000원으로 20,000원어치를 주문하려던 고객에게 결제액 0원으로 안내했다.",\
            "options":[{"text":"결제액을 0으로 한다","fact":"고객센터는 적립금 25,000원으로 20,000원어치를 주문하려던 고객에게 결제액 0원으로 안내했다."},\
            {"text":"사용 요청을 거절한다","fact":"제휴몰 연동 주문은 주문 금액보다 큰 적립금 사용 요청을 거절했다."}]}],\
            "cases":[{"name":"pointsFollowSettlementRecord","situation":"addItem(\\"가방\\", 23456, 1) 뒤 points()","expected":"230","basis":"8월 2일 정산 기록: 12,345원 상품 1개 주문에 적립금 120원이 지급되었다."},\
            {"name":"pointsPerItemForMixedOrder","situation":"addItem(\\"모자\\", 15550, 1), addItem(\\"양말\\", 4480, 1) 뒤 points()","expected":"190","basis":"8월 9일 정산 기록: 9,990원 상품과 5,050원 상품을 함께 주문한 건에 적립금 140원이 지급되었다."},\
            {"name":"payableAfterPoints","situation":"addItem(\\"노트\\", 10000, 2) 뒤 payable(500)","expected":"19500","basis":"새 OrderCheckout에 addItem(\\"노트\\", 10000, 2)를 한 뒤 payable(500) → 19500"},\
            {"name":"payableWithoutPoints","situation":"addItem(\\"노트\\", 10000, 2) 뒤 payable(0)","expected":"20000","basis":"새 OrderCheckout에 addItem(\\"노트\\", 10000, 2)를 한 뒤 payable(0) → 20000"}],\
            "trapKeywords":["10원 단위","버림","상품별"],\
            "neededFiles":["src/order/OrderCheckout.java","src/order/RewardPolicy.java","NOTES.md"],\
            "fillers":[{"path":"src/order/PointExpiryPolicy.java","summary":"적립금 지급일로부터 유효기간이 끝나는 날짜를 계산하는 클래스","confusing":true},\
            {"path":"src/order/ReceiptFormatter.java","summary":"영수증 금액 줄을 화면 표시용 문자열로 만드는 클래스","confusing":false},\
            {"path":"src/order/DeliveryLogReader.java","summary":"배송 로그 한 줄을 읽어 날짜와 상태로 나누는 클래스","confusing":false}]}""";

    static final String BIZ_EXAMPLE = """
            {"title":"캠페인 개선 예산과 추진 순서 검토","summary":"두 캠페인의 예산 배정 후보와 개선 작업 순서를 정한다","code":"campaign-review","category":"기획안 작성","competencies":["decomposition"],\
            "scenario":"가상의 구독 서비스 회사가 다음 분기 캠페인 개선 예산 배정 후보와 개선 작업 순서를 검토한다.",\
            "tables":[{"path":"자료/캠페인 실적.csv","columns":["캠페인","월","방문자 수","전환 건수"],\
            "rows":[["새봄","4월","10000","400"],["새봄","6월","10000","500"],["여름","4월","10000","500"],["여름","6월","10000","600"]]}],\
            "figures":[{"label":"통과선 심사 변화율","value":"22"},{"label":"통과선 전환 건수 증가","value":"90"},{"label":"운영팀 기록 증가","value":"1"},{"label":"이전 기준 전환율","value":"2"},{"label":"이전 현재 전환율","value":"3"},{"label":"이전 심사 변화율","value":"50"},{"label":"6월 상담 처리 여력","value":"1050"}],\
            "docs":[{"path":"자료/검토 안내.pdf","role":"검토 안내"},{"path":"자료/운영 메모.pdf","role":"운영 메모"}],\
            "facts":[{"text":"배정 후보 심사표의 통과선은 심사 변화율 22퍼센트 이상과 전환 건수 증가 90건 이상이다.","place":"자료/검토 안내.pdf"},\
            {"text":"심사표는 두 조건을 모두 충족한 캠페인만 통과로 표시한다.","place":"자료/검토 안내.pdf"},\
            {"text":"이전 검토의 심사표에는 기준 시점 전환율 2퍼센트, 현재 시점 전환율 3퍼센트인 건의 심사 변화율이 50퍼센트로 적혀 있다.","place":"자료/검토 안내.pdf"},\
            {"text":"운영팀은 새봄과 여름의 전환율을 각각 1퍼센트 증가로 기록했다.","place":"자료/운영 메모.pdf"},\
            {"text":"6월의 두 캠페인 신청 완료 건을 처리할 수 있는 상담팀 여력은 합계 1050건이었다.","place":"자료/운영 메모.pdf"},\
            {"text":"개발팀은 신청 화면 개선과 상담 예약 개선 중 한 가지만 다음 달에 착수할 수 있다고 회신했다.","place":"자료/운영 메모.pdf"},\
            {"text":"새봄 담당자는 신청 화면에서 이탈한 방문자의 문의가 가장 많았다고 기록했다.","place":"자료/운영 메모.pdf"},\
            {"text":"상담팀은 신청 완료 건 일부가 상담 대기로 다음 달로 넘어갔다고 기록했다.","place":"자료/검토 안내.pdf"}],\
            "flaws":[{"what":"B1 운영 메모의 1퍼센트는 전환율 변화 폭이고 심사표 변화율은 기준 시점 대비 상대 변화율이다","where":"자료/운영 메모.pdf 대 자료/검토 안내.pdf·자료/캠페인 실적.csv",\
            "evidence":"운영팀은 새봄과 여름의 전환율을 각각 1퍼센트 증가로 기록했다.","caughtBy":"Q2"}],\
            "judgments":[{"question":"다음 달에 어느 개선 작업부터 착수할 것인가","basis":"개발팀은 신청 화면 개선과 상담 예약 개선 중 한 가지만 다음 달에 착수할 수 있다고 회신했다.",\
            "options":[{"text":"신청 화면 개선","fact":"새봄 담당자는 신청 화면에서 이탈한 방문자의 문의가 가장 많았다고 기록했다."},\
            {"text":"상담 예약 개선","fact":"상담팀은 신청 완료 건 일부가 상담 대기로 다음 달로 넘어갔다고 기록했다."}]}],\
            "conclusion":{"correct":"새봄 캠페인만 배정 후보로 정한다.","wrong":"두 캠페인 모두 보류한다."},\
            "questionPlan":["Q1 객관식 배정 후보 결론","Q2 단답형 새봄 심사 변화율","Q3 객관식 6월 상담 여력"],\
            "cases":[{"name":"Q1","situation":"통과선과 두 캠페인의 변화율 비교","expected":"새봄 캠페인만 배정 후보로 정한다.","basis":"심사표는 두 조건을 모두 충족한 캠페인만 통과로 표시한다.","ask":"배정 후보 결론","choices":["새봄 캠페인만 배정 후보로 정한다.","여름 캠페인만 배정 후보로 정한다.","두 캠페인 모두 배정 후보로 정한다.","두 캠페인 모두 보류한다."],"judgment":false},\
            {"name":"Q2","situation":"새봄 4월 전환율 4퍼센트, 6월 5퍼센트일 때 심사 변화율","expected":"25","basis":"운영팀은 새봄과 여름의 전환율을 각각 1퍼센트 증가로 기록했다.","ask":"새봄 캠페인의 4월 대비 6월 심사 변화율(퍼센트)","choices":[],"judgment":false},\
            {"name":"Q3","situation":"6월 두 캠페인 전환 건수 합계와 상담 여력 비교","expected":"여력 부족","basis":"6월의 두 캠페인 신청 완료 건을 처리할 수 있는 상담팀 여력은 합계 1050건이었다.","ask":"6월 상담 여력 판정","choices":["여력 부족","여력 충분","여력과 정확히 같음","판단할 자료 없음"],"judgment":false}],\
            "trapKeywords":["퍼센트포인트","상대 변화율"],\
            "neededFiles":["자료/캠페인 실적.csv","자료/검토 안내.pdf","자료/운영 메모.pdf"],\
            "fillers":[{"path":"자료/지난 분기 캠페인 결산.pdf","summary":"지난 분기 다른 캠페인의 집행 결과와 담당자 소감을 정리한 결산 문서","confusing":true},\
            {"path":"자료/광고 소재 제작 일정.csv","summary":"광고 소재별 제작 담당과 완료 예정일 표","confusing":false},\
            {"path":"자료/고객센터 주간 회의록.pdf","summary":"고객센터 인력 교대와 교육 일정을 다룬 주간 회의록","confusing":false}]}""";

    /** 단계별 생성의 과제 규모(기준 문서 6장). E6 를 켜면 6장 분량, 끄면 옛 깊이 문장(oneshot 과 같음) */
    static String stageDepth(boolean biz, int minutes, boolean e6) {
        if (!e6) return biz ? BizTaskGeneratorService.depth(minutes) : DevTaskGeneratorService.depth(minutes);
        int band = minutes <= 30 ? 0 : minutes <= 50 ? 1 : 2;
        String[] dev = {
                "20 minutes = 1 추가 method, 1 judgment point, 1 hidden flaw in 기존 code, about 4–6 cases (hidden tests). Keep it small enough to read and solve quickly.",
                "40 minutes = 1–2 추가 methods, 1–2 judgment points, 1–2 hidden flaws in 기존 code, about 5–8 cases (hidden tests).",
                "60 minutes = 2–3 추가 methods, 2 judgment points, 2 hidden flaws in 기존 code, about 7–10 cases (hidden tests). The solver must follow several files; one textbook pattern must not solve it."};
        // 결함은 기존 코드에 숨어 있고 설명은 추가할 기능만 말한다(g10). 예전 "bug fix → feature" 단계는 설명이 결함을 가리키게 해서 뺐다(g12)
        String[] bizDepth = {
                "20 minutes = 1 CSV + 2 documents, 3 questions (2 multiple choice, 1 short answer), 1 judgment point, 1 trap.",
                "40 minutes = 1–2 CSVs + 2–3 documents, 4 questions (2 multiple choice, 2 short answer), 1–2 judgment points, 1–2 traps.",
                "60 minutes = 1–2 CSVs + 2–3 documents, 4–6 questions (2–3 multiple choice, 2–3 short answer), 2 judgment points, 2 traps."};
        return (biz ? bizDepth : dev)[band] + " File counts and sizes follow the E6·M6 section.";
    }

    // ── 설계도(1단계): 직군별 프롬프트(g6). 공용 조각(목적·사실·판단 지점·사례 공통·E6 공통·말투·체크리스트 1~6)을 양쪽이 끼워 쓴다 ──

    /** 공용: 과제의 목적과 출력(직군 말 없음) */
    private static final String BP_INTRO = """
            You design one {{track}} assessment task for pAIr, a hiring test where the candidate solves a task together with an AI assistant in {{minutes}} minutes.
            Output only the blueprint JSON (structure and the exact sentences listed below). Later stages write the prose without knowing the flaw.

            What makes a good task (criteria E1–E6, M1–M6):
            - The AI can do most of the work fast when instructed well (E1). The hidden flaw (E3) is where the AI's usual solution (idiomatic code, common sense) is wrong because a business rule recorded only in the materials says otherwise; the candidate gets it right only by checking the materials (E4). No generic bugs that an idiomatic rewrite or common sense fixes by itself ([Flaw types] lists them). No tricks, no knowledge outside the materials (Q3, Q5).
            - 1–2 judgment points (E2): a human must choose, and every option is defensible from a fact in the materials (M4).
            - Never give away the answer (M5): materials state facts and records, never causes, fix locations, rules, definitions that resolve the trap, calculation steps, or "~해야 한다".
            - Never put generator information in candidate text: file counts, character counts, attachment or context limits, "needed files", "fillers".

            Language:
            - Write every text value in Korean{{ascii}}.
            - facts, judgments.basis, judgments.options.fact, flaws.evidence and cases.basis{{verbatimExtra}} are copied verbatim into the candidate's materials, and code checks compare them sentence by sentence. Write them as finished Korean sentences.
            """;

    /** 공용: 사실·판단 지점·결함 칸·사례 근거·핵심어·제목 */
    private static final String BP_FACTS = """
            Fields:
            - scenario: 1–2 Korean sentences about the business situation. No flaw, no cause, no file names.
            - facts: records and observations, one sentence each, with place (where the sentence goes). A fact says what happened, a table value, what a quote/receipt/log/memo says, who said what. Mix flaw evidence, judgment basis and background in the same style. A sentence ending in "~이다/~였다" is still a fact if it records something (a threshold on a review sheet, that month's value).
              Not a fact (rule, definition, formula, method, cause, fix): "총비용은 공급가액에 봉사료와 부가가치세를 합한 금액이다", "일평균은 전환 수를 집행일 수로 나눈 값이다".
              Fact: "견적서 항목: 대관 8,000,000원, 봉사료 10퍼센트 별도", "청구서 합계란에 '부가세 포함 금액'이라고 적혀 있다", "4월 전환 400건, 집행 20일", "8월 2일 정산 기록: 12,345원 상품 1개 주문에 적립금 120원이 지급되었다".
            - flaws: hidden flaws, only from [Flaw types] below; what starts with the type code. what = what is wrong; where = which files/places to compare (name the file paths from neededFiles); evidence = one raw record line that reveals the flaw when compared (a facts sentence, verbatim); caughtBy = the case that catches it.
            - judgments: question = what to decide, neutral, no options or conclusion inside. basis = a facts sentence. options ≥ 2, each with text and fact (a different observation supporting that option, a facts sentence). Every option must be accepted; no fact may settle the choice.
            - cases: basis is either (1) a sentence that fixes this expected result directly, or (2) a fact from which the expected result can be derived (a record of another input under the same rule, a domain rule fact, an observed value). A sentence that is only related is not enough. For the input that triggers the flaw, never use a sentence stating that input's expected result (the flaw would leak); use (2). Every flaw has a case that catches it; flaws.caughtBy is that case's name.
            - trapKeywords: 2–5 core words of the flaw (in Korean as they would appear) that must not appear in candidate text.
            - title, summary (≤ 80 chars, does not reveal the flaw), code (lowercase letters, digits, hyphens), category, competencies (1–2 keys).
            """;

    private static final String BP_DEV = """
            DEV fields (Java 21, one package):
            - Flaw placement: the starter code follows the AI's usual default, and only a business-rule record (a NOTES.md settlement record on another input, a description observation) shows it is wrong. It must not be visible from one line of starter code or public tests. Do not put a requirement example or NOTES record right next to the flaw's input value (examples on the same input use other values).
            - Flaw file: the flaw lives in a class the api classes call (e.g. OrderCheckout.points() calls RewardPolicy.pointsFor). That class is never in api, examples or description facts, so the description and the opened file do not point at it (g6: "기존: X.method" in the api list named the flawed file). flaws.where names that class and its path.
            - api: public signatures shared by the text and the code, only for the classes the candidate calls. Exactly "기존: Class.method(Type name, …): ReturnType" or "추가: Class.method(Type name, …): ReturnType". No modifiers (public, static), no package, no body, no ";" (e.g. "추가: OrderDiscount.payable(): long"). 기존 = already implemented in the starter code; 추가 = the candidate writes it. The flaw lives in 기존 code.
              Pick parameter and return types whose example values are short to read (Q7): LocalDate ("2026-06-10") rather than OffsetDateTime or ISO strings when a date is enough, long or int amounts, short codes. Even when the time zone is the trap, the trap inputs live only in hidden tests, so examples need no offsets; put a shared condition like "모든 시각은 한국 시간(+09:00)" once in exampleNote.
            - examples: the candidate sees them as one table per method (parameter | result), built by code from method, args and expected; input is never shown. 1–3 examples per method, at most 6 in all. Do not repeat the same information through another method (e.g. countRefundable = 2 when refundableIndexes already shows two items). Each example has:
              input = the exact Java calls including setup calls (for hidden tests and case basis), e.g. "새 PassRegistry에 register(\\"POOL-A\\", LocalDate.of(2026, 6, 30)), register(\\"GYM-B\\", LocalDate.of(2026, 6, 1)) 뒤 availableCodes(LocalDate.of(2026, 6, 10))";
              method = the api method as "Class.method" (e.g. "PassRegistry.availableCodes");
              args = one ["parameter name as in the api", "display value"] pair per parameter, in signature order ([] for no parameters);
              expected = the display value of the result.
              Display values are exact but short, plain text without Java syntax: 2026-06-10 (not LocalDate.of(…)), 19500, true, POOL-A, ["POOL-A"]. Each value ≤ 30 characters, lists ≤ 3 items, short names. Never explain why a result comes out (M5).
              All examples of one method use the same prepared data (the same setup calls in input). Write that data once in exampleSetups as {"method": "Class.method", "text": one short Korean line, e.g. "등록된 이용권: POOL-A 2026-06-30 만료, GYM-B 2026-06-01 만료"}; omit methods that need no setup. exampleNote = one line shared by every value (e.g. "모든 시각은 한국 시간(+09:00)"), or "".
              Values are correct. Examples calling a 기존 method must already pass on the flawed starter code (an example that fails at once makes the flaw obvious); the flaw-triggering inputs belong to hidden tests. Examples calling a 추가 method cannot run on the starter code (it is a stub), but they too use only inputs where the flaw does not change the result: the expected value must be the same under the flawed 기존 code and under the business rule (g12 03: "payable(1000, 500) → 14500" depended on the discount order the flaw is about, so the requirements gave away the answer). Check each 추가 example against the flawed behaviour before writing it.
            - cases = hidden tests: name = the test method name (English), situation = input, expected = result, basis = verbatim facts or examples sentence (an example written as its exact "input → expected" is an example sentence that fixes the expected result directly; the candidate sees it as that example's table row). If the basis is an example, the situation uses that example's exact calls and values; a case with other inputs (other names, amounts, another method) takes a fact as basis instead. The answer stage implements only these cases, with these exact inputs. Behaviour the materials do not decide (trailing empty item, null, empty input, which side a boundary belongs to) either gets its own example/fact as basis or is left out.
            - Each 추가 method needs at least 1 case whose situation contains the call as written, e.g. "payable(\\"10.00\\")", with basis = the same example. Each example calling a 추가 method needs a case with the same input values. If the method returns boolean, give one true and one false case; for numbers, lists or strings, give a case whose result differs from a constant (0, empty list, "") so a constant-returning stub fails.
            - Every flaw case (caughtBy, separatedBy) calls at least one method that also appears in an example (the requirements), so the flaw is graded through behaviour the candidate is asked about; never grade only a 기존 method the requirements never mention (g9 01: only an unmentioned eligible() was graded).
            - Cases named in caughtBy or separatedBy call only 기존 methods (a 추가 stub always throws, so a flaw test calling it proves nothing).
            - AI default solution = the simplest idiomatic implementation that ignores the materials' rule, e.g. start.plusMonths(i), Math.round(total), a plain split(","). At least one caughtBy case uses an input where that default gives a wrong result and only the business rule gives the expected one; check the expected value against the default before writing the case (g8 02: NOTES said "month-end stays month-end" from 2024-11-30, but hidden tests used only 1/31 and 1/30 starts, so plain plusMonths passed all).
            - flaws.wrongAnswer + flaws.separatedBy: for each flaw, a common wrong fix an AI would make (e.g. round the result, count hours instead of dates); for at least one flaw, wrongAnswer is the AI default solution itself. Add another case that gives different results for that wrong fix and the right fix, and put its name in separatedBy (≠ caughtBy, calls only 기존 methods). Its input must make the wrong fix fail (values near integers do not separate). Its basis is the same input → expected in examples, or a fact that derives it.
            - Every behaviour written in examples or description (input → result, "거절돼도 처리 건수는 늘지 않는다", both sides of a boundary) needs a case; behaviour without a case is not checked by hidden tests, so a constant would pass.
            - A case basis must never be the same sentence as a judgment option fact, and no example may contain an option fact (the choice would then be fixed, M4).
            - scenario and description facts never mention reviewing, checking or comparing existing behaviour against records or ledgers, not even as a work-order item ("기존 조회 결과를 정산 내역과 대조하는 작업이 등록되었다" points at the flaw; g10). They state the situation and the feature to add only.
            - facts.place: "description" (situation, observations) or "notes" (operations records: settled cases, partner sheet lines, customer inquiries; becomes NOTES.md).
            - The business-rule evidence goes only in notes as a record of a correctly settled case on another input, mixed among other ordinary records. Never write the flaw's wrong output anywhere (no repro like "합계가 0.30000000000000004로 표시됨" or "-1,494,967,296원"): a wrong-output record tells the AI exactly what to fix without reading the rule (g6). Description facts state the situation and the job only; never the input condition that triggers the flaw (g7 01: "끝에 Z가 붙은 시각도 들어온다" in the description pointed at the flaw). Such conditions appear only inside notes records.
            """;

    private static final String BP_BIZ = """
            BIZ fields:
            - The task has two parts and the blueprint covers both: (a) a one-page plan (기획안.md) graded by a checklist — judgment points with a supporting fact per option, every option accepted; (b) questions — the trap, the answer key and recalculation. Judgment points are never asked as multiple-choice questions; they are decided in the plan.
            - The decisive records of the flaw go into documents (facts.place = a document path), mixed among other records; description facts state only the situation and the job.
            - Trap (E3): a company definition found only in the materials (what counts as 실적, the counting basis, the rounding unit, the order of deductions) differs from the AI's common-sense reading of the numbers. Never a trap that general knowledge settles (dividing a VAT-inclusive amount by 1.1, converting MWh to kWh): the AI gets those right without reading.
            - Record that definition once as a plain fact in a document, in record style, e.g. "3분기 실적 보고서의 채널 실적은 판매 수수료를 뺀 입금액으로 작성되었다." (not "~로 계산해야 한다"), mixed among other records, plus a past case whose numbers match only that definition. Without the definition sentence the candidate can only guess it from numbers (g7 01).
            - Flaw placement: the flaw shows only when two documents or a document and a CSV are compared. One CSV column or one document line must not reveal the right reading (e.g. a unit column that announces the trap, a definition line that states the right interpretation, a confirmed per-item result for an item like the asked one). Prefer metadata evidence (column label, extraction period, source of a past figure) that implies the rule only with numbers from another file (see [Flaw types]).
            - If a threshold has several conditions, add a fact that records how they combine, e.g. "두 조건을 모두 충족해야 통과한다" or "한 조건만 충족해도 통과한다". Without it the answer is not unique (M1).
            - cases = questions: name = Q number, situation = the case, expected = the answer, basis = a verbatim facts sentence. ask = the value to find, neutral, no method. choices = for multiple choice, exactly 4 candidates (one equal to expected, the rest trap or common-error values; numbers or short conclusions only, no explanations); for short answer, []. Judgment points are graded in the plan, never asked as questions. Fact or conclusion questions must have a unique answer from the materials.
            - A case's expected value must not appear in another case's choices or ask (solving one question would reveal another).
            - tables: CSV tables (path 자료/name.csv, columns, rows). Numeric cells are plain numbers without commas; no commas inside cells; every row has as many cells as columns. Numbers are decided only here and in figures.
            - figures: named numbers used in documents (label, value). A part names its whole in partOf, a total lists its parts in sumOf (part ≤ whole, sums exact). Labels are unique. An answer value may be in figures but never in facts.
            - Every value number in facts must already be a table cell or a figures value (only numbers followed by 월, 일, 년, 분기, 쪽, 페이지, 번째, 차, 시 are exempt; counts like "3명" are values). facts.place = the document path it goes into (or "description"). A term definition says only what is counted.
            - docs: document plan (path 자료/name.pdf, neutral role). Split flaw evidence and judgment basis across different documents so they show only when compared. Each document is written separately from only the facts placed in it, so every document needs its own facts.
            - Path names use only Korean or Latin letters, digits, space, _ ( ) -. No "·", "&", "/" inside the name. All table and doc paths are distinct.
            - flaws.caughtBy: the question whose answer changes if the flaw is missed ("Q2") or "결론". conclusion: correct conclusion and the conclusion a trapped candidate reaches (different).
            - questionPlan: "Q1 객관식 …" entries, ≥ 3. A short-answer question's value changes when trapped; at least one multiple-choice conclusion question. Each question needs two or more different materials.
            """;

    /** 공용 E6·M6 앞뒤(직군별 묶음 규칙은 그 사이에 끼운다) */
    private static final String E6_HEAD = """
            E6·M6 materials (this section overrides any other file count or size):
            - The candidate's AI accepts at most {{attach}} attachments per question and {{ctx}} attached characters in total. Attaching only the needed files must solve the task; attaching everything must exceed the limit.
            - The needed files together are {{min}}–{{cap}} characters (40–70% of the limit, aim for {{target}}). Too small means attaching the first files solves it, so plan needed files as full, realistic work files.
            """;
    private static final String E6_TAIL = """
            - All materials together reach {{allChars}}+ characters; a cheaper model writes the fillers from your list only and knows no facts, flaw or answer, so summaries never mention them.
            """;
    private static final String E6_DEV = """
            - neededFiles: exactly {{needed}} paths from: starter code src/…/X.java, public test test/…/XTest.java, NOTES.md. Public tests do not count toward the character range above (the candidate runs them without attaching); plan the size with src files and NOTES.md. The flawed class file belongs in neededFiles.
            - NOTES.md in neededFiles requires at least 1 fact with place "notes" (otherwise NOTES.md is never written and the task fails).
            - Evidence in 2+ needed files: flaws.where names the flawed class and its path (e.g. "RewardPolicy.pointsFor (src/order/RewardPolicy.java)"), and at least one case basis is a fact with place "notes" (counts as NOTES.md) or the flaw spans a second needed class. Code files carry no comments, so do not plan sentences inside code.
            - fillers: Java files not needed for the solution (src/…/X.java, path and one-line Korean summary): other domain classes of the same service, log formats, config readers, unrelated to this task's flaw and judgment. Paths must differ from neededFiles.
            - Count: neededFiles + fillers = {{totalLo}}–{{totalHi}} files (public tests and NOTES.md count when listed).
            - At least 1/3 of fillers have confusing=true: same domain and a similar class name, so they look worth attaching, but a DIFFERENT calculation or feature (e.g. next to RewardPolicy: PointExpiryPolicy computing when points expire). Never an old or alternative version of the flawed or api calculation: generated code for it ends up implementing the judgment point or the flaw rule (g8 08, g9 03 failed M6). A summary never describes behaviour that a judgment point, flaw case or example decides (duplicates, boundaries, rounding, apply order, parsing the same format). Other fillers have confusing=false and are ordinary co-located work files.
            """;
    private static final String E6_BIZ = """
            - neededFiles: exactly {{needed}} paths, each one of the docs or tables paths.
            - Evidence in 2+ needed files: name the needed file paths in flaws.where, and put the facts used as case basis, flaw evidence and judgment basis into at least 2 different needed documents (facts.place). If docs + tables have more files than neededFiles, the extra document gets background facts only.
            - fillers: documents (자료/name.pdf) or CSVs (자료/name.csv) not needed for the solution (path, one-line Korean summary): another team's minutes, last quarter's report, another campaign's schedule, unrelated to this task's facts, flaw and judgment. Paths must differ from docs and tables.
            - Count: docs + tables + fillers = {{totalLo}}–{{totalHi}} files.
            - File names (2026-11-27: the solo AI picks files by name and size, and picked the needed files whenever their names matched the task best): needed documents get plain administrative names that do not repeat the task's topic words, above all the document holding the flaw evidence. Name each one after what that document actually is in this scenario: its document type and the team, system or occasion it comes from (document types vary, e.g. 결재 문서, 담당자 메일 묶음, 시스템 변경 공지, 정산 체크리스트, 감사 회신, 업무 매뉴얼 발췌, 주간 업무 보고), so names differ from task to task (g20: copied example names gave "운영팀 인계 기록 / 조회 저장 기록 / 회람" in all 6 tasks, a pattern candidates can learn). Never use the names "운영팀 인계 기록", "조회 저장 기록", "회람", or a name that is only one generic word. Confusing fillers get names that carry the task's topic words (the metric, the month, the team, e.g. "6월 지역 매출 검산 정리.pdf"), so by name they look at least as relevant as the needed documents. Confusing fillers are written at about the size of one needed document.
            - At least 1/3 of fillers have confusing=true: same domain in both name AND content (an old version doing the same calculation, another feature applying a similar rule, a previous policy on the same subject) so they look worth attaching, but they hold no answer, flaw evidence or judgment basis. Sharing only a name prefix with unrelated content does not count (g5 07). Write the summary so the content overlap is clear. Other fillers have confusing=false and are ordinary co-located work files.
            """;

    /** E6·M6 설계도 규칙(기준 문서 6장). 개수·경로 조건은 Dev/BizBlueprintService.materialProblems 와 같다 */
    static String e6Blueprint(boolean biz, int minutes) {
        var limits = AiLimitsDto.forMinutes(minutes);
        int[] r = BlueprintService.materialRange(biz, minutes);
        int cap = (int) (limits.contextChars() * MaterialGateService.NEEDED_MAX_RATIO);
        int min = (int) Math.ceil(limits.contextChars() * MaterialGateService.NEEDED_MIN_RATIO);
        int target = (int) (limits.contextChars() * MaterialGateService.NEEDED_TARGET_RATIO);
        int allMin = (int) (limits.contextChars() * MaterialGateService.TOTAL_MIN_RATIO);
        String needed = r[0] == r[1] ? String.valueOf(r[0]) : r[0] + "–" + r[1];
        int totalLo = biz ? r[2] : r[2] - 2;
        return (E6_HEAD + (biz ? E6_BIZ : E6_DEV) + E6_TAIL).replace("{{attach}}", String.valueOf(limits.maxAttach())).replace("{{ctx}}", String.valueOf(limits.contextChars()))
                .replace("{{needed}}", needed).replace("{{cap}}", String.valueOf(cap)).replace("{{min}}", String.valueOf(min))
                .replace("{{target}}", String.valueOf(target)).replace("{{allChars}}", String.valueOf(allMin))
                .replace("{{totalLo}}", String.valueOf(totalLo)).replace("{{totalHi}}", String.valueOf(r[3]));
    }

    /** 공용 체크리스트 1~6(BlueprintService.commonProblems 와 1:1) */
    private static final String CHECK_COMMON = """
            Checklist — code rejects the blueprint if any item fails. Check each before you answer:
            1. title and scenario are set; code matches [a-z0-9]+(-[a-z0-9]+)*.
            2. facts ≥ 3. Each fact is ONE sentence: no line break, no " / ", and no ". " followed by more text. The same applies to judgments.basis, options.fact, flaws.evidence and cases.basis (they are added to facts). Two records = two facts.
            3. flaws ≥ 1; each has what, where, caughtBy, and caughtBy is a case name.
            4. judgments ≥ 1; each has question, basis and ≥ 2 options with text and fact.
            5. trapKeywords: 2–5 items.
            6. cases ≥ number of flaws; each has name, situation, expected, basis.
            """;
    /** DEV 체크리스트(DevBlueprintService.problems·materialProblems 와 1:1) */
    private static final String CHECK_DEV = """
            7. api is not empty; every item is "기존: …" or "추가: …" with no "{", "}" or ";".
            8. examples ≥ 2, each with input, method, args (one [name, value] pair per parameter, [] if none) and expected. No example (기존 or 추가 method) has an expected value that the flaw changes. (Also checked as warnings: method is an api method, display values ≤ 30 characters, lists ≤ 3 items, ≤ 3 examples per method, ≤ 6 in all.)
            9. Every 추가 method is called as "name(" in some case situation; every example calling it has a case containing the same quoted values and numbers. A case whose basis quotes an example uses that example's calls, quoted values and numbers in its situation.
            10. Cases in caughtBy/separatedBy do not call 추가 methods. Every flaw with wrongAnswer has separatedBy = a different case name.
            11. No case basis equals or contains a judgment option fact; no example contains one.
            12. The flawed class named in flaws.where appears in no api item, no example and no description fact; every flaw case calls a method that appears in some example; scenario and description facts never ask to compare or review existing behaviour.
            """;
    private static final String CHECK_DEV_E6 = """
            13. neededFiles count is exact; paths are src/…java, test/…java or NOTES.md; NOTES.md listed ⇒ a fact with place "notes" exists.
            14. fillers: ≥ 1, each with summary, path src/…java without "..", not in neededFiles; file total within the range above; ≥ 1/3 confusing.
            """;
    /** BIZ 체크리스트(BizBlueprintService.problems·materialProblems 와 1:1) */
    private static final String CHECK_BIZ = """
            7. docs ≥ 2 with paths 자료/name.pdf; tables ≥ 1 with paths 자료/name.csv; allowed name characters only; all paths distinct.
            8. Every table has columns and rows, every row with as many cells as columns.
            9. conclusion.correct and conclusion.wrong are set and different; every flaw has evidence; questionPlan ≥ 3.
            10. Every case has ask. choices is [] or exactly 4 items with one equal to expected.
            11. figures: numeric values, unique labels, partOf/sumOf labels exist, part ≤ whole, sums exact.
            12. Every value number in facts is a table cell or figures value (only dates, pages and ordinals exempt).
            13. No case's expected value appears in another case's choices or ask.
            """;
    private static final String CHECK_BIZ_E6 = """
            14. neededFiles count is exact and each path is a docs or tables path.
            15. fillers: ≥ 1, each with summary, path 자료/name.pdf or 자료/name.csv, not a docs/tables path; file total within the range above; ≥ 1/3 confusing.
            """;

    /** 직군별 체크리스트(설계도 형식 검사를 그대로 옮긴 것) */
    static String blueprintChecklist(boolean biz, boolean e6) {
        return CHECK_COMMON + (biz ? CHECK_BIZ + (e6 ? CHECK_BIZ_E6 : "") : CHECK_DEV + (e6 ? CHECK_DEV_E6 : ""));
    }

    /** 1단계 DEV 설계도 프롬프트: DEV 규칙(공개 API·예·추가 메서드 틀·코드 파일·NOTES)만 */
    static String devBlueprint(int minutes, String depth, boolean e6) {
        String body = BP_INTRO.replace("{{track}}", "development (DEV, Java)").replace("{{ascii}}", ". Only these stay ASCII: code, api signatures, case names (Java test method names), Java file paths")
                .replace("{{verbatimExtra}}", "") + "\n" + BP_FACTS + "\n" + BP_DEV + "\nTask size: {{depth}}\n"
                + (e6 ? "\n" + e6Blueprint(false, minutes) : "") + "\n" + KOREAN_STYLE
                + "\n[Flaw types]\n" + resource("taskgen/flaw-types-dev.md")
                + "\nGood example (shortened; counts fit a 20-minute task):\n" + DEV_EXAMPLE + "\n\n" + blueprintChecklist(false, e6);
        return body.replace("{{minutes}}", String.valueOf(minutes)).replace("{{depth}}", depth) + TaskGenRequest.COMPETENCY_PROMPT;
    }

    /** 1단계 BIZ 설계도 프롬프트: BIZ 규칙(숫자표·CSV·문서·문항)만 */
    static String bizBlueprint(int minutes, String depth, boolean e6) {
        String body = BP_INTRO.replace("{{track}}", "non-development (BIZ: documents, CSVs and a plan)").replace("{{ascii}}", ", except code (lowercase ASCII)")
                .replace("{{verbatimExtra}}", "") + "\n" + BP_FACTS + "\n" + BP_BIZ + "\nTask size: {{depth}}\n"
                + (e6 ? "\n" + e6Blueprint(true, minutes) : "") + "\n" + KOREAN_STYLE
                + "\n[Flaw types]\n" + resource("taskgen/flaw-types-biz.md")
                + "\nGood example (shortened; counts fit a 40-minute task):\n" + BIZ_EXAMPLE + "\n\n" + blueprintChecklist(true, e6);
        return body.replace("{{minutes}}", String.valueOf(minutes)).replace("{{depth}}", depth) + TaskGenRequest.COMPETENCY_PROMPT;
    }

    /** 직군에 맞는 설계도 프롬프트 */
    static String blueprint(boolean biz, int minutes, String depth, boolean e6) {
        return biz ? bizBlueprint(minutes, depth, e6) : devBlueprint(minutes, depth, e6);
    }

    /** 1단계 설계도 프롬프트(E6 끔, 테스트·옛 호출용) */
    static String blueprint(boolean biz, int minutes, String depth) {
        return blueprint(biz, minutes, depth, false);
    }

    /** 2단계(DEV) 작성 프롬프트: 결함을 모르는 단계 */
    static final String WRITING_DEV = """
            You write the task text a candidate reads. You receive a facts list and write description, requirements, notes and taskFollowUp, all in Korean. Input → output examples are added by code after you write; never write any.
            Rules:
            - Insert every fact sentence verbatim, values and wording unchanged. place=description facts go into description, place=notes facts into notes. You may add connecting words between sentences.
            - Outside the facts write only background: situation, people, workflow. No new rules, conditions, definitions, handling or purpose statements (e.g. "구분자는 문자 그대로 취급한다", "이 API는 ~에 쓰인다"). No new facts, numbers, causes or reasons; do not point at which fact matters.
            - Public API: "기존:" methods already exist; never ask to add or implement them. Ask to build only "추가:" methods. Never say where anything is wrong, and name no class outside the api.
            - Never write generator information: file counts, character counts, attachment or context limits, which files are needed.
            - description: situation and the job (add the feature) in 3–6 sentences of business prose, plus every place=description fact. Never name or point to specific files or classes to read (no "NOTES.md를 확인하세요", no "X.java의 Y 메서드"); finding them is part of the task. Ask only to build the "추가:" methods and decide the open points; never ask to review, check or compare existing methods or their results (no "기존 X.f의 결과를 기록과 대조하세요", no "기존 코드를 검토하세요"). Not even as background or a work-order item: no "기존 지급액 계산의 정산 기록 대조", "기존 조회 결과 점검", "지급 내역을 대조하는 작업" (g9: these sentences point at the flaw and are rejected by code). The description says only the situation and the features to add. For things the candidate must decide, write only "~를 정하고 선택한 이유를 설명하세요"; no options, no hint which is right.
            - requirements: only the features to build (2026-11-27: candidates could not tell what the numbers in the examples mean). Use exactly these line shapes so the screen can lay them out:
              (1) for each "추가:" api method, one line "만들 기능: `<the signature exactly as in the api list, without 추가:>` — <one Korean sentence saying what it returns, e.g. '결제할 금액을 돌려준다'>";
              (2) right below it, one line per parameter "- <parameter name as in the signature>: <what the value is, a short Korean noun phrase, e.g. '이번 달 이용 금액'>".
              The return sentence and parameter phrases say only WHAT a value is, never HOW it is computed or handled: no conditions, boundaries, order of applying, rounding, units conversion, exceptions or edge cases (those would be rule sentences and give away the task). Never describe 기존 methods here. No other lines. Code adds the example table rows below each feature, so write no examples, no "→" lines and no method calls with values anywhere (requirements or description), and no sentence twice.
            - notes: place=notes facts as an operations note (the given records, in date order, with plain connecting words). Empty string if there are none. Never add a record of a wrong output or a failing input. Keep it compact: one title line, then each record on its own line with at most a short connecting clause; no background paragraphs, summaries or extra records (a long note pushes the needed files over the size limit).
            - taskFollowUp: one follow-up question built from "the thing to decide" and a generic frame (why you chose it, what if conditions change, would you ship it as is), phrased for this task. No fact values, records, file fields or columns, and no hint where to look.
            """ + KOREAN_STYLE;

    /** 2단계(BIZ) 작성 ① 요청 글: 설명·요구사항·후속 질문만(문서는 문서별 호출이 쓴다, g6) */
    static final String WRITING_BIZ_BRIEF = """
            You write the task request a candidate reads, in Korean. You receive the situation, the decisions the candidate must make, the material names and the facts whose place is description. Write description, requirements and taskFollowUp; leave documents as [].
            Rules:
            - Insert every given fact sentence verbatim into description, values and wording unchanged.
            - Outside the facts write only background: situation, people, workflow. No new rules, definitions, calculation methods, facts, numbers, causes or reasons; do not point at which fact matters or which option is right.
            - Never write generator information: file counts, character counts, attachment or context limits, which materials are needed.
            - description: situation, the candidate's role and the job (a one-page plan in 기획안.md, the first task to do and why, risks and responses, answers to the questions) in 3–5 sentences. Never say which material holds what or which files to read, and never name a material by its title or a near paraphrase (g8 07: "시설별 전력 사용량 자료와 계량기 안내서를 대조하고"); finding them is part of the task. For things to decide write only "~를 정하고 근거를 설명하세요"; no options or conclusions. No real company names.
            - requirements: 2–4 short items the plan must contain (conclusion with 3+ reasons that cite figures from the materials with the material name, the choice and its reason, risks and responses). Materials have no numbers, so never ask to cite a material number. No answer numbers.
            - taskFollowUp: one follow-up question built from "the thing to decide" and a generic frame (why you chose it, what if conditions change, would you ship it as is), phrased for this task. No fact values or material fields/columns, and no hint where to look.
            """ + KOREAN_STYLE;

    /** 2단계(BIZ) 작성 ② 문서 하나: 그 문서 자리의 사실만 받아 그 문서의 쪽들만 쓴다(문서마다 동시에, g6). docCount 는 문서 계획의 문서 수 */
    static String writingBizDoc(int minutes, boolean e6, int docCount) {
        int n = Math.max(1, docCount);
        String size;
        if (e6) {
            int ctx = AiLimitsDto.forMinutes(minutes).contextChars();
            size = "This document is " + (int) (ctx * 0.45 / n) + "–" + (int) (ctx * 0.6 / n) + " characters (the candidate must attach it; too short fails), 1–3 pages.";
        } else {
            size = "This document is about " + (minutes == 20 ? 2750 / n : 6500 / n) + " characters.";
        }
        return """
                You write ONE business document a candidate reads, in Korean. You receive the situation, this document's path and role, the names of the other materials, the CSV tables and the facts placed in this document. Write documents with exactly one entry for this path; leave description and requirements as [] and taskFollowUp as "".
                Rules:
                - Insert every given fact sentence verbatim, values and wording unchanged. Each fact appears exactly once. Never leave a fact as a lone line; mix it into a paragraph with other content.
                - Outside the facts write only background: situation, people, workflow. No new rules, conditions, definitions or calculation methods. No new facts or numbers (only the given fact sentences and CSV values), no causes or reasons; do not point at which fact matters or which option is right. Do not invent the content of the other materials.
                - No "~해야 한다", no remark that two things differ, no explanation of causes.
                - No requests addressed to the reader ("~해 주세요", "~해 달라", "회신 바랍니다"), no sentences that only close gaps ("그 밖의 조건은 없다", "~는 기록되어 있지 않다"), and never name other materials or files ("함께 참조하는 자료는 …").
                - Never write generator information: file counts, character counts, page or size targets, attachment or context limits.
                - Pages: each ≤ 900 characters and ≤ 30 lines, first line a short title. No markdown symbols, emoji or special symbols. Background text without numbers. {{size}}
                - Write it as a real company record (meeting notes, settlement record, execution confirmation, report). Never mention the task, candidate, AI assistant, plan template, or what a page covers (e.g. "이 페이지는 ~를 정리한다", "~결론을 덧붙이지 않는다"). Write what people did and the values they saw, like a record.
                  Every sentence carries a fact, number, judgment or request. Do not write sentences that only narrate actions (서류를 펼쳤다, 정돈했다, 묶어 두었다, 메모에 옮겼다) or only state a status (검토 중이다, 의견을 요청한다, 기록되어 있지 않다); three or more of these force a rewrite.
                  나쁜 예: "담당자는 자료를 검토 중이다." "팀은 의견을 요청한다." "이 항목은 기록되어 있지 않다."
                  좋은 예: "4월 전환 400건, 집행 20일이었다.", "운영팀은 새봄과 여름의 전환율을 각각 1퍼센트 증가로 기록했다."(given fact), "6월 회의에서 배정 후보 두 곳을 다음 분기 안건으로 올렸다."(what happened)
                """.replace("{{size}}", size) + KOREAN_STYLE;
    }

    /** 2'단계(DEV) 시작 코드 프롬프트(E6·M6 켜짐): 필요한 묶음의 src·test 경로로만 만들고 관련 적은 자료·NOTES.md 는 만들지 않는다 */
    static String code(String depth, boolean e6, int minutes) {
        return code(depth, e6, minutes, ASSUMED_NOTES_CHARS);
    }

    /** NOTES.md 를 모를 때(테스트·옛 호출) 잡는 길이. 실제 생성은 작성 단계가 쓴 notes 길이를 넘긴다 */
    static final int ASSUMED_NOTES_CHARS = 1000;
    /** 필요한 묶음 상한과 src 합계 사이 여유(글자) */
    private static final int BUNDLE_MARGIN = 300;
    /** src 합계의 가장 작은 범위 끝(NOTES 가 커도 코드가 너무 작아지지 않게) */
    private static final int MIN_SRC_CHARS = 1500;

    /**
     * src 파일 합계의 글자 수 범위. 필요한 묶음(src + NOTES.md)이 맥락 한도의 40~70% 안에 들도록, 작성 단계가 이미 쓴 NOTES.md 길이를 뺀다
     * (2026-11-27 g14: NOTES 를 1,000자로 가정해 src 를 잡았는데 실제 NOTES 가 2,400~3,500자라 묶음이 70%를 넘음, g13 01 도 같음)
     */
    static int[] srcRange(int minutes, int notesChars) {
        int ctx = AiLimitsDto.forMinutes(minutes).contextChars();
        int cap = (int) (ctx * MaterialGateService.NEEDED_MAX_RATIO), lo = (int) Math.ceil(ctx * MaterialGateService.NEEDED_MIN_RATIO);
        int from = Math.max(MIN_SRC_CHARS, lo - notesChars);
        int to = Math.max(from, cap - notesChars - BUNDLE_MARGIN);
        return new int[] {from, to};
    }

    static String code(String depth, boolean e6, int minutes, int notesChars) {
        if (!e6) return code(depth);
        int[] r = srcRange(minutes, notesChars);
        return code(depth) + "- E6·M6: create exactly the src/ and test/ paths in the blueprint's neededFiles (create the public test test/…java even if it is not listed). "
                + "Do not create NOTES.md (another stage writes it) or any other file; the fillers paths are written by another stage. "
                + "The src files together (public tests not counted) are " + r[0] + "–" + r[1] + " characters, counting every character of the files; stay inside this range (over the upper end the task is rejected). "
                + "Write realistic business code (fields, validation, formatting, helper methods) unrelated to the flaw so the files are not tiny. This overrides any file-count statement.\n";
    }

    /** 2'단계(DEV) 시작 코드 프롬프트. depth 는 과제 규모 */
    static String code(String depth) {
        return """
                You write the starter code and public tests of a development task, implementing the blueprint's api.
                Rules:
                - Pure Java 21 + JUnit 5 only. No external libraries, files, network or Spring. Paths: src/<package>/X.java, test/<package>/XTest.java.
                - Every "추가:" method of the api must exist in the starter code as a stub: the blueprint signature exactly (name, parameter types, return type) with the body throw new UnsupportedOperationException("TODO"); and nothing else. Without it the hidden tests do not compile. Do not implement it. Public tests use only "기존:" methods. Implement every "기존:" method.
                - The starter code compiles and passes the public tests. The flaw at flaws.where is present as an ordinary implementation of the AI's usual default, inside the class named there; the api classes call that class. Keep that class out of the api classes' names. Public tests check the examples of "기존:" methods; the starter code already returns the right value for every 기존 example, and the flaw shows only on inputs outside the examples.
                - No comments (no Javadoc). No names that hint at the flaw or the fix. Write it like real business code.
                - String literals and identifiers in English or Korean only; never Chinese characters (漢字) or Japanese.
                - Do not write hidden tests, the solution or alternative solutions (the next stage does).
                - Size: {{depth}}
                """.replace("{{depth}}", depth);
    }

    /** 단계 4' 프롬프트: 결함을 모르는 싼 모델이 관련 적은 자료를 쓴다(E6·M6) */
    static String filler(boolean biz) {
        return biz ? """
                You write ordinary company work files. You receive the situation and a list of files to create (path and one-line content) and write each as a file that would really sit next to the others, in Korean.
                Rules:
                - files: one entry per listed path, path identical to the list. For .pdf, content is text (first line a short title, paragraphs separated by blank lines). For .csv, content is a comma-separated table (first line header, no commas inside cells, plain numbers without commas). Page splitting is done by code.
                - Write only ordinary work content matching the one-line content: minutes, execution records, staff notes, past tables. Numbers, dates and names may be invented but must agree with each other. You do not know this task's answer, conclusion, trap or judgment, so do not try to write them. No sentence that concludes or says what is right, no calculation method, no rule explanation.
                - Never invent or quote the content of other files (the names you are given). Never mention the task, candidate, AI or the purpose of the file. No markdown symbols or emoji. No real company names.
                - Reach the per-file character count in the input (short files are regenerated).
                """ + KOREAN_STYLE : """
                You are a service developer. You receive the situation and a list of Java files to create (path and one-line content) and write each as code that would really sit in the same service.
                Rules:
                - files: one entry per listed path, path identical to the list, content = the whole file. The package declaration matches the folders under src/.
                - Pure Java 21 with JDK classes only. Never call, extend or import classes from the other files (the names you are given). Each file compiles alone, and class names do not clash with other files. The public class name equals the file name.
                - No comments (no Javadoc). Ordinary business code matching the one-line content (fields, constructors, methods, validation, formatting). Implement only what the one-line content says; no extra handling of duplicates, boundaries, rounding or special input formats. No main method, no tests. You do not know this task's bug or solution, so do not try to write them.
                - String literals and identifiers in English or Korean only; never Chinese characters (漢字) or Japanese.
                - Reach the per-file character count in the input (short files are regenerated) with enough fields and methods.
                """;
    }

    /** 4단계(DEV) 정답 쪽 프롬프트 */
    static String answerDev(int minutes) {
        return """
                You write the answer side of a development task. You receive the blueprint plus the text and starter code the candidate already sees.
                Rules:
                - hiddenTests: JUnit 5 hidden tests (test/…java) implementing only the blueprint cases. Hidden tests call the api's "추가:" methods directly and assert on them. One @Test method per case (method name = case.name), asserting only case.expected. Do not assert behaviour outside the cases (empty input, null fallback values and other behaviour the materials do not support). Each test uses its case's situation input values exactly (same values, same calls, same order); never change, add or reorder inputs — the blueprint already chose inputs that differ from the examples. Each must fail on the starter code where the case targets the flaw and pass on a correct solution. A solution that hardcodes the cases must fail. Assert only results that hold whichever judgment option is chosen.
                - Concurrency or time tests finish within 10 seconds with assertTimeoutPreemptively; a waiting helper that times out must not fail the test, and no test assumes a specific implementation structure.
                - solution: full content of each changed src file. Do not change test files. Changed lines vs the starter code (added + removed) ≥ {{lines}}.
                - altSolutions: 1–2 correct solutions structured differently from the solution (note = one line, files = full changed src files; paths start with src/ only, never NOTES.md or other files). They pass all public and hidden tests.
                - wrongFixes: 2–3 plausible fixes that miss the flaw (reason = one line, files). For every flaws.wrongAnswer make one wrong fix of that kind, and make sure the flaws.separatedBy case's test fails it (a wrong fix must not pass all hidden tests). One wrong fix is always the AI's default solution: the simplest idiomatic rewrite that ignores the business rule in NOTES.md/description (e.g. plain start.plusMonths(i), one Math.round on the total); the caughtBy test must fail it. They compile and pass the public tests but fail ≥ 1 hidden test. A different solution with the same behaviour as the solution is not a wrong fix.
                - answer: Korean markdown explaining the flaw and which judgment options are accepted. Do not introduce requirements or behaviour missing from the visible description·requirements (e.g. rejecting visits before activation); every behaviour you describe is in the visible text.
                """.replace("{{lines}}", minutes == 20 ? "2" : minutes == 40 ? "6" : "12") + KOREAN_STYLE;
    }

    /** 3단계(BIZ) 문항 글 프롬프트: 결함을 모르는 단계 */
    static final String QUESTIONS_BIZ = """
            You write the question text a candidate reads, in Korean. You receive the situation, the visible text and the question list (value to find and choice candidates).
            Rules:
            - questions: one per id in the list, in order. content asks only for the value, in 1–2 sentences. No calculation method, order, cause, or which material to use how; never name a material by its title or a near paraphrase.
            - Short-answer wording states the unit and rounding (e.g. "소수 첫째 자리까지").
            - Multiple-choice choices are the given candidates with values unchanged: numbers or short conclusions only, no explanation sentences (a unit may be added). Any order.
            - Do not put another question's value into choices or wording. Do not hint which choice is right.
            """ + KOREAN_STYLE;

    /** 4단계(BIZ) 정답 쪽 프롬프트. depth 는 과제 규모 */
    static String answerBiz(String depth) {
        return """
                You write the answer side of a non-development task. You receive the blueprint, the visible text, documents and CSVs, and the question text. You write only the answer keys, never candidate-visible text.
                Rules:
                - keys: one per question (id = case name, e.g. Q2). Same number of questions as the blueprint cases. {{depth}}
                - Short answer: accepted = the blueprint case's expected, checked against the material numbers (first item = the number only, in the unit of the question wording, no commas); calc = a check formula from the materials. For multiple-choice and judgment questions leave accepted and calc empty.
                - calc: v("자료/file.csv", "column=value;column=value", "column name") is the cell of exactly one matching row. Use only the ASCII operators + - * /, parentheses and round(x, digits) (never −, ×, ÷).
                - sources: per question ≥ 2 sources (different documents or CSVs, path and page number; for a CSV, pages is ""). The answer needs the sources together.
                - commonWrongAnswers: for each short answer, 1–2 trapped values (value = number only, reason). They differ from the answer by more than 0.5%. Multiple choice: [].
                - checklist: Korean markdown with '## 필수 항목' (numbered: conclusion, 3 reasons citing material numbers, the trap-related calculation, the judgment choice and its reason, risks and responses), '## 참고 계산', '## 흔한 함정'.
                """.replace("{{depth}}", depth) + KOREAN_STYLE;
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
