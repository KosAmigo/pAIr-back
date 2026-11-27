package com.pair.domain.taskgen.service;

import com.pair.domain.taskgen.dto.BizAnswerDto;
import com.pair.domain.taskgen.dto.BizDraftDto;
import com.pair.domain.taskgen.dto.BizQuestionsDto;
import com.pair.domain.taskgen.dto.BizWritingDto;
import com.pair.domain.task.dto.AiLimitsDto;
import com.pair.domain.taskgen.dto.BlueprintDto;
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
 * 비개발(BIZ) 과제 단계별 생성: 설계도(숫자표 포함) → 작성(결함을 모름, CSV 는 숫자표에서 코드가 만듦) → 문항 글(결함을 모름) → 정답 쪽(정답 키만) → 조립.
 * 숫자표 대조(M1)는 코드가 한다. 실패한 검사를 낸 단계만 다시 돌린다(설계 5장).
 */
@Component
public class BizPipelineService implements TaskPipelineService<BizDraftDto> {

    private static final Set<Stage> ALL = EnumSet.of(Stage.BLUEPRINT, Stage.WRITING, Stage.QUESTIONS, Stage.ANSWER);
    /** E6·M6(materialGate) 를 켜면 정답 쪽 뒤에 단계 4' 관련 적은 자료가 더 돈다 */
    private static final Set<Stage> ALL_E6 = EnumSet.of(Stage.BLUEPRINT, Stage.WRITING, Stage.QUESTIONS, Stage.ANSWER, Stage.FILLER);

    private final TaskGenAiService ai;
    private final TaskGenProperties props;

    public BizPipelineService(TaskGenAiService ai, TaskGenProperties props) {
        this.ai = ai;
        this.props = props;
    }

    @Override
    public Run<BizDraftDto> start(TaskGenRequest form) {
        return new BizRun(form, new StageCallService(ai, props, false), props.materialGate());
    }

    /** 검사 이름 → 다시 돌릴 단계. 모르면 빈 집합 */
    static Set<Stage> owners(GenCheckResultDto c) {
        Set<Stage> gate = StageRouteService.gateOwners(c, true);
        if (gate != null) return gate;
        String n = c.name(), d = c.detail() == null ? "" : c.detail();
        if (n.equals(TaskPipelineService.BLUEPRINT_SENTENCE_CHECK)) return EnumSet.of(Stage.BLUEPRINT);
        if (n.equals(TaskPipelineService.M6_TOTAL_CHECK)) return EnumSet.of(Stage.FILLER); // 자료 전체가 모자람: 관련 적은 자료를 다시(M6)
        if (n.equals(TaskPipelineService.M6_NEEDED_CHECK)) return EnumSet.of(Stage.BLUEPRINT); // 필요한 묶음이 큼: 설계도를 다시(M6)
        if (n.equals(BizCheckService.SOLVE)) return EnumSet.of(Stage.WRITING); // 함정 값이 아닌 답이 나왔다 = 자료만으로 정답이 안 정해짐
        if (n.equals(BizCheckService.TRAP_QUESTION) && d.contains(BizCheckService.CELL_SAME)) return EnumSet.of(Stage.BLUEPRINT); // 정답이 칸 값 그대로: 정의가 함정을 없앤 것
        if (n.equals(BizCheckService.CALC) || n.equals(BizCheckService.SOURCES) || n.equals(BizCheckService.WRONG_ANSWERS)
                || n.equals(BizCheckService.HIDDEN_FLAW) || n.equals(BizCheckService.TRAP_QUESTION)) return EnumSet.of(Stage.ANSWER);
        if (n.equals(TaskPipelineService.MENTION_CHECK) || n.equals(TaskPipelineService.FILLER_CHECK) || n.equals(TaskPipelineService.GEN_META_CHECK)) return EnumSet.of(Stage.WRITING);
        if (n.equals(TaskPipelineService.FLAW_POINTER_CHECK)) return d.contains("[문항]") && !d.contains("[요청 글]") ? EnumSet.of(Stage.QUESTIONS) : d.contains("[문항]") ? EnumSet.of(Stage.WRITING, Stage.QUESTIONS) : EnumSet.of(Stage.WRITING);
        if (n.equals(BizCheckService.JUDGMENT) && d.contains("가리킵니다")) return EnumSet.of(Stage.BLUEPRINT); // 선택지 근거 사실이 자료가 있다고만 말함: 설계도가 쓴 문장
        if (n.equals(BizCheckService.FOLLOW_UP) || n.equals(TaskPipelineService.DUPLICATE_CHECK) || n.equals(TaskPipelineService.META_CHECK)) return EnumSet.of(Stage.WRITING);
        if (n.equals(TaskPipelineService.HAN_CHECK)) {
            Set<Stage> s = EnumSet.noneOf(Stage.class);
            if (d.contains("[설계도]")) s.add(Stage.BLUEPRINT);
            if (d.contains("[작성]")) s.add(Stage.WRITING);
            if (d.contains("[문항]")) s.add(Stage.QUESTIONS);
            if (d.contains("[정답]")) s.add(Stage.ANSWER);
            if (d.contains("[관련 적은 자료]")) s.add(Stage.FILLER);
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

    /**
     * 응시 화면이 PDF 를 첨부할 때 AI 에게 보내는 글과 같은 모양(TaskMaterialService.attachForAttempt: 쪽마다 "--- n쪽 ---" 머리 + 쪽 글,
     * 쪽 글은 저장된 document_pages 그대로). M6 글자 수를 실제 첨부와 같게 센다(2026-11-26 g7 판정)
     */
    static String attachedText(List<String> pages) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < pages.size(); i++) text.append("--- ").append(i + 1).append("쪽 ---\n").append(pages.get(i)).append('\n');
        return text.toString().strip();
    }

