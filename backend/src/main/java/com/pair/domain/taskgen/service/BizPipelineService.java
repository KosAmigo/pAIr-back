package com.pair.domain.taskgen.service;

import com.pair.domain.taskgen.dto.BizAnswerDto;
import com.pair.domain.taskgen.dto.BizDraftDto;
import com.pair.domain.taskgen.dto.BizWritingDto;
import com.pair.domain.taskgen.dto.BlueprintDto;
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
 * 비개발(BIZ) 과제 단계별 생성: 설계도(숫자표 포함) → 작성(결함을 모름, CSV 는 숫자표에서 코드가 만듦) → 정답 쪽 → 조립.
 * 숫자표 대조(M1)는 코드가 한다. 실패한 검사를 낸 단계부터 다시 돌린다.
 */
@Component
public class BizPipelineService implements TaskPipelineService<BizDraftDto> {

    private final TaskGenAiService ai;
    private final TaskGenProperties props;

    public BizPipelineService(TaskGenAiService ai, TaskGenProperties props) {
        this.ai = ai;
        this.props = props;
    }

    @Override
    public Run<BizDraftDto> start(TaskGenRequest form) {
        return new BizRun(form, new StageCallService(ai, props));
    }

    /** 검사 이름 → 다시 돌릴 단계. 모르면 빈 집합 */
    static Set<Stage> owners(GenCheckResultDto c) {
        String n = c.name(), d = c.detail() == null ? "" : c.detail();
        if (n.equals(BizCheckService.CALC) || n.equals(BizCheckService.SOURCES) || n.equals(BizCheckService.SOLVE) || n.equals(BizCheckService.WRONG_ANSWERS)
                || n.equals(BizCheckService.FOLLOW_UP) || n.equals(BizCheckService.HIDDEN_FLAW) || n.equals(BizCheckService.TRAP_QUESTION)) return EnumSet.of(Stage.ANSWER);
        if (n.equals(BizCheckService.TRAP) || n.equals(BizCheckService.LEAK) || n.equals(BizCheckService.TRAP_WORDS) || n.equals(BizCheckService.EVIDENCE_STYLE)
                || n.equals(BizCheckService.NO_PROCEDURE) || n.equals(BizCheckService.JUDGMENT) || n.equals(AnswerLeakJudgeService.NAME)
                || n.equals(AnswerLeakJudgeService.NUMBERS) || n.equals(TaskPipelineService.FACTS_CHECK) || n.equals(BizNumbersService.NAME) || n.equals(TaskPipelineService.PATHS_CHECK) || n.equals(TaskPipelineService.RULES_CHECK)) return EnumSet.of(Stage.WRITING);
        if (n.equals(TaskPipelineService.CHOICE_CHECK)) return EnumSet.of(Stage.ANSWER);
        if (n.equals(BizCheckService.FORMAT)) {
            if (d.contains("설명(description)") || d.contains("요구사항(requirements)") || d.contains("문서") || d.contains("PDF") || d.contains("쪽")) return EnumSet.of(Stage.WRITING);
            if (d.contains("후속") || d.contains("체크리스트") || d.contains("문항") || d.contains("객관식") || d.contains("단답형") || d.contains("계산식")) return EnumSet.of(Stage.ANSWER);
        }
        return EnumSet.noneOf(Stage.class); // CSV, 결론(CONCLUSION), 설계도 문제, 모르는 검사: 설계도부터
    }

    private static final class BizRun implements Run<BizDraftDto> {
        private final TaskGenRequest form;
        private final StageCallService calls;
        private BlueprintDto bp;
        private BizWritingDto writing;
        private BizAnswerDto answer;
        private List<GenCheckResultDto> checks = List.of();
        private List<String> redone = List.of();

        BizRun(TaskGenRequest form, StageCallService calls) {
            this.form = form;
            this.calls = calls;
        }

        @Override
        public BizDraftDto create(Consumer<String> onStep) {
            return execute(null, onStep);
        }

        @Override
        public BizDraftDto redo(List<GenCheckResultDto> failed, Consumer<String> onStep) {
            StageRouteService.Plan plan = StageRouteService.plan(failed, BizPipelineService::owners, false);
            redone = plan.stages().stream().map(Enum::name).toList();
            return execute(plan, onStep);
        }

