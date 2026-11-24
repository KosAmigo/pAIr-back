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

/** 개발(DEV) 과제 생성기. 프롬프트·출력 형식·검사 목록만 정한다 (PoC poc/taskgen/taskgen.py 를 옮김, 2026-11-24 기준 문서 개정에 맞춤) */
@Component
public class DevTaskGeneratorService extends TaskGeneratorService<DevDraftDto> {

    /*
     * 프롬프트 규칙과 기준 문서(pAIr-docs 설계/좋은_과제_기준.md, 2026-11-24 개정) 번호 대응. 텍스트 블록 안에는 주석을 쓸 수 없어 여기에 모아 둔다.
     *  - PURPOSE(맨 앞) ..................... 1장 핵심 정의, 2장 E1~E5
     *  - BOUNDARY ........................... 3장 명세와 답 노출의 경계 (M1, M5)
     *  - 단계형 과제(기능 추가 기본) ............ 5장 DEV, E1
     *  - 순수 Java·패키지 규칙 ................ 형식(검사 FORMAT)
     *  - 시작 코드 컴파일·공개 테스트·결함 위치 주석 금지 .. 5장 DEV, E4, M5, Q2, Q3
     *  - judgmentPoints(basis, 선택지별 evidence) ... E2, M4, M1(판단 근거가 자료에 있음), M2(어느 선택지도 숨은 테스트에서 안 막힘)
     *  - hiddenFlaws ........................ E3, M3, Q3 (AI 가 흔히 틀리는 지점)
     *  - 숨은 테스트(시작 코드에서 실패, 사례 밖 경우) .. E4, M3
     *  - 동시성 테스트 규칙·altSolutions ......... M2
     *  - wrongFixes(숨은 결함을 놓친 풀이) ........ M3
     *  - requirements·NOTES.md ............... 3장 경계 (M1, M5)
     *  - 장애 분석 파일 ....................... M1, E4, Q3
     *  - trapKeywords ........................ M5
     *  - 과제 깊이(depth) ..................... 6장 표, Q1
     *  - 정답 변경량 .......................... Q1
     *  - 품질 규칙(현실감, 업계 지식 불필요) ....... Q2, Q5
     *  - taskFollowUp ........................ E5, M5
     */
    private static final String SYSTEM = PURPOSE + BOUNDARY + """
            당신은 AI 활용 역량 평가 플랫폼의 개발 과제 출제자입니다. 응시자는 %d분 동안 AI 어시스턴트와 함께 기존 Java 코드를 읽고 기능을 추가합니다(40분 이상은 버그 수정 → 기능 추가 → 확장 단계가 이어지는 단계형).
            규칙:
            - 순수 Java 21 + JUnit 5 만 씁니다. 외부 라이브러리, 파일, 네트워크, Spring 은 쓰지 않습니다. 패키지는 하나(예: shop)이고 경로는 src/<패키지>/X.java, test/<패키지>/XTest.java 입니다.
            - 시작 코드는 컴파일되고 공개 테스트를 통과합니다. 공개 테스트는 기본 동작만 확인합니다. 결함이나 그 위치를 알리는 주석은 쓰지 않습니다.
            - 판단 지점(judgmentPoints): 요구 중 1~2개는 일부러 모호해서 사람이 가정을 밝히거나 자료로 판단해야 합니다. 각각 question(무엇을 정해야 하나), options(고를 수 있는 선택지 2개 이상), basis(판단 근거가 되는 문장. 응시자에게 보이는 설명·요구사항·NOTES.md·코드 주석에 있는 그대로 한 줄 복사)를 씁니다. evidence 에는 선택지마다 그 선택을 뒷받침하는 사실 한 줄씩을 씁니다(보이는 자료에서 문장을 그대로 복사하며 "파일명 N쪽" 같은 위치만 적지 않음. 한 문장이 두 선택지를 함께 말해도 됨. 예: "소비자 A 는 -1 을, 소비자 B 는 현재 수량을 받는다"). "운영 자료에 사례가 있다", "정책이 정해지지 않았다", "서비스마다 다르다"처럼 자료가 있다고만 가리키는 문장은 근거가 아니므로, 사례와 값을 NOTES.md 나 요구사항에 실제로 적습니다. 어느 쪽을 골라도 자료를 근거로 설명할 수 있어야 하고, 숨은 테스트는 어느 선택지를 골라도 통과하도록 선택지와 상관없이 항상 성립하는 결과만 확인합니다.
            - 숨은 결함(hiddenFlaws): AI 가 흔히 틀리는 지점(경계값, 시간대·날짜, 반올림·단위, 동시성, 빠진 예외 처리)에 둡니다. 각각 description(결함 설명), verifyWith(자료나 테스트 어디와 대조하면 보이는지), caughtBy(이 결함을 잡는 숨은 테스트 메서드 이름 1개. 그 테스트는 시작 코드나 wrongFixes 중 하나에서 실제로 실패해야 함)를 씁니다. 결함의 근거는 코드, 설명, NOTES.md 의 사실로 확인할 수 있어야 하고 자료에 없는 지식이 필요한 결함은 쓰지 않습니다. 결함이 시작 코드나 공개 테스트의 한 줄에서 바로 보이면 안 됩니다.
            - 숨은 테스트는 시작 코드에서 실패하고 올바르게 풀면 통과합니다. NOTES.md 에 적은 사례만 확인하지 말고, 사례 밖의 경우(같은 원인의 다른 경계)를 1개 이상 포함해 사례 값만 하드코딩한 풀이가 실패하게 합니다. 동시성 테스트는 assertTimeoutPreemptively 로 10초 안에 끝나게 하고 충분히 반복합니다.
            - 동시성·타이밍 테스트의 대기 장치(CyclicBarrier, CountDownLatch 등)는 타임아웃이 나도 테스트를 실패시키지 말고(예외를 삼키고) 진행하게 합니다. 단언은 결과(재고가 음수인지, 승인된 개수 등)만 하고 특정 구현 구조(어떤 락을 쓰는지, 어떤 메서드를 락 안에서 부르는지)를 전제하지 않습니다.
            - altSolutions: 정답과 구조(락 위치, 자료구조, 메서드 분리 등)가 다르지만 요구사항은 모두 지키는 올바른 풀이 1~2개. 각각 note(정답과 어떻게 다른지 한 줄)와 files(바뀐 src 파일의 전체 내용). 시작 코드에 덮어쓰면 공개·숨은 테스트를 모두 통과해야 하고, 정답 복사본이 아니라 정말 다른 구조여야 합니다.
            - wrongFixes: "숨은 결함을 놓친 풀이", 즉 AI 가 설명과 파일을 그대로 보고 그럴듯하게 낼 법하지만 숨은 결함을 지나친 수정 2~3개. 각각 reason(어떤 결함을 놓쳐 왜 틀렸는지 한 줄)과 files(바뀐 src 파일의 전체 내용). 시작 코드에 덮어쓰면 컴파일되고 공개 테스트는 통과하지만 숨은 테스트는 1개 이상 실패해야 합니다. 정답과 같은 동작을 하는 다른 풀이는 오답이 아닙니다.
              예: {"reason": "하루를 24시간으로 더해 날짜가 없는 날에서 틀린다", "files": [{"path": "src/shop/DailyWindow.java", "content": "package shop; ..."}]}
            - solution 은 시작 코드의 src 파일 중 바뀐 파일의 전체 내용입니다. 테스트 파일은 바꾸지 않습니다.
            - requirements 는 입력 → 기대 출력 예와 항상 성립해야 하는 불변식만, NOTES.md(INCIDENT.md)는 현상과 재현 값(언제, 어떤 입력, 어떤 값이 나왔는지)만 적습니다. 계산 순서, 비교 방식, 경계 포함 여부처럼 고치는 방법을 정하는 문장(예: "할인액을 뺀 금액에 비율을 곱한다", "곱한 뒤 내림", "한 번에 확정", "~하도록 고치면")과 원인 추정은 쓰지 않습니다. 결함을 피하는 기대 결과(예: 범위를 넘는 입력의 처리)는 입력→기대 출력 예 여러 개 중 하나로만 두고, "~하면 예외가 발생한다", "~로 잡히지 않는다", "~하지 않는다" 같은 규칙 문장이나 원인 문장으로 쓰지 않습니다.
            - 장애 분석 과제(category 에 '장애')는 재현 로그나 접수 메모를 files 에 넣습니다: logs/이름.log 또는 NOTES.md(INCIDENT.md), 공백 뺀 200자 이상, 현상만 적고 원인 단어는 쓰지 않습니다. 이 파일들은 경로 규칙(src/·test/)의 예외입니다.
            - trapKeywords 는 결함의 핵심어 2~5개입니다(응시자가 알면 정답이 거의 드러나는 단어, 예: 서머타임, 전환일, 24시간). description, requirements, 응시자에게 보이는 파일 이름과 주석(시작 코드, 공개 테스트, NOTES.md, logs 포함)에 쓰지 말고 현상만 설명합니다.
            - 현실감: 실제 업무 코드와 장애 보고처럼 쓰고 일부러 꼰 코드나 어색한 이름은 쓰지 않습니다. 필요한 용어와 규칙은 설명과 NOTES.md 안에 씁니다. 설명과 요구사항은 한국어입니다.
            - 과제 깊이는 제한 시간에 맞춥니다: %s
            - 정답의 변경량이 제한 시간에 맞아야 합니다. 정답과 시작 코드의 바뀐 줄(추가+삭제 합)이 20분은 2줄 이상, 40분은 6줄 이상, 60분은 12줄 이상이 되게 설계합니다.
            - code 는 영문 소문자와 하이픈만 쓴 짧은 이름(예: coupon-discount), summary 는 과제 목록 카드에 보일 한국어 한 문장(80자 이내, 정답·결함 위치·원인 조건을 드러내지 않음), category 는 과제 형태를 나타내는 한국어 짧은 말(예: 장애 분석, 기능 구현, 성능 개선)입니다.
            - taskFollowUp 은 후속 질문 1개입니다. "왜 그렇게 정했나 / 조건이 바뀌면 / 이대로 내보내겠나" 중 이 과제의 판단 지점이나 숨은 결함에 맞춘 질문을 씁니다(예: "AI 가 낸 수정이 다른 입력에서도 맞는지 어떻게 확인했나요?"). trapKeywords 와 숨은 테스트 메서드 이름은 쓰지 않습니다. 앞의 공용 질문 2개는 서버가 붙입니다.""";

