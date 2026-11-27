package com.pair.domain.taskgen.service;

import com.pair.domain.taskgen.dto.AltSolutionDto;
import com.pair.domain.taskgen.dto.BizDraftDto;
import com.pair.domain.taskgen.dto.BlueprintDto;
import com.pair.domain.taskgen.dto.DevDraftDto;
import com.pair.domain.taskgen.dto.GenCheckResultDto;
import com.pair.domain.taskgen.dto.ReviewResultDto;
import com.pair.domain.taskgen.dto.ReviewResultDto.Violation;
import com.pair.domain.taskgen.dto.TaskGenRequest;
import com.pair.domain.taskgen.service.TaskPipelineService.Stage;
import java.util.List;
import java.util.Set;

/**
 * 관문 검사(AI)의 모델 호출과 입력 만들기(설계 4장). 점검마다 필요한 입력만 받고 질문 하나만 한다. 프롬프트는 resources/taskgen/review/ 아래 파일이다.
 * 관문(통과·실패를 정함): 답 노출(두 모델이 따로), 정답 키(BIZ), 테스트 근거(DEV), 다른 풀이 실행(DEV). 일관성과 Q 는 경고만 한다.
 * 점검이 내는 위반 중 맡은 항목(Check.ids)과 Q(경고) 밖의 것은 버린다. 고치는 일은 하지 않는다: 실패하면 만든 단계를 다시 돌린다.
 */
final class TaskReviewService {

    /** 점검 목록. ids: 이 점검이 낼 수 있는 항목 번호. gate 는 실패하면 단계를 다시 돌리는 관문, 아니면 경고만 */
    enum Check {
        LEAK("leak", true, true, Set.of("M5"), true), // 답 노출: 응시자에게 보이는 글 전부 + 숨은 결함
        BACKING("backing", true, false, Set.of("M2"), true), // 테스트 근거(DEV): 숨은 테스트 단정 + 보이는 자료
        ANSWER_KEY("answer-key", false, true, Set.of("M2"), true), // 정답 키(BIZ): 문항·보기·인정 답 + 사례·판단 지점
        ALT("alt-solution", true, false, Set.of("M2"), true), // 다른 풀이 실행(DEV): 풀이를 쓰게 하고 숨은 테스트로 돌림
        CONSISTENCY("consistency", true, true, Set.of("C1", "C2", "C3"), false), // 일관성: 경고만
        FILLER("filler-leak", true, true, Set.of("M6", "M5"), true), // 관련 적은 자료(E6·M6): 답·결함 근거·판단 근거가 없는지. 생성(terra)과 다른 회사 모델이 본다
        BLUEPRINT("blueprint-leak", true, true, Set.of("M5", "M4"), true); // 설계도 문장 점검(관문 아님): 글에 그대로 들어갈 사실·근거·예가 규칙·절차를 말하거나 판단 지점을 못 박는지

        final String resource;
        final boolean dev, biz, gate;
        final Set<String> ids;

        Check(String resource, boolean dev, boolean biz, Set<String> ids, boolean gate) {
            this.resource = resource;
            this.dev = dev;
            this.biz = biz;
            this.ids = ids;
            this.gate = gate;
        }

        boolean appliesTo(boolean isDev) {
            return isDev ? dev : biz;
        }
    }

    /** 관문 하나: 결과에 붙는 이름, 점검, 부르는 단계(모델). 답 노출은 두 모델이 따로 본다 */
    record Gate(String name, Check check, Stage stage) {}

    static List<Gate> gates(boolean dev) {
        return dev
                ? List.of(new Gate(TaskPipelineService.LEAK_A_GATE, Check.LEAK, Stage.REVIEW), new Gate(TaskPipelineService.LEAK_B_GATE, Check.LEAK, Stage.REVIEW2),
                        new Gate(TaskPipelineService.FILLER_GATE, Check.FILLER, Stage.FILLER_REVIEW),
                        new Gate(TaskPipelineService.BACKING_GATE, Check.BACKING, Stage.REVIEW), new Gate(TaskPipelineService.ALT_GATE, Check.ALT, Stage.REVIEW))
                : List.of(new Gate(TaskPipelineService.LEAK_A_GATE, Check.LEAK, Stage.REVIEW), new Gate(TaskPipelineService.LEAK_B_GATE, Check.LEAK, Stage.REVIEW2),
                        new Gate(TaskPipelineService.FILLER_GATE, Check.FILLER, Stage.FILLER_REVIEW),
                        new Gate(TaskPipelineService.KEY_GATE, Check.ANSWER_KEY, Stage.REVIEW));
    }

