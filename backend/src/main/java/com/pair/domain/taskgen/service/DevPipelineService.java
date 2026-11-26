package com.pair.domain.taskgen.service;

import com.pair.domain.task.dto.AiLimitsDto;
import com.pair.domain.taskgen.dto.BlueprintDto;
import com.pair.domain.taskgen.dto.DevAnswerDto;
import com.pair.domain.taskgen.dto.DevCodeDto;
import com.pair.domain.taskgen.dto.DevDraftDto;
import com.pair.domain.taskgen.dto.DevWritingDto;
import com.pair.domain.taskgen.dto.FillerDto;
import com.pair.domain.taskgen.dto.GenCheckResultDto;
import com.pair.domain.taskgen.dto.StageUsageDto;
import com.pair.domain.taskgen.dto.TaskGenRequest;
import com.pair.global.config.TaskGenProperties;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import org.springframework.stereotype.Component;

import com.pair.domain.taskgen.service.TaskPipelineService.Stage;

/**
 * 개발(DEV) 과제 단계별 생성: 설계도 → 작성(결함을 모름) → 시작 코드(결함을 앎, 글 없음) → 정답 쪽(전부 앎) → 조립.
 * 실행 검사가 실패하면 그 검사를 낸 단계부터 다시 돌린다(설계 3·4장).
 */
@Component
public class DevPipelineService implements TaskPipelineService<DevDraftDto> {

    private static final Set<Stage> ALL = EnumSet.of(Stage.BLUEPRINT, Stage.WRITING, Stage.CODE, Stage.ANSWER);
    /** E6·M6(materialGate) 를 켜면 정답 쪽 뒤에 단계 4' 관련 적은 자료가 더 돈다 */
    private static final Set<Stage> ALL_E6 = EnumSet.of(Stage.BLUEPRINT, Stage.WRITING, Stage.CODE, Stage.ANSWER, Stage.FILLER);

    private final TaskGenAiService ai;
    private final TaskGenProperties props;

    public DevPipelineService(TaskGenAiService ai, TaskGenProperties props) {
        this.ai = ai;
        this.props = props;
    }

    @Override
    public Run<DevDraftDto> start(TaskGenRequest form) {
        return new DevRun(form, new StageCallService(ai, props, true), props.materialGate());
    }

    /** 검사 이름 → 다시 돌릴 단계. 모르면 빈 집합 */
    static Set<Stage> owners(GenCheckResultDto c, List<String> codePaths) {
        return owners(c, codePaths, List.of());
    }