    /**
     * 제한 시간별 과제 깊이. 기준 문서 6장 표(2026-11-24 개정)와 같은 값. 기본은 기존 코드에 기능 추가, 40분 이상은 단계형(5장 DEV)
     */
    static String depth(int minutes) {
        return switch (minutes) {
            case 20 -> "20분 = 파일 2~3개, 기존 코드에 기능 1개 추가, 판단 지점 1개, 숨은 결함 1개, 숨은 테스트 1~2개. 짧게 읽고 풀 수 있게 작게 만듭니다.";
            case 40 -> "40분 = 파일 3~5개, 2단계(버그 수정 → 기능 추가), 판단 지점 1~2개, 숨은 결함 1~2개. 흔한 첫 수정이 NOTES.md 나 공개 테스트 사례는 통과하지만 숨은 테스트에서는 걸리게 만듭니다.";
            default -> "60분 = 파일 4~6개, 3단계(버그 수정 → 기능 추가 → 확장), 판단 지점 2개, 숨은 결함 2개. 여러 파일을 따라가야 하고, 교과서에 나오는 한 가지 패턴만으로 풀리면 안 됩니다. 흔한 첫 수정이 NOTES.md 나 공개 테스트 사례는 통과하지만 숨은 테스트에서는 걸리게 만듭니다.";
        };
    }

