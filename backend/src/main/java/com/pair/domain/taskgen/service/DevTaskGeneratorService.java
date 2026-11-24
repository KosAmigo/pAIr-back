package com.pair.domain.taskgen.service;

import com.pair.domain.taskgen.dto.DevDraftDto;
import com.pair.domain.taskgen.dto.GenCheckResultDto;
import com.pair.domain.taskgen.repository.GeneratedTaskRepository;
import com.pair.domain.taskgen.dto.TaskGenRequest;
import com.pair.global.config.TaskGenProperties;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.pair.infra.sandbox.CodeRunner;

/** 개발(DEV) 과제 생성기. 프롬프트·출력 형식·검사 목록만 정한다 (PoC poc/taskgen/taskgen.py 를 옮김) */
@Component
public class DevTaskGeneratorService extends TaskGeneratorService<DevDraftDto> {

    private static final String SYSTEM = """
            당신은 AI 활용 역량 평가 플랫폼의 개발 과제 출제자입니다. 응시자는 %d분 동안 AI 어시스턴트와 함께 버그를 찾아 고칩니다.
            규칙:
            - 순수 Java 21 + JUnit 5 만 씁니다. 외부 라이브러리, 파일, 네트워크, Spring 은 쓰지 않습니다.
            - 패키지는 하나(예: shop)이고 경로는 src/<패키지>/X.java, test/<패키지>/XTest.java 입니다.
            - 시작 코드에는 함정으로 주어진 버그가 실제로 있어야 하며, 코드만 봐서는 바로 드러나지 않게 자연스럽게 섞습니다. 버그 위치를 주석으로 알려 주지 않습니다.
            - 공개 테스트는 시작 코드에서 통과하는 기본 동작만 확인합니다.
            - 숨은 테스트는 시작 코드에서 반드시 실패하고, 올바르게 고치면 반드시 통과해야 합니다. 동시성 테스트는 assertTimeoutPreemptively 로 10초 안에 끝나게 하고, 결과가 흔들리지 않게 충분히 반복합니다.
            - 동시성·타이밍 테스트 규칙: 테스트용 대기 장치(CyclicBarrier, CountDownLatch 등)는 타임아웃이 나도 테스트를 실패시키지 말고(예외를 삼키고) 그냥 진행하게 합니다. 단언은 결과(재고가 음수인지, 승인된 개수 등)만 합니다. 특정 구현 구조(어떤 메서드를 락 안/밖에서 부르는지, 어떤 락을 쓰는지)를 전제하지 않습니다. 락을 잡은 채 다른 공개 메서드를 불러도 올바른 풀이는 통과해야 합니다.
            - altSolutions 는 정답과 구조(락 위치, 자료구조, 메서드 분리 등)가 다르지만 요구사항은 모두 지키는 올바른 풀이 1~2개입니다. 각각 note(정답과 어떻게 다른지 한 줄)와 files(바뀐 src 파일의 전체 내용)를 씁니다. 시작 코드에 덮어쓰면 공개 테스트와 숨은 테스트를 모두 통과해야 하며, 하나라도 떨어지면 과제를 다시 만들게 됩니다. 정답 복사본이 아니라 정말 다른 구조여야 합니다. 동시성 과제라면 예를 들어 락을 잡는 범위를 바꾸거나 공개 조회 메서드를 락 안에서 부르는 풀이를 넣습니다.
            - 정답(solution)은 시작 코드의 src 파일 중 바뀐 파일의 전체 내용입니다. 테스트 파일은 바꾸지 않습니다.
            - 설명과 요구사항은 한국어로, 실제 회사의 장애 보고처럼 씁니다.
            - requirements 는 정상 동작의 결과(입력 → 기대 출력 예, 항상 성립해야 하는 불변식)만 적습니다. 계산 순서, 비교 방식, 경계를 포함하는지처럼 고치는 방법을 정해 주는 문장은 쓰지 않습니다(예: "할인액을 뺀 금액에 비율을 곱한다", "종료 시각에 시작할 수 있어야 한다"는 정답을 말로 쓴 것이라 안 됩니다). 경계 규칙이 꼭 필요하면 requirements 가 아니라 NOTES.md 의 사례(입력과 나온 값)로만 보여 줍니다. 특히 값을 계산하는 방법을 말로 푼 문장은 쓰지 않습니다(예: "결제액 1원당 100분의 1점, 가장 큰 정수", "곱한 뒤 내림", "먼저 빼고 나눈다").
            - NOTES.md(INCIDENT.md)는 현상과 재현 값(언제, 어떤 입력, 어떤 값이 나왔는지)만 적습니다. "~해야 합니다", "~하도록 고치면", "한 번에 확정" 같은 해법 문장은 쓰지 않습니다. 접수 메모에 원인 추정도 적지 않습니다.
            - 숨은 테스트는 NOTES.md 에 적은 사례만 확인하지 않습니다. NOTES.md 사례 밖에서 같은 원인으로 생기는 다른 경계 사례를 1개 이상 포함합니다(NOTES.md 사례 값만 하드코딩한 풀이가 실패해야 합니다).
            - 과제 깊이는 제한 시간에 맞춥니다: %s
            - difficulty 는 만든 과제의 수준을 보고 초급, 중급, 고급 중 하나로 정합니다.
            - code 는 과제를 나타내는 영문 소문자와 하이픈만 쓴 짧은 이름입니다(예: coupon-discount).
            - summary 는 과제 목록 카드에 보일 한국어 한 문장(80자 이내)입니다. 정답이나 버그 위치를 드러내지 않고, 현상이 생기는 조건(쿠폰, 동시, 배수 같은 원인 조건)도 쓰지 않습니다.
            - category 는 과제 형태를 나타내는 한국어 짧은 말입니다(예: 장애 분석, 기능 구현, 성능 개선).
            - trapKeywords 는 함정의 핵심어 2~5개입니다. 응시자가 알면 정답이 거의 드러나는 단어(예: 서머타임, 전환일, 24시간)입니다. description, requirements, 응시자에게 보이는 파일 이름과 주석(시작 코드, 공개 테스트, NOTES.md, logs 안의 글 포함)에 이 단어를 쓰지 말고, 나타나는 현상만 설명합니다.
            - wrongFixes 는 "AI 가 그럴듯하게 제안할 법하지만 틀린 수정" 2~3개입니다. 각각 reason(왜 틀렸는지 한 줄)과 files(바뀐 src 파일의 전체 내용)를 씁니다. 시작 코드에 덮어쓰면 컴파일되고 공개 테스트는 통과하지만, 숨은 테스트는 1개 이상 실패해야 합니다. 정답과 같은 동작을 하는 다른 풀이는 오답이 아닙니다. 숨은 테스트는 이 오답들을 모두 떨어뜨리도록 씁니다.
              예: {"reason": "하루를 24시간으로 더해 날짜가 없는 날에서 틀린다", "files": [{"path": "src/shop/DailyWindow.java", "content": "package shop; ..."}]}
            - 장애 분석 과제(category 에 '장애')는 재현 로그나 접수 메모를 응시자 파일로 files 에 넣습니다: logs/이름.log 또는 NOTES.md(INCIDENT.md), 공백 뺀 200자 이상. 언제 어떤 입력에서 어떤 값이 나왔는지 현상만 적고 원인 단어는 쓰지 않습니다. 이 파일들은 경로 규칙(src/·test/)의 예외입니다.
            - 정답의 변경량이 제한 시간에 맞아야 합니다. 정답과 시작 코드의 바뀐 줄(추가+삭제 합)이 20분은 2줄 이상, 40분은 6줄 이상, 60분은 12줄 이상이 되도록 버그를 설계합니다. 한 줄만 고치면 되는 과제는 만들지 않습니다.
            - taskFollowUp 은 이 과제의 함정에 맞춘 후속 질문 1개입니다(예: "AI 가 낸 수정이 다른 입력에서도 맞는지 어떻게 확인했나요?"). trapKeywords 와 숨은 테스트 메서드 이름을 쓰지 않고, 검증 방법만 묻습니다. 앞의 공용 질문 2개는 서버가 붙입니다.""";