    /** fillerPaths: 단계 4' 가 만든 파일 경로. 실행·형식 검사가 이 파일을 가리키면 단계 4' 만 다시 돌린다(E6) */
    static Set<Stage> owners(GenCheckResultDto c, List<String> codePaths, List<String> fillerPaths) {
        Set<Stage> gate = StageRouteService.gateOwners(c, false);
        if (gate != null) return gate;
        String n = c.name(), d = c.detail() == null ? "" : c.detail();
        if (n.equals(TaskPipelineService.M6_TOTAL_CHECK)) return EnumSet.of(Stage.FILLER); // 자료 전체가 모자람: 관련 적은 자료를 다시
        if (n.equals(TaskPipelineService.M6_NEEDED_CHECK)) {
            if (d.contains("만들어지지 않았습니다")) return d.contains("NOTES.md") ? EnumSet.of(Stage.WRITING) : EnumSet.of(Stage.CODE);
            return EnumSet.of(Stage.BLUEPRINT); // 필요한 묶음이 큼: 설계도를 다시
        }
        if (!fillerPaths.isEmpty() && fillerPaths.stream().anyMatch(d::contains)
                && (n.equals(DevCheckService.START_PASSES) || n.equals(DevCheckService.HIDDEN_CATCHES) || n.equals(DevCheckService.SOLVED_PASSES)
                || n.equals(DevCheckService.WRONG_FIXES) || n.equals(DevCheckService.ALT_SOLUTIONS) || n.equals(DevCheckService.TIME)
                || n.equals(DevCheckService.NO_HINT) || n.equals(DevCheckService.FORMAT))) {
            return EnumSet.of(Stage.FILLER);
        }
        if (n.equals(TaskPipelineService.MENTION_CHECK)) return EnumSet.of(Stage.WRITING);
        if (n.equals(DevCheckService.FAKE_IMPL)) return EnumSet.of(Stage.BLUEPRINT); // 반대 결과 사례가 없다: 사례를 더해야 하는 설계도 문제
        if (n.equals(DevCheckService.NO_HINT) || n.equals(DevCheckService.START_PASSES) || n.equals(TaskPipelineService.STUB_CHECK)) return EnumSet.of(Stage.CODE);
        if (n.equals(DevCheckService.HIDDEN_CATCHES) || n.equals(DevCheckService.SOLVED_PASSES) || n.equals(DevCheckService.WRONG_FIXES)
                || n.equals(DevCheckService.TIME) || n.equals(DevCheckService.ALT_SOLUTIONS)
                || n.equals(DevCheckService.HIDDEN_FLAW)) return EnumSet.of(Stage.ANSWER);
        if (n.equals(DevCheckService.JUDGMENT) && d.contains("가리킵니다")) return EnumSet.of(Stage.BLUEPRINT); // 선택지 근거 사실이 자료가 있다고만 말함: 설계도가 쓴 문장
        if (n.equals(DevCheckService.JUDGMENT) || n.equals(TaskPipelineService.FACTS_CHECK) || n.equals(DevCheckService.FOLLOW_UP) || n.equals(TaskPipelineService.BASIS_CHECK)) return EnumSet.of(Stage.WRITING);
        if (n.equals(FeatureCheckService.NAME)) return d.contains(FeatureCheckService.CODE_STAGE) ? EnumSet.of(Stage.CODE) : EnumSet.of(Stage.WRITING);
        if (n.equals(TaskPipelineService.CASES_CHECK) || n.equals(TaskPipelineService.ADDED_CALLED_CHECK)) return EnumSet.of(Stage.ANSWER);
        if (n.equals(TaskPipelineService.HAN_CHECK)) {
            Set<Stage> s = EnumSet.noneOf(Stage.class);
            if (d.contains("[설계도]")) s.add(Stage.BLUEPRINT);
            if (d.contains("[작성]")) s.add(Stage.WRITING);
            if (d.contains("[코드]")) s.add(Stage.CODE);
            if (d.contains("[정답]")) s.add(Stage.ANSWER);
            if (d.contains("[관련 적은 자료]")) s.add(Stage.FILLER);
            return s;
        }
        if (n.equals(TaskPipelineService.BLUEPRINT_CHECK) || n.equals(TaskPipelineService.BLUEPRINT_SENTENCE_CHECK)) return EnumSet.of(Stage.BLUEPRINT);
        if (n.equals(DevCheckService.FORMAT)) {
            if (d.contains("정답 설명") || d.contains("숨은 테스트") || d.contains("정답 코드")) return EnumSet.of(Stage.ANSWER);
            if (d.contains("설명(description)") || d.contains("요구사항(requirements)") || d.contains("후속")) return EnumSet.of(Stage.WRITING);
            if (d.contains("시작 코드") || d.contains("공개 테스트") || d.contains("같은 경로") || codePaths.stream().anyMatch(d::contains)) return EnumSet.of(Stage.CODE);
            if (d.contains("경로")) return EnumSet.of(Stage.ANSWER);
        }
        return EnumSet.noneOf(Stage.class);
    }

    private static final class DevRun implements Run<DevDraftDto> {
        private final TaskGenRequest form;
        private final StageCallService calls;
        private final boolean e6;
        private final AiLimitsDto limits;
        private BlueprintDto bp;
        private DevWritingDto writing;
        private DevCodeDto code;
        private DevAnswerDto answer;
        private FillerDto filler;
        private List<GenCheckResultDto> checks = List.of();
        private List<String> redone = List.of();
        private List<GenCheckResultDto> previous = List.of(); // 바로 앞 다시 만들기 때 실패한 검사(같은 이유의 되풀이를 알아내는 데 쓴다)

