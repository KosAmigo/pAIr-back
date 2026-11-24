package com.pair.domain.taskgen.service;

import com.pair.domain.taskgen.dto.BlueprintDto;
import com.pair.domain.taskgen.dto.DevAnswerDto;
import com.pair.domain.taskgen.dto.DevCodeDto;
import com.pair.domain.taskgen.dto.DevDraftDto;
import com.pair.domain.taskgen.dto.DevWritingDto;
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

    private final TaskGenAiService ai;
    private final TaskGenProperties props;

    public DevPipelineService(TaskGenAiService ai, TaskGenProperties props) {
        this.ai = ai;
        this.props = props;
    }

    @Override
    public Run<DevDraftDto> start(TaskGenRequest form) {
        return new DevRun(form, new StageCallService(ai, props));
    }

    /** 검사 이름 → 다시 돌릴 단계. 모르면 빈 집합 */
    static Set<Stage> owners(GenCheckResultDto c, List<String> codePaths) {
        String n = c.name(), d = c.detail() == null ? "" : c.detail();
        if (n.equals(DevCheckService.NO_HINT) || n.equals(DevCheckService.START_PASSES)) return EnumSet.of(Stage.CODE);
        if (n.equals(DevCheckService.TRAP_WORDS)) {
            boolean code = d.contains("파일 경로") || d.contains("코드 주석");
            boolean text = d.contains("설명") || d.contains("요구사항") || d.contains("메모");
            return code && text ? EnumSet.of(Stage.CODE, Stage.WRITING) : code ? EnumSet.of(Stage.CODE) : EnumSet.of(Stage.WRITING);
        }
        if (n.equals(DevCheckService.HIDDEN_CATCHES) || n.equals(DevCheckService.SOLVED_PASSES) || n.equals(DevCheckService.WRONG_FIXES)
                || n.equals(DevCheckService.TIME) || n.equals(DevCheckService.FOLLOW_UP) || n.equals(DevCheckService.ALT_SOLUTIONS)
                || n.equals(DevCheckService.HIDDEN_FLAW)) return EnumSet.of(Stage.ANSWER);
        if (n.equals(DevCheckService.MATERIALS) || n.equals(DevCheckService.JUDGMENT) || n.equals(AnswerLeakJudgeService.NAME)
                || n.equals(AnswerLeakJudgeService.NUMBERS) || n.equals(TaskPipelineService.FACTS_CHECK) || n.equals(TaskPipelineService.RULES_CHECK)) return EnumSet.of(Stage.WRITING);
        if (n.equals(TaskPipelineService.PATHS_CHECK)) return d.contains("(시작 코드)") ? EnumSet.of(Stage.CODE) : EnumSet.of(Stage.ANSWER);
        if (n.equals(TaskPipelineService.BLUEPRINT_CHECK)) return EnumSet.of(Stage.BLUEPRINT);
        if (n.equals(DevCheckService.FORMAT)) {
            if (d.contains("정답 설명") || d.contains("숨은 테스트") || d.contains("정답 코드")) return EnumSet.of(Stage.ANSWER);
            if (d.contains("설명(description)") || d.contains("요구사항(requirements)")) return EnumSet.of(Stage.WRITING);
            if (d.contains("시작 코드") || d.contains("공개 테스트") || d.contains("같은 경로") || codePaths.stream().anyMatch(d::contains)) return EnumSet.of(Stage.CODE);
            if (d.contains("경로")) return EnumSet.of(Stage.ANSWER);
        }
        return EnumSet.noneOf(Stage.class);
    }

    private static final class DevRun implements Run<DevDraftDto> {
        private final TaskGenRequest form;
        private final StageCallService calls;
        private BlueprintDto bp;
        private DevWritingDto writing;
        private DevCodeDto code;
        private DevAnswerDto answer;
        private List<GenCheckResultDto> checks = List.of();
        private List<String> redone = List.of();

        DevRun(TaskGenRequest form, StageCallService calls) {
            this.form = form;
            this.calls = calls;
        }

        @Override
        public DevDraftDto create(Consumer<String> onStep) {
            return execute(null, onStep);
        }

        @Override
        public DevDraftDto redo(List<GenCheckResultDto> failed, Consumer<String> onStep) {
            List<String> paths = code == null || code.files() == null ? List.of() : code.files().stream().map(DevDraftDto.FileDraft::path).toList();
            StageRouteService.Plan plan = StageRouteService.plan(failed, c -> owners(c, paths), true);
            redone = plan.stages().stream().map(Enum::name).toList();
            return execute(plan, onStep);
        }

        private DevDraftDto execute(StageRouteService.Plan plan, Consumer<String> onStep) {
            Set<Stage> st = plan == null ? EnumSet.allOf(Stage.class) : plan.stages();
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
            onStep.accept(STEP_ANSWER);
            answer = answer(restart ? "" : StageRouteService.feedback(plan.byStage().get(Stage.ANSWER)));
            checks = preChecks();
            return TaskAssemblyService.dev(form, bp, writing, code, answer);
        }

        private BlueprintDto blueprint(String feedback) {
            String system = TaskPromptService.blueprint(false, form.minutes(), DevTaskGeneratorService.depth(form.minutes()));
            String user = "제한 시간: " + form.minutes() + "분\n주제: " + form.topic() + "\n함정: " + form.trap() + (feedback.isEmpty() ? "" : "\n\n" + feedback);
            BlueprintDto b = BlueprintService.normalized(calls.call(Stage.BLUEPRINT, system, user, BlueprintDto.class), false);
            List<String> problems = BlueprintService.problems(b, false);
            if (problems.isEmpty()) return b;
            // 설계도가 모자라면 뒤 단계를 돌리기 전에 설계도만 한 번 더 받는다
            return BlueprintService.normalized(calls.call(Stage.BLUEPRINT, system,
                    user + "\n\n설계도가 아래 문제로 걸렸습니다. 고쳐서 다시 내세요:\n- " + String.join("\n- ", problems), BlueprintDto.class), false);
        }

        private DevWritingDto write(String feedback) {
            DevWritingDto w = calls.call(Stage.WRITING, TaskPromptService.WRITING_DEV, TaskWritingService.input(bp, false, form.minutes(), feedback), DevWritingDto.class);
            return new DevWritingDto(nz(w.description()), nz(w.requirements()), w.notes() == null ? "" : w.notes());
        }

        private DevCodeDto writeCode(String feedback) {
            String user = StageCallService.json(bp) + (feedback.isEmpty() ? "" : "\n\n" + feedback);
            DevCodeDto c = calls.call(Stage.CODE, TaskPromptService.code(DevTaskGeneratorService.depth(form.minutes())), user, DevCodeDto.class);
            return new DevCodeDto(nz(c.files()));
        }

        private DevAnswerDto answer(String feedback) {
            StringBuilder sb = new StringBuilder("설계도:\n").append(StageCallService.json(BlueprintService.withoutFacts(bp)));
            sb.append("\n\n[응시자에게 보이는 글]\n").append(visibleText());
            for (var f : code.files()) sb.append("\n[파일 ").append(f.path()).append("]\n").append(f.content()).append('\n');
            if (!writing.notes().isBlank()) sb.append("\n[파일 NOTES.md]\n").append(writing.notes()).append('\n');
            if (!feedback.isEmpty()) sb.append("\n\n").append(feedback);
            DevAnswerDto a = calls.call(Stage.ANSWER, TaskPromptService.answerDev(form.minutes()), sb.toString(), DevAnswerDto.class);
            return new DevAnswerDto(a.answer(), nz(a.hiddenTests()), nz(a.solution()), nz(a.altSolutions()), nz(a.wrongFixes()), a.taskFollowUp());
        }

        private String visibleText() {
            if (writing == null) return "";
            return String.join("\n", writing.description()) + "\n" + String.join("\n", writing.requirements()) + "\n" + writing.notes();
        }

        private List<GenCheckResultDto> preChecks() {
            List<String> problems = BlueprintService.problems(bp, false);
            List<GenCheckResultDto> out = new ArrayList<>();
            out.add(problems.isEmpty() ? new GenCheckResultDto(BLUEPRINT_CHECK, true, "설계도에 결함·판단 지점·사실·예가 모두 있습니다.")
                    : new GenCheckResultDto(BLUEPRINT_CHECK, false, String.join(" / ", problems)));
            out.add(TaskWritingService.factsCheck(bp, visibleText(), String.join("\n", writing.requirements())));
            out.add(TaskWritingService.rulesCheck(bp, visibleText()));
            List<String> dups = TaskAssemblyService.devPathProblems(code, answer);
            out.add(dups.isEmpty() ? new GenCheckResultDto(PATHS_CHECK, true, "파일 경로가 겹치지 않습니다.") : new GenCheckResultDto(PATHS_CHECK, false, String.join(" / ", dups)));
            return out;
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

    private static <T> List<T> nz(List<T> l) {
        return l == null ? List.of() : l;
    }
}