    private static final class BizRun implements Run<BizDraftDto> {
        private final TaskGenRequest form;
        private final StageCallService calls;
        private BlueprintDto bp;
        private BizWritingDto writing;
        private BizQuestionsDto questions;
        private BizAnswerDto answer;
        private FillerDto filler;
        private final boolean e6;
        private final AiLimitsDto limits;
        private List<GenCheckResultDto> checks = List.of();
        private List<String> redone = List.of();
        private final List<List<String>> blueprintRetries = new ArrayList<>(); // 설계도 형식 검사로 다시 받은 이유(report.json details.blueprintRetries)
        private List<GenCheckResultDto> previous = List.of(); // 바로 앞 다시 만들기 때 실패한 검사
        private boolean fillerSent; // 군더더기 반복으로 작성 단계에 되돌린 적이 있는가(한 번만)

        BizRun(TaskGenRequest form, StageCallService calls, boolean e6) {
            this.form = form;
            this.calls = calls;
            this.e6 = e6;
            this.limits = AiLimitsDto.forMinutes(form.minutes());
        }

        @Override
        public BizDraftDto create(Consumer<String> onStep) {
            return execute(null, onStep);
        }

        private StageRouteService.Plan plan(List<GenCheckResultDto> failed) {
            return StageRouteService.plan(routed(failed), BizPipelineService::owners, e6 ? ALL_E6 : ALL, EnumSet.of(Stage.WRITING));
        }

        private List<GenCheckResultDto> routed(List<GenCheckResultDto> failed) {
            return failed.stream().map(c -> StageRouteService.reroute(bp, c, previous)).toList();
        }