        private BizDraftDto execute(StageRouteService.Plan plan, Consumer<String> onStep) {
            Set<Stage> st = plan == null ? EnumSet.of(Stage.BLUEPRINT, Stage.WRITING, Stage.ANSWER) : plan.stages();
            boolean restart = plan == null || plan.fromBlueprint();
            if (st.contains(Stage.BLUEPRINT)) {
                onStep.accept(STEP_BLUEPRINT);
                bp = blueprint(plan == null ? "" : StageRouteService.feedback(plan.all()));
            }
            if (st.contains(Stage.WRITING)) {
                onStep.accept(STEP_WRITING);
                writing = write(restart ? "" : TaskWritingService.writerFeedback(plan.byStage().get(Stage.WRITING), visibleText()));
            }
            onStep.accept(STEP_ANSWER);
            answer = answer(restart ? "" : StageRouteService.feedback(plan.byStage().get(Stage.ANSWER)));
            checks = preChecks();
            return TaskAssemblyService.biz(form, bp, writing, answer);
        }

        private BlueprintDto blueprint(String feedback) {
            String system = TaskPromptService.blueprint(true, form.minutes(), BizTaskGeneratorService.depth(form.minutes()));
            String user = "제한 시간: " + form.minutes() + "분\n주제: " + form.topic() + "\n함정: " + form.trap() + (feedback.isEmpty() ? "" : "\n\n" + feedback);
            BlueprintDto b = BlueprintService.normalized(calls.call(Stage.BLUEPRINT, system, user, BlueprintDto.class), true);
            List<String> problems = BlueprintService.problems(b, true);
            if (problems.isEmpty()) return b;
            return BlueprintService.normalized(calls.call(Stage.BLUEPRINT, system,
                    user + "\n\n설계도가 아래 문제로 걸렸습니다. 고쳐서 다시 내세요:\n- " + String.join("\n- ", problems), BlueprintDto.class), true);
        }

        private BizWritingDto write(String feedback) {
            BizWritingDto w = calls.call(Stage.WRITING, TaskPromptService.writingBiz(form.minutes()), TaskWritingService.input(bp, true, form.minutes(), feedback), BizWritingDto.class);
            return new BizWritingDto(nz(w.description()), nz(w.requirements()),
                    nz(w.documents()).stream().map(d -> new BizDraftDto.DocDraft(d.path(), nz(d.pages()))).collect(java.util.stream.Collectors.toMap(
                            BizDraftDto.DocDraft::path, d -> d, (a, b) -> a, java.util.LinkedHashMap::new)).values().stream().toList()); // 같은 경로 문서는 첫 번째만
        }

        private BizAnswerDto answer(String feedback) {
            StringBuilder sb = new StringBuilder("설계도:\n").append(StageCallService.json(BlueprintService.withoutFacts(bp)));
            sb.append("\n\n[응시자에게 보이는 글]\n").append(String.join("\n", writing.description())).append('\n').append(String.join("\n", writing.requirements()));
            for (var d : writing.documents()) {
                sb.append("\n[문서 ").append(d.path()).append("]\n");
                for (int i = 0; i < d.pages().size(); i++) sb.append("(").append(i + 1).append("쪽) ").append(d.pages().get(i)).append('\n');
            }
            for (var f : BizNumbersService.csvFiles(bp)) sb.append("\n[CSV ").append(f.path()).append("]\n").append(f.content());
            if (!feedback.isEmpty()) sb.append("\n\n").append(feedback);
            BizAnswerDto a = calls.call(Stage.ANSWER, TaskPromptService.answerBiz(BizTaskGeneratorService.depth(form.minutes())), sb.toString(), BizAnswerDto.class);
            return new BizAnswerDto(a.checklist(), nz(a.questions()), a.taskFollowUp());
        }

        private String visibleText() {
            if (writing == null) return "";
            StringBuilder sb = new StringBuilder(String.join("\n", writing.description())).append('\n').append(String.join("\n", writing.requirements()));
            writing.documents().forEach(d -> d.pages().forEach(p -> sb.append('\n').append(p)));
            return sb.toString();
        }

        private List<GenCheckResultDto> preChecks() {
            List<String> problems = BlueprintService.problems(bp, true);
            List<GenCheckResultDto> out = new ArrayList<>();
            out.add(problems.isEmpty() ? new GenCheckResultDto(BLUEPRINT_CHECK, true, "설계도와 숫자표가 정합합니다.")
                    : new GenCheckResultDto(BLUEPRINT_CHECK, false, String.join(" / ", problems)));
            out.add(TaskWritingService.factsCheck(bp, visibleText(), ""));
            out.add(BizNumbersService.documentsCheck(bp, writing.documents()));
            out.add(TaskWritingService.rulesCheck(bp, visibleText()));
            out.add(AnswerChoiceService.check(bp, answer.questions()));
            List<String> dups = TaskAssemblyService.bizPathProblems(bp, writing);
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