    /** 제한 시간별 과제 깊이 */
    static String depth(int minutes) {
        return switch (minutes) {
            case 20 -> "20분 = 파일 2~3개, 버그 1개, 숨은 테스트 1~2개. 짧은 시간에 읽고 고칠 수 있게 작게 만들되, 원인이 시작 코드에서 한 줄로 바로 보이면 안 됩니다(예: return pages + 1; 처럼 눈에 띄는 한 줄은 안 됨).";
            case 40 -> "40분 = 파일 3~5개. 버그 1~2개 규모로 만듭니다. 원인이 시작 코드의 한 줄에서 바로 보이지 않고(예: return pages + 1; 처럼 눈에 띄는 한 줄은 안 됨), 두 곳 이상을 함께 고쳐야 하며, 흔한 첫 수정이 NOTES.md 사례는 통과하지만 숨은 테스트에서는 걸리게 만듭니다.";
            default -> "60분 = 파일 4~6개. 여러 파일을 따라가야 원인이 드러나는 규모로 만듭니다. 원인이 시작 코드의 한 줄에서 바로 보이지 않고(예: return pages + 1; 처럼 눈에 띄는 한 줄은 안 됨), 두 곳 이상(보통 둘 이상의 파일)을 함께 고쳐야 하며, 흔한 첫 수정이 NOTES.md 사례는 통과하지만 숨은 테스트에서는 걸리게 만듭니다. 교과서에 나오는 한 가지 패턴만으로 풀리면 안 됩니다.";
        };
    }

