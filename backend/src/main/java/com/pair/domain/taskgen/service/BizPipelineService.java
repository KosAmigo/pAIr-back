package com.pair.domain.taskgen.service;

import com.pair.domain.taskgen.dto.BizAnswerDto;
import com.pair.domain.taskgen.dto.BizDraftDto;
import com.pair.domain.taskgen.dto.BizQuestionsDto;
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
 * 비개발(BIZ) 과제 단계별 생성: 설계도(숫자표 포함) → 작성(결함을 모름, CSV 는 숫자표에서 코드가 만듦) → 문항 글(결함을 모름) → 정답 쪽(정답 키만) → 조립.
 * 숫자표 대조(M1)는 코드가 한다. 실패한 검사를 낸 단계만 다시 돌린다(설계 5장).
 */
@Component
public class BizPipelineService implements TaskPipelineService<BizDraftDto> {

    private static final Set<Stage> ALL = EnumSet.of(Stage.BLUEPRINT, Stage.WRITING, Stage.QUESTIONS, Stage.ANSWER);

    private final TaskGenAiService ai;
    private final TaskGenProperties props;

    public BizPipelineService(TaskGenAiService ai, TaskGenProperties props) {
        this.ai = ai;
        this.props = props;
    }

    @Override
    public Run<BizDraftDto> start(TaskGenRequest form) {
        return new BizRun(form, new StageCallService(ai, props, false));
    }

    /** 검사 이름 → 다시 돌릴 단계. 모르면 빈 집합 */
    static Set<Stage> owners(GenCheckResultDto c) {
        Set<Stage> gate = StageRouteService.gateOwners(c, true);
        if (gate != null) return gate;
        String n = c.name(), d = c.detail() == null ? "" : c.detail();
        if (n.equals(TaskPipelineService.BLUEPRINT_SENTENCE_CHECK)) return EnumSet.of(Stage.BLUEPRINT);
        if (n.equals(BizCheckService.SOLVE)) return EnumSet.of(Stage.WRITING); // 함정 값이 아닌 답이 나왔다 = 자료만으로 정답이 안 정해짐
        if (n.equals(BizCheckService.CALC) || n.equals(BizCheckService.SOURCES) || n.equals(BizCheckService.WRONG_ANSWERS)
                || n.equals(BizCheckService.HIDDEN_FLAW) || n.equals(BizCheckService.TRAP_QUESTION)) return EnumSet.of(Stage.ANSWER);
        if (n.equals(TaskPipelineService.MENTION_CHECK) || n.equals(TaskPipelineService.FILLER_CHECK)) return EnumSet.of(Stage.WRITING);
        if (n.equals(BizCheckService.JUDGMENT) && d.contains("가리킵니다")) return EnumSet.of(Stage.BLUEPRINT); // 선택지 근거 사실이 자료가 있다고만 말함: 설계도가 쓴 문장
        if (n.equals(BizCheckService.FOLLOW_UP) || n.equals(TaskPipelineService.DUPLICATE_CHECK) || n.equals(TaskPipelineService.META_CHECK)) return EnumSet.of(Stage.WRITING);
        if (n.equals(TaskPipelineService.HAN_CHECK)) {
            Set<Stage> s = EnumSet.noneOf(Stage.class);
            if (d.contains("[설계도]")) s.add(Stage.BLUEPRINT);
            if (d.contains("[작성]")) s.add(Stage.WRITING);
            if (d.contains("[문항]")) s.add(Stage.QUESTIONS);
            if (d.contains("[정답]")) s.add(Stage.ANSWER);
            return s;
        }
        if (n.equals(BizCheckService.TRAP) || n.equals(BizCheckService.LEAK) || n.equals(BizCheckService.JUDGMENT) || n.equals(TaskPipelineService.FACTS_CHECK)
                || n.equals(TaskPipelineService.BASIS_CHECK) || n.equals(BizNumbersService.NAME)) return EnumSet.of(Stage.WRITING);
        if (n.equals(BizQuestionService.CHECK) || n.equals(TaskPipelineService.CHOICE_CHECK)) return EnumSet.of(Stage.QUESTIONS);
        if (n.equals(TaskPipelineService.SOURCE_PATH_CHECK)) return EnumSet.of(Stage.ANSWER);
        if (n.equals(BizCheckService.FORMAT)) {
            if (d.contains("설명(description)") || d.contains("요구사항(requirements)") || d.contains("문서") || d.contains("PDF") || d.contains("쪽") || d.contains("후속")) return EnumSet.of(Stage.WRITING);
            if (d.contains("객관식") || d.contains("보기") || d.contains("문항")) return EnumSet.of(Stage.QUESTIONS, Stage.ANSWER);
            if (d.contains("체크리스트") || d.contains("단답형") || d.contains("계산식")) return EnumSet.of(Stage.ANSWER);
        }
        return EnumSet.noneOf(Stage.class); // CSV, 결론(CONCLUSION), 설계도 문제, 모르는 검사: 설계도부터
    }