        DevRun(TaskGenRequest form, StageCallService calls, boolean e6) {
            this.form = form;
            this.calls = calls;
            this.e6 = e6;
            this.limits = AiLimitsDto.forMinutes(form.minutes());
        }

        private List<String> fillerPaths() {
            return FillerService.devFiles(filler).stream().map(DevDraftDto.FileDraft::path).toList();
        }

        @Override
        public DevDraftDto create(Consumer<String> onStep) {
            return execute(null, onStep);
        }

        private StageRouteService.Plan plan(List<GenCheckResultDto> failed) {
            List<String> paths = code == null || code.files() == null ? List.of() : code.files().stream().map(DevDraftDto.FileDraft::path).toList();
            return StageRouteService.plan(routed(failed), c -> owners(c, paths, fillerPaths()), e6 ? ALL_E6 : ALL, EnumSet.of(Stage.CODE));
        }

        private List<GenCheckResultDto> routed(List<GenCheckResultDto> failed) {
            return failed.stream().map(c -> StageRouteService.reroute(bp, c, previous)).toList();
        }

        @Override
        public Set<String> kinds(List<GenCheckResultDto> failed) {
            List<String> paths = code == null || code.files() == null ? List.of() : code.files().stream().map(DevDraftDto.FileDraft::path).toList();
            return StageRouteService.kinds(routed(failed), c -> owners(c, paths, fillerPaths()));
        }

        @Override
        public List<String> plannedStages(List<GenCheckResultDto> failed) {
            return plan(failed).stages().stream().map(Enum::name).toList();
        }

        @Override
        public boolean restartsFrom(List<GenCheckResultDto> failed) {
            return plan(failed).fromBlueprint();
        }

        @Override
        public DevDraftDto redo(List<GenCheckResultDto> failed, Consumer<String> onStep) {
            StageRouteService.Plan plan = plan(failed);
            redone = plan.stages().stream().map(Enum::name).toList();
            previous = failed;
            return execute(plan, onStep);
        }

        private DevDraftDto execute(StageRouteService.Plan plan, Consumer<String> onStep) {
            Set<Stage> st = plan == null ? (e6 ? ALL_E6 : ALL) : plan.stages();
            boolean restart = plan == null || plan.fromBlueprint();
            if (st.contains(Stage.BLUEPRINT)) {
                onStep.accept(STEP_BLUEPRINT);
                bp = blueprint(plan == null ? "" : StageRouteService.feedback(plan.all()));
            }
            if (st.contains(Stage.WRITING)) {
                onStep.accept(STEP_WRITING);
                String fb = restart ? "" : TaskWritingService.writerFeedback(plan.byStage().get(Stage.WRITING), visibleText());
                writing = write(fb);
            }
            if (st.contains(Stage.CODE)) {
                onStep.accept(STEP_CODE);
                code = writeCode(restart ? "" : StageRouteService.feedback(plan.byStage().get(Stage.CODE)));
            }
            if (st.contains(Stage.ANSWER)) {
                onStep.accept(STEP_ANSWER);
                answer = answer(restart ? "" : StageRouteService.feedback(plan.byStage().get(Stage.ANSWER)));
            }
            if (e6 && st.contains(Stage.FILLER)) {
                onStep.accept(STEP_FILLER);
                filler = fill(restart ? "" : StageRouteService.feedback(plan.byStage().get(Stage.FILLER)));
            }
            DevDraftDto draft = TaskAssemblyService.dev(form, bp, writing, code, answer, filler);
            checks = preChecks(draft);
            return draft;
        }

        /** E6·M6 단계 4': 결함을 모르는 싼 모델이 관련 적은 자료를 쓴다. 자료 전체가 맥락 한도의 2.3배가 되도록 파일당 분량을 알려 준다 */
        private FillerDto fill(String feedback) {
            if (bp.fillers().isEmpty()) return new FillerDto(List.of());
            int neededChars = MaterialGateService.chars(materials(TaskAssemblyService.dev(form, bp, writing, code, answer, null)), bp.neededFiles());
            int per = MaterialGateService.perFillerChars(bp, neededChars, limits);
            FillerDto raw = calls.call(Stage.FILLER, TaskPromptService.filler(false), FillerService.input(bp, per, feedback), FillerDto.class);
            return FillerService.sanitized(raw, bp);
        }