    private final CodeRunner runner;
    private final GeneratedTaskRepository writer;
    private final SoloSolveService solo;

    public DevTaskGeneratorService(TaskGenAiService ai, TaskGenProperties props, CodeRunner runner, GeneratedTaskRepository writer,
            SoloSolveService solo) {
        super(ai, props);
        this.runner = runner;
        this.writer = writer;
        this.solo = solo;
    }

    @Override
    protected String system(TaskGenRequest form) {
        return SYSTEM.formatted(form.minutes(), depth(form.minutes())) + TaskGenRequest.COMPETENCY_PROMPT;
    }

    @Override
    protected String user(TaskGenRequest form, List<GenCheckResultDto> failed) {
        StringBuilder sb = new StringBuilder();
        sb.append("제한 시간: ").append(form.minutes()).append("분\n")
                .append("주제: ").append(form.topic()).append('\n')
                .append("함정: ").append(form.trap()).append('\n')
                .append("위 조건으로 과제를 만들어 주세요.");
        if (!failed.isEmpty()) {
            sb.append("\n\n앞서 만든 과제는 자동 확인에서 아래 검사에 실패했습니다. 이 문제를 고쳐 과제를 처음부터 다시 만들어 주세요.");
            for (GenCheckResultDto c : failed) sb.append("\n- ").append(c.name()).append(": ").append(c.detail());
        }
        return sb.toString();
    }

    @Override
    protected Class<DevDraftDto> draftType() {
        return DevDraftDto.class;
    }

    @Override
    protected List<GenCheckService<DevDraftDto>> checks(TaskGenRequest form, DevDraftDto draft) {
        return DevCheckService.forDraft(runner, draft, form);
    }

    /**
     * 다른 fail 검사가 모두 통과한 Draft 에만 "AI 혼자 바로 풀림" 검사를 돌린다(비용 절약).
     * 용도 LIVE 는 풀리면 fail(다시 생성하며 AI 풀이를 알려 줌), SELF 는 warn.
     */
    @Override
    protected Extra afterChecks(TaskGenRequest form, DevDraftDto draft, List<GenCheckResultDto> results) {
        if (solo == null || !solo.enabled()) return skip("pair.taskgen.solo-enabled=false");
        if (results.stream().anyMatch(GenCheckResultDto::needsRegeneration)) return skip("다른 검사가 실패해 돌리지 않음");
        try {
            SoloSolveService.Result r = solo.run(draft, "LIVE".equalsIgnoreCase(form.usage()));
            Map<String, Object> tokens = new LinkedHashMap<>();
            tokens.put("input", r.inputTokens());
            tokens.put("output", r.outputTokens());
            return new Extra(List.of(r.check()), Map.of("soloTokens", tokens), r.files());
        } catch (RuntimeException e) {
            return new Extra(List.of(new GenCheckResultDto(SoloSolveService.NAME, true, "AI 단독 풀이를 확인하지 못했어요(호출 실패)",
                    GenCheckResultDto.WARN)), Map.of(), Map.of());
        }
    }

    private static Extra skip(String why) {
        return new Extra(List.of(SoloSolveService.skipped(why)), Map.of(), Map.of());
    }

    @Override
    protected Map<String, Object> details(DevDraftDto draft) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("trapKeywords", draft.trapKeywords());
        m.put("taskFollowUp", draft.taskFollowUp());
        if (draft.wrongFixes() != null) m.put("wrongFixReasons", draft.wrongFixes().stream().map(DevDraftDto.WrongFix::reason).toList());
        return m;
    }

    @Override
    protected List<String> draftCompetencies(DevDraftDto draft) {
        return draft.competencies();
    }

    @Override
    protected String save(TaskGenRequest form, DevDraftDto draft, GenReport report, long adminId) {
        return writer.saveDev(form, draft, report, adminId);
    }

    @Override
    protected String save(TaskGenRequest form, DevDraftDto draft, GenReport report, long adminId, Map<String, String> extraFiles) {
        return writer.saveDev(form, draft, report, adminId, extraFiles);
    }
}