    private static final class BizRun implements Run<BizDraftDto> {
        private final TaskGenRequest form;
        private final StageCallService calls;
        private BlueprintDto bp;
        private BizWritingDto writing;
        private BizQuestionsDto questions;
        private BizAnswerDto answer;
        private List<GenCheckResultDto> checks = List.of();
        private List<String> redone = List.of();
        private List<GenCheckResultDto> previous = List.of(); // 바로 앞 다시 만들기 때 실패한 검사

        BizRun(TaskGenRequest form, StageCallService calls) {
            this.form = form;
            this.calls = calls;
        }

        @Override
        public BizDraftDto create(Consumer<String> onStep) {
            return execute(null, onStep);
        }

        private StageRouteService.Plan plan(List<GenCheckResultDto> failed) {
            return StageRouteService.plan(routed(failed), BizPipelineService::owners, ALL, EnumSet.of(Stage.WRITING));
        }

        private List<GenCheckResultDto> routed(List<GenCheckResultDto> failed) {
            return failed.stream().map(c -> StageRouteService.reroute(bp, c, previous)).toList();
        }

        @Override
        public Set<String> kinds(List<GenCheckResultDto> failed) {
            return StageRouteService.kinds(routed(failed), BizPipelineService::owners);
        }

        @Override
        public boolean restartsFrom(List<GenCheckResultDto> failed) {
            return plan(failed).fromBlueprint();
        }

        @Override
        public BizDraftDto redo(List<GenCheckResultDto> failed, Consumer<String> onStep) {
            StageRouteService.Plan plan = plan(failed);
            redone = plan.stages().stream().map(Enum::name).toList();
            previous = failed;
            return execute(plan, onStep);
        }

        private BizDraftDto execute(StageRouteService.Plan plan, Consumer<String> onStep) {
            Set<Stage> st = plan == null ? ALL : plan.stages();
            boolean restart = plan == null || plan.fromBlueprint();
            if (st.contains(Stage.BLUEPRINT)) {
                onStep.accept(STEP_BLUEPRINT);
                bp = blueprint(plan == null ? "" : StageRouteService.feedback(plan.all()));
            }
            if (st.contains(Stage.WRITING)) {
                onStep.accept(STEP_WRITING);
                writing = write(restart ? "" : TaskWritingService.writerFeedback(plan.byStage().get(Stage.WRITING), visibleText()));
            }
            if (st.contains(Stage.QUESTIONS)) {
                onStep.accept(STEP_QUESTIONS);
                questions = questions(restart ? "" : TaskWritingService.writerFeedback(plan.byStage().get(Stage.QUESTIONS), questionsText()));
            }
            if (st.contains(Stage.ANSWER)) {
                onStep.accept(STEP_ANSWER);
                answer = answer(restart ? "" : StageRouteService.feedback(plan.byStage().get(Stage.ANSWER)));
            }
            List<BizDraftDto.QuestionDraft> assembled = BizQuestionService.assemble(bp, questions, answer);
            checks = preChecks(assembled);
            return TaskAssemblyService.biz(form, bp, writing, assembled, answer);
        }

        private BlueprintDto blueprint(String feedback) {
            String system = TaskPromptService.blueprint(true, form.minutes(), BizTaskGeneratorService.depth(form.minutes()));
            String user = "제한 시간: " + form.minutes() + "분\n주제: " + form.topic() + "\n함정: " + form.trap() + (feedback.isEmpty() ? "" : "\n\n" + feedback);
            BlueprintDto b = BlueprintService.normalized(calls.call(Stage.BLUEPRINT, system, user, BlueprintDto.class), true);
            List<String> problems = BlueprintService.problems(b, true);
            if (problems.isEmpty()) problems = earlyReview(b); // 코드 검사를 통과하면 글에 들어갈 문장만 싸게 한 번 더 본다
            for (int retry = 0; !problems.isEmpty() && retry < 2; retry++) { // 코드 검사에 걸리면 최대 2번, 다시 받은 것도 검사한다
                b = BlueprintService.normalized(calls.call(Stage.BLUEPRINT, system,
                        user + "\n\n설계도가 아래 문제로 걸렸습니다. 고쳐서 다시 내세요:\n- " + String.join("\n- ", problems), BlueprintDto.class), true);
                problems = BlueprintService.problems(b, true);
            }
            return b;
        }

        private List<String> earlyReview(BlueprintDto b) {
            try {
                return new TaskReviewService(calls).blueprintProblems(b);
            } catch (RuntimeException e) {
                return List.of(); // 이 점검이 실패해도 과제를 버리지 않는다(관문이 다시 본다)
            }
        }

        private BizWritingDto write(String feedback) {
            BizWritingDto w = calls.call(Stage.WRITING, TaskPromptService.writingBiz(form.minutes()), TaskWritingService.input(bp, true, form.minutes(), feedback), BizWritingDto.class);
            return new BizWritingDto(nz(w.description()), nz(w.requirements()),
                    nz(w.documents()).stream().map(d -> new BizDraftDto.DocDraft(d.path(), nz(d.pages()))).collect(java.util.stream.Collectors.toMap(
                            BizDraftDto.DocDraft::path, d -> d, (a, b) -> a, java.util.LinkedHashMap::new)).values().stream().toList(), // 같은 경로 문서는 첫 번째만
                    w.taskFollowUp() == null ? "" : w.taskFollowUp());
        }