    /** 점검 하나의 결과. dropped 는 맡은 항목이 아니라 버린 위반 수 */
    record Findings(Check check, List<Violation> violations, int dropped) {}

    private static final String OUTPUT = """

            출력: violations 목록(item, quote, location, fix, stage). quote 는 과제에서 그대로 복사한 글, fix 는 고칠 방향 한두 문장. 위반이 없으면 빈 목록.
            stage 는 정답 키·테스트 근거 점검에서만 채운다(다른 점검은 빈 값): 고치려면 어느 단계가 필요한지. WRITING(응시자 자료에 사실·예를 더하면 됨), BLUEPRINT(사례 자체가 자료와 안 맞아 사례를 바꾸거나 빼야 함), ANSWER(테스트나 정답 키가 잘못 쓰임), QUESTIONS(문항 글·보기 문제). 모르면 빈 값.
            """;

    /** 테스트 근거 입력에 설계도 사례(cases)가 있을 때만 붙는 머리글 */
    static final String CASES_MARK = "[사례별 근거와 테스트]";

    private final StageCallService calls;

    TaskReviewService(StageCallService calls) {
        this.calls = calls;
    }

    Findings run(Check check, Stage stage, String input) {
        // 테스트 근거: 설계도 사례가 있으면 사례별 점검 프롬프트, 없으면 단정별 점검 프롬프트(사례 없는 과제에서 오탐이 많았다)
        String resource = check == Check.BACKING && input.contains(CASES_MARK) ? "backing-cases" : check.resource;
        ReviewResultDto r = calls.call(stage, check.name(), TaskPromptService.check(resource) + OUTPUT, input, ReviewResultDto.class);
        boolean biz = input.startsWith("직군: BIZ");
        List<Violation> kept = nz(r.violations()).stream().filter(v -> v != null && v.item() != null && (isQ(v) || check.ids.contains(v.item().strip().toUpperCase())))
                .filter(v -> !(check == Check.LEAK && biz && recordedDefinition(v))).toList(); // g9: 검수 모델이 허용 규칙을 어기고 회사 정의 기록을 걸어 BIZ 8/10이 떨어짐
        return new Findings(check, kept, nz(r.violations()).size() - kept.size());
    }

    private static final java.util.regex.Pattern RECORD_TONE = java.util.regex.Pattern.compile("(적혀|기재|기록|작성되|답변했|남아|표시되|명시되)[가-힣 ]{0,12}[.。]?\s*$");
    private static final java.util.regex.Pattern INSTRUCTION = java.util.regex.Pattern.compile(
            "해야|하세요|해 주|해주|할 것|말 것|빼서|나눠|곱해|더해|먼저|한 뒤|한 다음|하지 않|확인해|대조해|계산한다|구한다|가정할|기준으로 구");

    /**
     * BIZ 답 노출(M5) 위반이 자료(문서·CSV) 속 회사 정의 기록 한 문장이면 true(좋은_과제_기준 3장: 회사 정의를 기록 말투로 한 번 쓰는 건 된다).
     * 기록 말투로 끝나고 지시·계산 절차 말이 없고 한 문장 길이이며 자료 위치일 때만 본다. 설명·요구사항·문항·후속 질문 위치는 그대로 둔다
     */
    static boolean recordedDefinition(Violation v) {
        String loc = v.location() == null ? "" : v.location();
        String q = v.quote() == null ? "" : v.quote().strip();
        boolean material = (loc.contains("자료/") || loc.contains("문서") || loc.toLowerCase().contains("csv") || loc.toLowerCase().contains(".pdf") || loc.toLowerCase().contains(".md"))
                && !loc.contains("설명") && !loc.contains("요구사항") && !loc.contains("문항") && !loc.contains("후속");
        if (!material || q.isEmpty() || q.length() > 200) return false;
        return RECORD_TONE.matcher(q).find() && !INSTRUCTION.matcher(q).find();
    }