        @Override
        public Set<String> kinds(List<GenCheckResultDto> failed) {
            return StageRouteService.kinds(routed(failed), BizPipelineService::owners);
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
        public BizDraftDto resumeFrom(Stage stage, Consumer<String> onStep) {
            StageRouteService.Plan plan = StageRouteService.from(stage, e6 ? ALL_E6 : ALL);
            redone = plan.stages().stream().map(Enum::name).toList();
            return execute(plan, onStep);
        }

        @Override
        public BizDraftDto redo(List<GenCheckResultDto> failed, Consumer<String> onStep) {
            StageRouteService.Plan plan = plan(failed);
            redone = plan.stages().stream().map(Enum::name).toList();
            previous = failed;
            return execute(plan, onStep);
        }

        private BizDraftDto execute(StageRouteService.Plan plan, Consumer<String> onStep) {
            Set<Stage> st = plan == null ? (e6 ? ALL_E6 : ALL) : plan.stages();
            boolean restart = plan == null || plan.fromBlueprint();
            if (st.contains(Stage.BLUEPRINT)) {
                onStep.accept(STEP_BLUEPRINT);
                bp = blueprint(plan == null ? "" : StageRouteService.feedback(plan.all()));
            }
            if (st.contains(Stage.WRITING)) {
                onStep.accept(STEP_WRITING);
                writing = write(restart ? "" : TaskWritingService.writerFeedback(plan.byStage().get(Stage.WRITING), visibleText()));
                List<String> filler = fillerNarration();
                if (!fillerSent && !filler.isEmpty()) { // 같은 꼴의 군더더기 문장이 3개 이상: 작성 단계에 한 번만 되돌린다
                    fillerSent = true;
                    // g8: 그 문장이 든 문서만 다시 쓴다(g7: 10개 모두 문서 전부 + 요청 글까지 다시 써서 작성 비용이 2배)
                    writing = rewriteDocs(TaskWritingService.fillerFeedback(filler), filler);
                }
            }
            if (st.contains(Stage.QUESTIONS)) {
                onStep.accept(STEP_QUESTIONS);
                questions = questions(restart ? "" : TaskWritingService.writerFeedback(plan.byStage().get(Stage.QUESTIONS), questionsText()));
            }
            if (st.contains(Stage.ANSWER)) {
                onStep.accept(STEP_ANSWER);
                answer = answer(restart ? "" : StageRouteService.feedback(plan.byStage().get(Stage.ANSWER)));
            }
            if (e6 && st.contains(Stage.FILLER)) {
                onStep.accept(STEP_FILLER);
                filler = fill(restart ? "" : StageRouteService.feedback(plan.byStage().get(Stage.FILLER)));
            }
            List<BizDraftDto.QuestionDraft> assembled = BizQuestionService.assemble(bp, questions, answer);
            BizDraftDto draft = TaskAssemblyService.biz(form, bp, writing, assembled, answer, filler);
            checks = preChecks(assembled, draft);
            return draft;
        }

        /** E6·M6 단계 4': 결함을 모르는 싼 모델이 관련 적은 문서·CSV 를 쓴다. 자료 전체가 맥락 한도의 2.3배가 되도록 파일당 분량을 알려 준다 */
        private FillerDto fill(String feedback) {
            if (bp.fillers().isEmpty()) return new FillerDto(List.of());
            int neededChars = MaterialGateService.bundleChars(materials(TaskAssemblyService.biz(form, bp, writing, List.of(), answer, null)), bp.neededFiles());
            int per = MaterialGateService.perFillerChars(bp, neededChars, limits);
            FillerDto raw = FillerService.merge(calls.callParallel(Stage.FILLER, TaskPromptService.filler(true), FillerService.inputs(bp, per, feedback), FillerDto.class)); // 파일마다 동시에
            return FillerService.sanitized(raw, bp);
        }

        /** 응시자가 첨부할 수 있는 파일(경로 → 글): 문서(응시 화면 첨부와 같은 글), CSV */
        private static java.util.Map<String, String> materials(BizDraftDto d) {
            java.util.Map<String, String> m = new java.util.LinkedHashMap<>();
            for (var doc : d.documents()) m.put(doc.path(), attachedText(doc.pages()));
            for (var f : d.csvFiles()) m.put(f.path(), f.content() == null ? "" : f.content());
            return m;
        }

        /** 설계도 코드 검사 + (E6·M6 켜짐) 필요한 묶음·관련 적은 자료 목록 검사 */
        private List<String> blueprintProblems(BlueprintDto b) {
            List<String> problems = new ArrayList<>(BlueprintService.problems(b, true));
            if (e6) problems.addAll(BlueprintService.materialProblems(b, true, form.minutes()));
            return problems;
        }

        private BlueprintDto blueprint(String feedback) {
            String system = TaskPromptService.blueprint(true, form.minutes(), TaskPromptService.stageDepth(true, form.minutes(), e6), e6);
            String user = "제한 시간: " + form.minutes() + "분\n주제: " + form.topic() + "\n함정: " + form.trap() + (feedback.isEmpty() ? "" : "\n\n" + feedback);
            BlueprintDto b = BlueprintService.normalized(calls.call(Stage.BLUEPRINT, system, user, com.pair.domain.taskgen.dto.BizBlueprintDto.class).toBlueprint(), true);
            List<String> problems = blueprintProblems(b);
            if (problems.isEmpty()) problems = earlyReview(b); // 코드 검사를 통과하면 글에 들어갈 문장만 싸게 한 번 더 본다
            for (int retry = 0; !problems.isEmpty() && retry < 2; retry++) { // 코드 검사에 걸리면 최대 2번, 다시 받은 것도 검사한다
                blueprintRetries.add(List.copyOf(problems));
                b = BlueprintService.normalized(calls.call(Stage.BLUEPRINT, system,
                        user + "\n\n설계도가 아래 문제로 걸렸습니다. 고쳐서 다시 내세요:\n- " + String.join("\n- ", problems), com.pair.domain.taskgen.dto.BizBlueprintDto.class).toBlueprint(), true);
                problems = blueprintProblems(b);
            }
            if (!problems.isEmpty()) blueprintRetries.add(List.copyOf(problems)); // 재시도를 다 쓰고도 남은 문제
            return b;
        }

        private List<String> earlyReview(BlueprintDto b) {
            try {
                return new TaskReviewService(calls).blueprintProblems(b);
            } catch (RuntimeException e) {
                return List.of(); // 이 점검이 실패해도 과제를 버리지 않는다(관문이 다시 본다)
            }
        }

        /**
         * BIZ 작성(g6): 문서마다 따로 동시에 부르고(그 문서 자리의 사실만 준다), 그다음 요청 글(설명·요구사항·후속 질문)을 부른다.
         * 문서 사이 숫자·문장 겹침은 결정적 검사(자료 숫자가 숫자표와 같음, 같은 문장 한 번)가 본다
         */
        private BizWritingDto write(String feedback) {
            List<String> docInputs = bp.docs().stream().map(d -> TaskWritingService.bizDocInput(bp, d, form.minutes(), feedback, DOC_CALL_MARK)).toList();
            List<BizWritingDto> parts = docInputs.isEmpty() ? List.of()
                    : calls.callParallel(Stage.WRITING, TaskPromptService.writingBizDoc(form.minutes(), e6, docInputs.size()), docInputs, BizWritingDto.class);
            BizWritingDto brief = calls.call(Stage.WRITING, TaskPromptService.WRITING_BIZ_BRIEF, TaskWritingService.bizBriefInput(bp, form.minutes(), feedback), BizWritingDto.class);
            List<BizDraftDto.DocDraft> docs = new ArrayList<>();
            for (int i = 0; i < parts.size(); i++) {
                String path = bp.docs().get(i).path();
                List<BizDraftDto.DocDraft> got = parts.get(i) == null ? List.of() : nz(parts.get(i).documents());
                // 이 경로의 문서를 쓰고, 경로를 다르게 적었으면 첫 문서를 이 경로로 둔다
                got.stream().filter(d -> d != null && path.equals(d.path())).findFirst().or(() -> got.stream().filter(d -> d != null).findFirst())
                        .ifPresent(d -> docs.add(new BizDraftDto.DocDraft(path, nz(d.pages()))));
            }
            return new BizWritingDto(nz(brief.description()), nz(brief.requirements()), docs, brief.taskFollowUp() == null ? "" : brief.taskFollowUp());
        }

        /** 문장(sentences) 중 하나라도 든 문서만 문서별 호출로 다시 쓰고, 나머지 문서와 요청 글은 그대로 둔다 */
        private BizWritingDto rewriteDocs(String feedback, List<String> sentences) {
            List<Integer> idx = new ArrayList<>();
            for (int i = 0; i < bp.docs().size(); i++) {
                String path = bp.docs().get(i).path();
                String text = writing.documents().stream().filter(d -> path.equals(d.path())).flatMap(d -> d.pages().stream()).reduce("", (a, b) -> a + "\n" + b);
                if (sentences.stream().anyMatch(s -> s != null && !s.isBlank() && text.contains(s.strip()))) idx.add(i);
            }
            if (idx.isEmpty()) for (int i = 0; i < bp.docs().size(); i++) idx.add(i); // 어느 문서인지 못 찾으면 문서를 모두 다시(요청 글은 그대로)
            List<String> inputs = idx.stream().map(i -> TaskWritingService.bizDocInput(bp, bp.docs().get(i), form.minutes(), feedback, DOC_CALL_MARK)).toList();
            List<BizWritingDto> parts = calls.callParallel(Stage.WRITING, TaskPromptService.writingBizDoc(form.minutes(), e6, bp.docs().size()), inputs, BizWritingDto.class);
            java.util.Map<String, BizDraftDto.DocDraft> byPath = new java.util.LinkedHashMap<>();
            writing.documents().forEach(d -> byPath.put(d.path(), d));
            for (int k = 0; k < idx.size(); k++) {
                String path = bp.docs().get(idx.get(k)).path();
                List<BizDraftDto.DocDraft> got = parts.get(k) == null ? List.of() : nz(parts.get(k).documents());
                got.stream().filter(d -> d != null && path.equals(d.path())).findFirst().or(() -> got.stream().filter(d -> d != null).findFirst())
                        .ifPresent(d -> byPath.put(path, new BizDraftDto.DocDraft(path, nz(d.pages()))));
            }
            return new BizWritingDto(writing.description(), writing.requirements(), new ArrayList<>(byPath.values()), writing.taskFollowUp());
        }

        private List<String> fillerNarration() {
            StringBuilder sb = new StringBuilder();
            writing.documents().forEach(d -> d.pages().forEach(p -> sb.append(p).append('\n')));
            return TaskWritingService.repeatedFiller(sb.toString());
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
            BizAnswerDto a = calls.call(Stage.ANSWER, TaskPromptService.answerBiz(TaskPromptService.stageDepth(true, form.minutes(), e6)), sb.toString(), BizAnswerDto.class);
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

        private List<GenCheckResultDto> preChecks(List<BizDraftDto.QuestionDraft> assembled, BizDraftDto draft) {
            List<String> problems = blueprintProblems(bp);
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
            out.add(TaskWritingService.genMetaCheck(String.join("\n", writing.description()) + "\n" + String.join("\n", writing.requirements()) + "\n" + docText));
            java.util.List<String> visible = new ArrayList<>();
            writing.documents().forEach(doc -> visible.add(doc.path()));
            BizNumbersService.csvFiles(bp).forEach(f -> visible.add(f.path()));
            visible.add(PLAN_FILE); // 기획안 틀은 저장할 때 만들어져 응시자에게 보인다(g6-biz: 설명이 "기획안.md에 작성"이라 쓰면 7/10이 이 검사에 걸림)
            if (filler != null) filler.files().forEach(f -> visible.add(f.path()));
            out.add(TaskWritingService.bizMaterialPointerCheck(bp, writing.description(), writing.requirements(),
                    questions == null ? List.of() : nz(questions.questions()).stream().map(q -> q == null || q.content() == null ? "" : q.content()).toList()));
            out.add(TaskWritingService.mentionedFilesCheck(String.join("\n", writing.description()) + "\n" + String.join("\n", writing.requirements()), visible));
            java.util.Map<String, String> han = new java.util.LinkedHashMap<>();
            han.put("[설계도]", StageCallService.json(bp)); // 숨은 글(정답 키 사유 등)까지 모든 글 칸
            han.put("[작성]", StageCallService.json(writing));
            han.put("[문항]", StageCallService.json(questions));
            han.put("[정답]", StageCallService.json(answer));
            if (e6 && filler != null) han.put("[관련 적은 자료]", StageCallService.json(filler));
            out.add(TaskWritingService.hanCheck(han));
            out.add(BizNumbersService.documentsCheck(bp, writing.documents()));
            out.add(BizQuestionService.check(bp, questions));
            out.add(AnswerChoiceService.check(bp, assembled));
            List<String> badSources = TaskAssemblyService.bizSourceProblems(bp, writing, assembled);
            out.add(badSources.isEmpty() ? new GenCheckResultDto(SOURCE_PATH_CHECK, true, "문항 근거 경로가 모두 실제 자료입니다.")
                    : new GenCheckResultDto(SOURCE_PATH_CHECK, false, String.join(" / ", badSources)));
            if (e6) { // E6·M6: 필요한 묶음은 한도의 70% 이하, 자료 전체는 한도의 2배 이상
                var mats = materials(draft);
                out.add(MaterialGateService.needed(bp, mats, limits));
                out.add(MaterialGateService.neededMin(bp, mats, limits));
                out.add(MaterialGateService.evidence(bp));
                out.add(MaterialGateService.confusing(bp));
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

        @Override
        public List<List<String>> blueprintRetries() {
            return List.copyOf(blueprintRetries);
        }
    }

    /** 응시자가 기획안을 쓰는 파일(GeneratedTaskRepository 가 저장할 때 제목·머리말만 있는 틀로 만든다) */
    static final String PLAN_FILE = "기획안.md";

    /** BIZ 작성 문서 호출의 입력 첫 줄(요청 글 호출과 가른다) */
    static final String DOC_CALL_MARK = "[문서 하나만 쓴다]";

    private static <T> List<T> nz(List<T> l) {
        return l == null ? List.of() : l;
    }
}