        /** 응시자가 첨부할 수 있는 파일(경로 → 글): 시작 코드, 공개 테스트, NOTES.md, 관련 적은 자료 */
        private static java.util.Map<String, String> materials(DevDraftDto d) {
            java.util.Map<String, String> m = new java.util.LinkedHashMap<>();
            for (var f : d.files()) m.put(f.path(), f.content() == null ? "" : f.content());
            return m;
        }

        private BlueprintDto blueprint(String feedback) {
            String system = TaskPromptService.blueprint(false, form.minutes(), DevTaskGeneratorService.depth(form.minutes()), e6);
            String user = "제한 시간: " + form.minutes() + "분\n주제: " + form.topic() + "\n함정: " + form.trap() + (feedback.isEmpty() ? "" : "\n\n" + feedback);
            BlueprintDto b = BlueprintService.normalized(calls.call(Stage.BLUEPRINT, system, user, BlueprintDto.class), false);
            List<String> problems = blueprintProblems(b);
            if (problems.isEmpty()) problems = earlyReview(b); // 코드 검사를 통과하면 글에 들어갈 문장만 싸게 한 번 더 본다
            // 설계도가 모자라면 뒤 단계를 돌리기 전에 설계도만 다시 받는다(코드 검사에 걸리면 최대 2번, 다시 받은 것도 검사한다)
            for (int retry = 0; !problems.isEmpty() && retry < 2; retry++) {
                b = BlueprintService.normalized(calls.call(Stage.BLUEPRINT, system,
                        user + "\n\n설계도가 아래 문제로 걸렸습니다. 고쳐서 다시 내세요:\n- " + String.join("\n- ", problems), BlueprintDto.class), false);
                problems = blueprintProblems(b);
            }
            return b;
        }

        /** 설계도 코드 검사 + (E6·M6 켜짐) 필요한 묶음·관련 적은 자료 목록 검사 */
        private List<String> blueprintProblems(BlueprintDto b) {
            List<String> problems = new ArrayList<>(BlueprintService.problems(b, false));
            if (e6) problems.addAll(BlueprintService.materialProblems(b, false, form.minutes()));
            return problems;
        }

        private List<String> earlyReview(BlueprintDto b) {
            try {
                return new TaskReviewService(calls).blueprintProblems(b);
            } catch (RuntimeException e) {
                return List.of(); // 이 점검이 실패해도 과제를 버리지 않는다(관문이 다시 본다)
            }
        }

        private DevWritingDto write(String feedback) {
            DevWritingDto w = calls.call(Stage.WRITING, TaskPromptService.WRITING_DEV, TaskWritingService.input(bp, false, form.minutes(), feedback), DevWritingDto.class);
            return new DevWritingDto(nz(w.description()), nz(w.requirements()), w.notes() == null ? "" : w.notes(), w.taskFollowUp() == null ? "" : w.taskFollowUp());
        }

        private DevCodeDto writeCode(String feedback) {
            String user = StageCallService.json(bp) + (feedback.isEmpty() ? "" : "\n\n" + feedback);
            DevCodeDto c = calls.call(Stage.CODE, TaskPromptService.code(DevTaskGeneratorService.depth(form.minutes()), e6, form.minutes()), user, DevCodeDto.class);
            return new DevCodeDto(nz(c.files()));
        }