    /** 설계도를 만든 직후 부르는 싼 점검: 응시자 글에 그대로 들어갈 문장이 규칙·절차를 말하거나 판단 지점을 한 선택으로 못 박는지. 고쳐야 할 점을 글로 돌려준다 */
    List<String> blueprintProblems(BlueprintDto bp) {
        StringBuilder sb = new StringBuilder("[숨은 결함(출제자만 아는 정보)]\n").append(flaws(bp)).append("[응시자 글에 그대로 들어갈 사실]\n");
        bp.facts().forEach(f -> sb.append("- ").append(f.text()).append('\n'));
        sb.append("[판단 지점]\n");
        bp.judgments().forEach(j -> {
            sb.append("- 질문: ").append(j.question()).append(" | 근거: ").append(j.basis()).append('\n');
            j.options().forEach(o -> sb.append("  선택지: ").append(o.text()).append(" | 근거 문장: ").append(o.fact()).append('\n'));
        });
        sb.append("[예(요구사항에 입력 → 기대 출력으로 들어감)]\n");
        bp.examples().forEach(e -> sb.append("- ").append(e.input()).append(" → ").append(e.expected()).append('\n'));
        sb.append("[숨은 테스트·문항 사례]\n");
        bp.cases().forEach(c -> sb.append("- ").append(c.name()).append(" | ").append(c.situation()).append(" → ").append(c.expected()).append(" | 근거: ").append(c.basis()).append('\n'));
        return run(Check.BLUEPRINT, Stage.REVIEW, sb.toString()).violations().stream().filter(Violation::mustFix)
                .map(v -> v.item() + " [" + v.location() + "] " + v.quote() + " → " + v.fix()).toList();
    }

    /** ALT 점검: 검수자가 다른 풀이를 쓴다(실행은 호출하는 쪽이 한다) */
    List<DevDraftDto.FileDraft> alternative(String input) {
        AltSolutionDto r = calls.call(Stage.REVIEW, Check.ALT.name(), TaskPromptService.check(Check.ALT.resource), input, AltSolutionDto.class);
        return nz(r.files());
    }

    /** 관문 결과: 꼭 고칠 위반(M·C)이 있으면 실패, 없으면 통과. 위반 글에는 위치가 들어 있어 어느 단계 탓인지 가를 수 있다 */
    static GenCheckResultDto result(Gate g, List<Violation> violations) {
        List<Violation> must = violations.stream().filter(Violation::mustFix).toList();
        if (must.isEmpty()) return new GenCheckResultDto(g.name(), true, "위반 없음");
        return new GenCheckResultDto(g.name(), false, describe(must));
    }

    static String describe(List<Violation> vs) {
        StringBuilder sb = new StringBuilder();
        for (var v : vs) {
            if (sb.length() > 0) sb.append(" / ");
            sb.append(v.item()).append(" [").append(v.location()).append("] ").append(v.quote()).append(" → ").append(v.fix());
            if (v.stage() != null && v.stage().strip().toUpperCase().matches("WRITING|BLUEPRINT|ANSWER|QUESTIONS|CODE")) sb.append(" [단계:").append(v.stage().strip().toUpperCase()).append(']');
        }
        return sb.toString();
    }

    private static boolean isQ(Violation v) {
        return !v.mustFix();
    }

    // ── 점검별 입력 ─────────────────────────────

    /** check 가 이 직군에 안 맞으면 null */
    static String devInput(Check check, TaskGenRequest form, BlueprintDto bp, DevDraftDto d) {
        if (!check.dev) return null;
        StringBuilder sb = new StringBuilder("직군: DEV, 제한 시간: ").append(form.minutes()).append("분\n");
        switch (check) {
            case LEAK -> sb.append("[숨은 결함(출제자만 아는 정보)]\n").append(flaws(bp)).append(visibleDev(d));
            case BACKING -> {
                // 사례마다 한 묶음: 기대 결과, 그것을 정한다는 근거 문장, 그 사례의 테스트 본문. 질문은 "이 문장이 이 사례의 기대 결과를 실제로 정하는가"
                if (bp != null && bp.cases() != null && !bp.cases().isEmpty()) {
                    var bodies = GenTextService.testBodies(d.hiddenTests());
                    sb.append(CASES_MARK).append('\n');
                    for (var c : bp.cases()) {
                        sb.append("사례 ").append(c.name()).append(" | 기대 결과: ").append(c.expected()).append(" | 근거 문장: ").append(c.basis())
                                .append(" | 근거 종류: ").append(basisKind(bp, c)).append('\n')
                                .append("  테스트 본문:\n").append(bodies.getOrDefault(c.name(), "(이 이름의 테스트 없음)")).append('\n');
                    }
                } else { // 사례가 없으면(이미 만든 과제를 평가할 때) 숨은 테스트 전체를 주고 단정별로만 본다
                    nz(d.hiddenTests()).forEach(f -> sb.append("\n[숨은 테스트 ").append(f.path()).append("]\n").append(f.content()).append('\n'));
                }
                sb.append(visibleDev(d));
            }
            case CONSISTENCY -> {
                if (bp != null) sb.append("[상황] ").append(bp.scenario()).append('\n').append(intended(bp));
                sb.append(visibleDev(d));
            }
            case ALT -> {
                sb.append("[설계도 사례(cases)]\n").append(StageCallService.json(bp == null ? List.of() : bp.cases())).append('\n').append(visibleDev(d));
                nz(d.solution()).forEach(f -> sb.append("\n[공식 정답 풀이 ").append(f.path()).append("]\n").append(f.content()).append('\n'));
            }
            default -> {
                return null;
            }
        }
        return sb.toString();
    }