        private BizQuestionsDto questions(String feedback) {
            BizQuestionsDto q = calls.call(Stage.QUESTIONS, TaskPromptService.QUESTIONS_BIZ, BizQuestionService.input(bp, visibleText(), feedback), BizQuestionsDto.class);
            return new BizQuestionsDto(nz(q.questions()));
        }

        private BizAnswerDto answer(String feedback) {
            StringBuilder sb = new StringBuilder("설계도:\n").append(StageCallService.json(BlueprintService.withoutFacts(bp)));
            sb.append("\n\n[응시자에게 보이는 글]\n").append(String.join("\n", writing.description())).append('\n').append(String.join("\n", writing.requirements()));
            for (var d : writing.documents()) {
                sb.append("\n[문서 ").append(d.path()).append("]\n");
                for (int i = 0; i < d.pages().size(); i++) sb.append("(").append(i + 1).append("쪽) ").append(d.pages().get(i)).append('\n');
            }
            for (var f : BizNumbersService.csvFiles(bp)) sb.append("\n[CSV ").append(f.path()).append("]\n").append(f.content());
            sb.append("\n\n[문항 글]\n").append(questionsText());
            if (!feedback.isEmpty()) sb.append("\n\n").append(feedback);
            BizAnswerDto a = calls.call(Stage.ANSWER, TaskPromptService.answerBiz(BizTaskGeneratorService.depth(form.minutes())), sb.toString(), BizAnswerDto.class);
            return new BizAnswerDto(a.checklist(), nz(a.keys()));
        }

        private String questionsText() {
            if (questions == null) return "";
            StringBuilder sb = new StringBuilder();
            for (var q : nz(questions.questions())) {
                sb.append(q.id()).append(". ").append(q.content()).append('\n');
                nz(q.choices()).forEach(c -> sb.append("  - ").append(c).append('\n'));
            }
            return sb.toString();
        }

        private String visibleText() {
            if (writing == null) return "";
            StringBuilder sb = new StringBuilder(String.join("\n", writing.description())).append('\n').append(String.join("\n", writing.requirements()));
            writing.documents().forEach(d -> d.pages().forEach(p -> sb.append('\n').append(p)));
            return sb.toString();
        }

        private List<GenCheckResultDto> preChecks(List<BizDraftDto.QuestionDraft> assembled) {
            List<String> problems = BlueprintService.problems(bp, true);
            List<GenCheckResultDto> out = new ArrayList<>();
            out.add(problems.isEmpty() ? new GenCheckResultDto(BLUEPRINT_CHECK, true, "설계도와 숫자표가 정합합니다.")
                    : new GenCheckResultDto(BLUEPRINT_CHECK, false, String.join(" / ", problems)));
            out.add(TaskWritingService.factsCheck(bp, visibleText(), ""));
            out.add(TaskWritingService.basisCheck(bp, visibleText()));
            List<String> pages = new ArrayList<>();
            writing.documents().forEach(doc -> pages.addAll(doc.pages()));
            out.add(TaskWritingService.duplicateCheck(pages, String.join("\n", writing.description()) + "\n" + String.join("\n", writing.requirements()), bp));
            StringBuilder docText = new StringBuilder();
            writing.documents().forEach(d -> d.pages().forEach(p -> docText.append(p).append('\n')));
            out.add(TaskWritingService.metaCheck(docText.toString()));
            out.add(TaskWritingService.fillerCheck(docText.toString()));
            java.util.List<String> visible = new ArrayList<>();
            writing.documents().forEach(doc -> visible.add(doc.path()));
            BizNumbersService.csvFiles(bp).forEach(f -> visible.add(f.path()));
            out.add(TaskWritingService.mentionedFilesCheck(String.join("\n", writing.description()) + "\n" + String.join("\n", writing.requirements()), visible));
            java.util.Map<String, String> han = new java.util.LinkedHashMap<>();
            han.put("[설계도]", StageCallService.json(bp)); // 숨은 글(정답 키 사유 등)까지 모든 글 칸
            han.put("[작성]", StageCallService.json(writing));
            han.put("[문항]", StageCallService.json(questions));
            han.put("[정답]", StageCallService.json(answer));
            out.add(TaskWritingService.hanCheck(han));
            out.add(BizNumbersService.documentsCheck(bp, writing.documents()));
            out.add(BizQuestionService.check(bp, questions));
            out.add(AnswerChoiceService.check(bp, assembled));
            List<String> badSources = TaskAssemblyService.bizSourceProblems(bp, writing, assembled);
            out.add(badSources.isEmpty() ? new GenCheckResultDto(SOURCE_PATH_CHECK, true, "문항 근거 경로가 모두 실제 자료입니다.")
                    : new GenCheckResultDto(SOURCE_PATH_CHECK, false, String.join(" / ", badSources)));
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

    private static <T> List<T> nz(List<T> l) {
        return l == null ? List.of() : l;
    }
}