        private DevAnswerDto answer(String feedback) {
            StringBuilder sb = new StringBuilder("설계도:\n").append(StageCallService.json(BlueprintService.withoutFacts(bp)));
            sb.append("\n\n[응시자에게 보이는 글]\n").append(visibleText());
            for (var f : code.files()) sb.append("\n[파일 ").append(f.path()).append("]\n").append(f.content()).append('\n');
            if (!writing.notes().isBlank()) sb.append("\n[파일 NOTES.md]\n").append(writing.notes()).append('\n');
            if (!feedback.isEmpty()) sb.append("\n\n").append(feedback);
            DevAnswerDto a = calls.call(Stage.ANSWER, TaskPromptService.answerDev(form.minutes()), sb.toString(), DevAnswerDto.class);
            // 다른 풀이·오답은 src/ 아래 바뀐 파일만 쓴다(NOTES.md 같은 다른 파일이 섞이면 경로 오류가 난다)
            List<DevDraftDto.AltSolution> alts = nz(a.altSolutions()).stream().filter(x -> x != null)
                    .map(x -> new DevDraftDto.AltSolution(x.note(), srcOnly(x.files()))).toList();
            List<DevDraftDto.WrongFix> wrongs = nz(a.wrongFixes()).stream().filter(x -> x != null)
                    .map(x -> new DevDraftDto.WrongFix(x.reason(), srcOnly(x.files()))).toList();
            return new DevAnswerDto(a.answer(), nz(a.hiddenTests()), nz(a.solution()), alts, wrongs);
        }

        private String visibleText() {
            if (writing == null) return "";
            return String.join("\n", writing.description()) + "\n" + String.join("\n", writing.requirements()) + "\n" + writing.notes();
        }

        private List<GenCheckResultDto> preChecks(DevDraftDto draft) {
            List<String> problems = blueprintProblems(bp);
            List<GenCheckResultDto> out = new ArrayList<>();
            out.add(problems.isEmpty() ? new GenCheckResultDto(BLUEPRINT_CHECK, true, "설계도에 결함·판단 지점·사실·예가 모두 있습니다.")
                    : new GenCheckResultDto(BLUEPRINT_CHECK, false, String.join(" / ", problems)));
            out.add(TaskWritingService.factsCheck(bp, visibleText(), String.join("\n", writing.requirements())));
            out.add(CaseCheckService.dev(bp, answer.hiddenTests()));
            out.add(TaskWritingService.basisCheck(bp, visibleText()));
            java.util.List<String> visible = new ArrayList<>(code.files().stream().map(DevDraftDto.FileDraft::path).toList());
            if (!writing.notes().isBlank()) visible.add("NOTES.md");
            out.add(TaskWritingService.mentionedFilesCheck(String.join("\n", writing.description()) + "\n" + String.join("\n", writing.requirements()), visible));
            out.add(CaseCheckService.stubs(bp, code.files())); // 틀이 없으면 숨은 테스트가 컴파일되지 않으니 이 검사가 먼저 시작 코드 단계를 가리킨다
            out.add(CaseCheckService.addedCalled(bp, answer.hiddenTests()));
            java.util.Map<String, String> han = new java.util.LinkedHashMap<>(); // 숨은 글(정답·오답 사유 등)까지 모든 글 칸
            han.put("[설계도]", StageCallService.json(bp));
            han.put("[작성]", StageCallService.json(writing));
            han.put("[코드]", StageCallService.json(code));
            han.put("[정답]", StageCallService.json(answer));
            if (e6 && filler != null) han.put("[관련 적은 자료]", StageCallService.json(filler));
            out.add(TaskWritingService.hanCheck(han));
            out.add(FeatureCheckService.check(bp, String.join("\n", writing.description()) + "\n" + String.join("\n", writing.requirements()), code.files()));
            if (e6) { // E6·M6: 필요한 묶음은 한도의 70% 이하, 자료 전체는 한도의 2배 이상
                var mats = materials(draft);
                out.add(MaterialGateService.needed(bp, mats, limits));
                out.add(MaterialGateService.total(bp, mats, limits));
            }
            return out;
        }

        @Override
        public BlueprintDto blueprint() {
            return bp;
        }

        @Override
        public List<GenCheckResultDto> checks() {
            return checks;
        }

        @Override
        public List<StageUsageDto> usage() {
            return calls.usage();
        }

        @Override
        public List<String> redone() {
            return redone;
        }
    }

    private static List<DevDraftDto.FileDraft> srcOnly(List<DevDraftDto.FileDraft> files) {
        return nz(files).stream().filter(f -> f != null && f.path() != null && f.path().startsWith("src/")).toList();
    }

    private static <T> List<T> nz(List<T> l) {
        return l == null ? List.of() : l;
    }
}