    static String bizInput(Check check, TaskGenRequest form, BlueprintDto bp, BizDraftDto d) {
        if (!check.biz) return null;
        StringBuilder sb = new StringBuilder("직군: BIZ, 제한 시간: ").append(form.minutes()).append("분\n");
        switch (check) {
            case LEAK -> {
                sb.append("[숨은 결함(출제자만 아는 정보)]\n").append(flaws(bp));
                if (bp != null && bp.conclusion() != null) sb.append("정답 결론: ").append(bp.conclusion().correct()).append(" / 함정에 빠진 결론: ").append(bp.conclusion().wrong()).append('\n');
                sb.append(visibleBiz(d, true)).append(answers(d));
            }
            case ANSWER_KEY -> {
                sb.append("[판단 지점(설계도)]\n").append(StageCallService.json(bp == null ? List.of() : bp.judgments())).append('\n');
                sb.append("[설계도 사례(기대 결과)]\n").append(StageCallService.json(bp == null ? List.of() : bp.cases())).append('\n');
                sb.append(questions(d, true));
                sb.append("\n[응시자 자료(문서·CSV)]\n").append(visibleBiz(d, false));
            }
            case CONSISTENCY -> {
                if (bp != null) sb.append("[상황] ").append(bp.scenario()).append('\n').append(intended(bp));
                sb.append(visibleBiz(d, false));
            }
            default -> {
                return null;
            }
        }
        return sb.toString();
    }

    /**
     * 관련 적은 자료 점검의 입력(E6·M6): 출제자만 아는 정보(결함, 사례 기대 결과, 정답 결론, 판단 지점)와 관련 적은 자료 글.
     * 관련 적은 자료가 없으면 null(점검하지 않음)
     */
    static String fillerInput(BlueprintDto bp, java.util.Map<String, String> fillerTexts) {
        if (bp == null || fillerTexts == null || fillerTexts.isEmpty()) return null;
        StringBuilder sb = new StringBuilder("[숨은 결함(출제자만 아는 정보)]\n").append(flaws(bp));
        for (var f : nz(bp.flaws())) sb.append("  결함 근거 문장: ").append(f.evidence()).append('\n');
        if (bp.conclusion() != null) sb.append("정답 결론: ").append(bp.conclusion().correct()).append(" / 함정에 빠진 결론: ").append(bp.conclusion().wrong()).append('\n');
        sb.append("[사례 기대 결과(출제자만 아는 정보)]\n");
        for (var cs : nz(bp.cases())) sb.append("- ").append(cs.name()).append(" | ").append(cs.situation()).append(" → ").append(cs.expected()).append('\n');
        sb.append("[판단 지점과 선택지 근거]\n");
        for (var j : nz(bp.judgments())) {
            sb.append("- ").append(j.question()).append('\n');
            for (var o : nz(j.options())) sb.append("  선택지: ").append(o.text()).append(" | 근거: ").append(o.fact()).append('\n');
        }
        sb.append("[관련 적은 자료]\n");
        fillerTexts.forEach((path, text) -> sb.append("\n[파일 ").append(path).append("]\n").append(text).append('\n'));
        return sb.toString();
    }

    // ── 조각 ─────────────────────────────

    /** 사례의 근거 문장이 요구사항 예(입력 → 기대 출력)이면 "예", 아니면 "사실" */
    static String basisKind(BlueprintDto bp, BlueprintDto.Case c) {
        String b = GenTextService.norm(c.basis());
        for (var e : bp.examples()) {
            String ex = GenTextService.norm(e.input() + e.expected());
            if (!b.isBlank() && (ex.equals(b) || b.contains(GenTextService.norm(e.input())) && b.contains(GenTextService.norm(e.expected())))) return "예";
        }
        return "사실";
    }