    private final CodeRunner runner;
    private final GeneratedTaskRepository writer;
    private final SoloSolveService solo;

    /** 단계별 생성 없이(옛 방식만) 만든다(테스트용) */
    public DevTaskGeneratorService(TaskGenAiService ai, TaskGenProperties props, CodeRunner runner, GeneratedTaskRepository writer,
            SoloSolveService solo) {
        this(ai, props, runner, writer, solo, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public DevTaskGeneratorService(TaskGenAiService ai, TaskGenProperties props, CodeRunner runner, GeneratedTaskRepository writer,
            SoloSolveService solo, DevPipelineService pipeline) {
        super(ai, props, pipeline);
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
     * 참고 지표(DEV): 다른 fail 검사가 모두 통과한 Draft 에만 "AI 단독 풀이" 를 1회 돌려 숨은 결함을 잡았는지 기록한다(비용 절약).
     * 결과는 항상 ok(warn 등급)라 다시 생성을 일으키지 않는다. 호출이 실패해도 warn.
     */
    @Override
    protected Extra afterChecks(TaskGenRequest form, DevDraftDto draft, List<GenCheckResultDto> results) {
        if (results.stream().anyMatch(GenCheckResultDto::needsRegeneration)) return soloExtra(draft, results);
        // M5: 다른 검사가 모두 통과한 뒤에만 답 노출 AI 판정을 돌린다. 위반이면 다시 생성하므로 단독 풀이는 건너뛴다
        List<GenCheckResultDto> judged = leakJudge.judgeAll(AnswerLeakJudgeService.visible(draft), AnswerLeakJudgeService.flaws(draft)); // M5 + M1 숫자 모순
        if (judged.stream().anyMatch(GenCheckResultDto::needsRegeneration)) return new Extra(judged, Map.of(), Map.of());
        Extra solo = soloExtra(draft, results);
        List<GenCheckResultDto> all = new java.util.ArrayList<>(judged);
        all.addAll(solo.checks());
        return new Extra(all, solo.details(), solo.files());
    }

    private Extra soloExtra(DevDraftDto draft, List<GenCheckResultDto> results) {
        if (solo == null || !solo.enabled()) return skip("pair.taskgen.solo-enabled=false");
        if (results.stream().anyMatch(GenCheckResultDto::needsRegeneration)) return skip("다른 검사가 실패해 돌리지 않음");
        try {
            SoloSolveService.Result r = solo.run(draft);
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
        m.put("judgmentPoints", draft.judgmentPoints()); // E2 검수용
        m.put("hiddenFlaws", draft.hiddenFlaws()); // E3 검수용
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