    private static String flaws(BlueprintDto bp) {
        if (bp == null) return "";
        StringBuilder sb = new StringBuilder();
        for (var f : bp.flaws()) sb.append("- ").append(f.what()).append(" (위치: ").append(f.where()).append(")\n");
        return sb.toString();
    }

    /** 일관성 경고용: 출제자가 일부러 넣은 것(모순으로 보지 않게 알린다) */
    private static String intended(BlueprintDto bp) {
        StringBuilder sb = new StringBuilder("[의도한 결함(모순으로 보지 않음)]\n").append(flaws(bp)).append("[판단 지점(의도한 모호함)]\n");
        for (var j : bp.judgments()) sb.append("- ").append(j.question()).append('\n');
        return sb.toString();
    }

    /** 응시자에게 보이는 글: 설명, 요구사항, 보이는 파일(시작 코드·공개 테스트·메모), 후속 질문 */
    private static String visibleDev(DevDraftDto d) {
        StringBuilder sb = new StringBuilder("[설명]\n").append(String.join("\n", nz(d.description()))).append("\n[요구사항]\n").append(String.join("\n", nz(d.requirements()))).append('\n');
        nz(d.files()).forEach(f -> sb.append("\n[보이는 파일 ").append(f.path()).append("]\n").append(f.content()).append('\n'));
        if (d.taskFollowUp() != null && !d.taskFollowUp().isBlank()) sb.append("\n[후속 질문]\n").append(d.taskFollowUp()).append('\n');
        return sb.toString();
    }

    private static String visibleBiz(BizDraftDto d, boolean withQuestions) {
        StringBuilder sb = new StringBuilder("[설명]\n").append(String.join("\n", nz(d.description()))).append("\n[요구사항]\n").append(String.join("\n", nz(d.requirements()))).append('\n');
        for (var doc : nz(d.documents())) {
            sb.append("\n[문서 ").append(doc.path()).append("]\n");
            for (int i = 0; i < nz(doc.pages()).size(); i++) sb.append('(').append(i + 1).append("쪽) ").append(doc.pages().get(i)).append('\n');
        }
        nz(d.csvFiles()).forEach(f -> sb.append("\n[CSV ").append(f.path()).append("]\n").append(f.content()).append('\n'));
        if (withQuestions) sb.append(questions(d, false));
        else {
            int n = 0;
            for (var q : nz(d.questions())) sb.append("\n[문항 ").append(++n).append("] ").append(q.content()).append('\n');
        }
        if (nz(d.followUps()).size() > 2) sb.append("\n[후속 질문]\n").append(d.followUps().get(2)).append('\n');
        return sb.toString();
    }

    /** 문항과 보기. withKey 면 정답 표시와 인정 답도 */
    private static String questions(BizDraftDto d, boolean withKey) {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (var q : nz(d.questions())) {
            n++;
            sb.append("\n[문항 ").append(n).append(' ').append(q.type()).append("] ").append(q.content()).append('\n');
            int c = 0;
            for (var ch : nz(q.choices())) sb.append("  보기").append(++c).append(withKey && ch.correct() ? "(정답) " : " ").append(ch.content()).append('\n');
            if (withKey && !nz(q.accepted()).isEmpty()) sb.append("  인정 답: ").append(q.accepted()).append("  검산식: ").append(q.calc()).append('\n');
        }
        return sb.toString();
    }

    /** 문항별 정답(다른 문항에 노출됐는지 비교용) */
    private static String answers(BizDraftDto d) {
        StringBuilder sb = new StringBuilder("\n[정답 키(출제자만 아는 정보)]\n");
        int n = 0;
        for (var q : nz(d.questions())) {
            n++;
            if (q.type() == BizDraftDto.Type.SINGLE) {
                int right = 0;
                for (int i = 0; i < nz(q.choices()).size(); i++) if (q.choices().get(i).correct()) right = i + 1;
                sb.append("Q").append(n).append(": 보기").append(right).append('\n');
            } else sb.append("Q").append(n).append(": ").append(q.accepted()).append('\n');
        }
        return sb.toString();
    }

    private static <T> List<T> nz(List<T> l) {
        return l == null ? List.of() : l;
    }
}
