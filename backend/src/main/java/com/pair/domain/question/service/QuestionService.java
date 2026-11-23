package com.pair.domain.question.service;

import com.pair.domain.question.entity.ChoiceAnswer;
import com.pair.domain.question.entity.QuestionResult;
import com.pair.domain.question.entity.TextAnswer;
import com.pair.domain.question.repository.ChoiceAnswerRepository;
import com.pair.domain.question.repository.QuestionResultRepository;
import com.pair.domain.question.repository.TextAnswerRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.pair.domain.attempt.service.AttemptState;
import com.pair.domain.attempt.entity.Attempt;
import com.pair.domain.attempt.repository.AttemptRepository;
import com.pair.domain.attempt.repository.AttemptStateRepository;
import com.pair.domain.scoring.repository.ScoringRepository;
import com.pair.domain.task.entity.QuestionChoice;
import com.pair.domain.task.repository.QuestionChoiceRepository;
import com.pair.domain.task.entity.ShortAnswerKey;
import com.pair.domain.task.repository.ShortAnswerKeyRepository;
import com.pair.domain.task.entity.TaskQuestion;
import com.pair.domain.task.repository.TaskQuestionRepository;
import com.pair.global.error.ConflictException;

/**
 * 비개발 과제의 객관식·단답형 문항: 보여 주기, 답 저장, 자동 채점, 리포트용 결과.
 * 문항 점수는 6개 역량 점수와 따로 "문항 n/m점"으로 보여 준다. 채점은 AI 없이 코드로만 한다.
 * 정답·인정 답안·근거 문서는 풀이 중에는 절대 내보내지 않는다(리포트의 correctAnswer 만 제출 뒤에 나간다).
 */
@Service
public class QuestionService {

    static final String TASK = "TASK";
    static final int SHORT_MAX_CHARS = 200;

    private final AttemptRepository attempts;
    private final AttemptStateRepository store;
    private final TaskQuestionRepository questions;
    private final QuestionChoiceRepository choices;
    private final ShortAnswerKeyRepository keys;
    private final ChoiceAnswerRepository choiceAnswers;
    private final TextAnswerRepository textAnswers;
    private final QuestionResultRepository results;
    private final ScoringRepository scorings;

    public QuestionService(AttemptRepository attempts, AttemptStateRepository store, TaskQuestionRepository questions,
                           QuestionChoiceRepository choices, ShortAnswerKeyRepository keys,
                           ChoiceAnswerRepository choiceAnswers, TextAnswerRepository textAnswers,
                           QuestionResultRepository results, ScoringRepository scorings) {
        this.attempts = attempts;
        this.store = store;
        this.questions = questions;
        this.choices = choices;
        this.keys = keys;
        this.choiceAnswers = choiceAnswers;
        this.textAnswers = textAnswers;
        this.results = results;
        this.scorings = scorings;
    }

    // ── 응답 모양 ─────────────────

    public record ChoiceView(Long id, int seq, String content) {}

    /** choiceIds(객관식) 또는 text(단답형) 중 하나만 나간다 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AnswerView(List<Long> choiceIds, String text) {}

    public record QuestionView(Long id, int seq, String type, String content, Integer points,
                               List<ChoiceView> choices, AnswerView answer) {}

    public record AnswerRequest(List<Long> choiceIds, String text) {}

    public record ReportItem(int seq, String type, String content, int points, int score,
                             String yourAnswer, String correctAnswer, boolean correct) {}

    public record Report(int score, int max, List<ReportItem> items) {}

    // ── 문항 보기 ─────────────────

    /** 응시자에게 보여 줄 문항(정답 정보 없음)과 지금까지 저장한 답. 문항이 없으면 빈 목록 */
    @Transactional(readOnly = true)
    public List<QuestionView> list(long attemptId) {
        Attempt a = attempt(attemptId);
        List<TaskQuestion> qs = questions.findByTaskIdAndStageAndActiveTrueOrderBySeqAsc(a.getTaskId(), TASK);
        if (qs.isEmpty()) return List.of();
        List<Long> ids = qs.stream().map(TaskQuestion::getId).toList();
        Map<Long, List<ChoiceView>> choiceViews = new HashMap<>();
        for (QuestionChoice c : choices.findByQuestionIdInOrderBySeqAsc(ids)) {
            choiceViews.computeIfAbsent(c.getQuestionId(), k -> new ArrayList<>()).add(new ChoiceView(c.getId(), c.getSeq(), c.getContent()));
        }
        Map<Long, List<Long>> chosen = chosenByQuestion(attemptId);
        Map<Long, String> texts = textAnswers.findByAttemptId(attemptId).stream()
                .collect(Collectors.toMap(TextAnswer::getQuestionId, TextAnswer::getContent));
        List<QuestionView> out = new ArrayList<>();
        for (TaskQuestion q : qs) {
            AnswerView answer = null;
            if (isChoice(q.getQuestionType())) {
                if (chosen.containsKey(q.getId())) answer = new AnswerView(chosen.get(q.getId()), null);
            } else if (texts.containsKey(q.getId())) {
                answer = new AnswerView(null, texts.get(q.getId()));
            }
            out.add(new QuestionView(q.getId(), q.getSeq(), q.getQuestionType(), q.getContent(), q.getPoints(),
                    choiceViews.getOrDefault(q.getId(), List.of()), answer));
        }
        return out;
    }

    // ── 답 저장 ─────────────────

    /**
     * 풀이 중에만 저장한다. 다시 보내면 덮어쓴다(객관식은 그 문항의 기존 선택을 지우고 새로 저장).
     * 빈 답(choiceIds 가 빈 배열, text 가 공백뿐)은 "답 지우기"로 처리하고 null 을 돌려준다.
     */
    @Transactional
    public AnswerView answer(long attemptId, long questionId, AnswerRequest req) {
        Attempt a = attempt(attemptId);
        if (a.getStatus() != AttemptState.Status.SOLVING) {
            throw new ConflictException("풀이 중일 때만 답을 저장할 수 있습니다. (지금 단계: " + a.getStatus() + ")");
        }
        TaskQuestion q = questions.findById(questionId)
                .filter(x -> x.getTaskId().equals(a.getTaskId()) && TASK.equals(x.getStage()) && x.isActive())
                .orElseThrow(() -> new IllegalArgumentException("이 과제의 문항이 아닙니다: " + questionId));
        if (req == null) throw new IllegalArgumentException("답이 비어 있습니다.");
        Instant now = Instant.now();
        AnswerView saved;
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("questionId", questionId);
        if (isChoice(q.getQuestionType())) {
            if (req.text() != null) throw new IllegalArgumentException("객관식 문항은 choiceIds 로 답해야 합니다.");
            List<Long> ids = req.choiceIds() == null ? List.of() : req.choiceIds().stream().distinct().toList();
            if (ids.stream().anyMatch(java.util.Objects::isNull)) throw new IllegalArgumentException("보기 번호가 올바르지 않습니다.");
            if (q.getQuestionType().equals("SINGLE") && ids.size() > 1) throw new IllegalArgumentException("한 개만 고를 수 있는 문항입니다.");
            Set<Long> valid = choices.findByQuestionIdOrderByIdAsc(q.getId()).stream().map(QuestionChoice::getId).collect(Collectors.toSet());
            if (!valid.containsAll(ids)) throw new IllegalArgumentException("이 문항의 보기가 아닙니다.");
            choiceAnswers.deleteByAttemptAndQuestion(attemptId, questionId);
            for (Long id : ids) choiceAnswers.save(new ChoiceAnswer(attemptId, id, questionId, q.getQuestionType(), now));
            saved = ids.isEmpty() ? null : new AnswerView(ids, null);
            event.put("choiceIds", ids);
        } else if (q.getQuestionType().equals("SHORT")) {
            if (req.choiceIds() != null && !req.choiceIds().isEmpty()) throw new IllegalArgumentException("단답형 문항은 text 로 답해야 합니다.");
            String text = req.text() == null ? "" : req.text().strip();
            if (text.length() > SHORT_MAX_CHARS) throw new IllegalArgumentException("답은 " + SHORT_MAX_CHARS + "자 이하로 입력하세요.");
            TextAnswer row = textAnswers.findByAttemptIdAndQuestionId(attemptId, questionId).orElse(null);
            if (text.isEmpty()) {
                if (row != null) textAnswers.delete(row);
                saved = null;
            } else {
                if (row == null) textAnswers.save(new TextAnswer(attemptId, questionId, "SHORT", text, now));
                else row.update(text, now);
                saved = new AnswerView(null, text);
            }
            event.put("text", text);
        } else {
            throw new IllegalArgumentException("답할 수 없는 문항 유형입니다: " + q.getQuestionType());
        }
        store.appendEvent(String.valueOf(attemptId), "answer", event);
        return saved;
    }

    // ── 채점 (AI 없음) ─────────────────

    /** 한 문항의 채점 결과. correct 는 정답 여부, reason 은 "정답" / "오답 (답: X / 정답: Y)" / "답 없음" */
    public record Graded(int score, boolean correct, String reason) {}

    /** 고른 보기 집합이 정답 보기 집합과 같을 때만 배점, 부분 점수는 없다 */
    public static Graded gradeChoice(int points, Set<Long> chosen, Set<Long> correct, String yourText, String correctText) {
        if (chosen.isEmpty()) return noAnswer();
        return verdict(points, chosen.equals(correct), yourText, correctText);
    }

    /** 단답형: 인정 답안 중 하나라도 맞으면 배점 */
    public static Graded gradeShort(int points, String answer, List<String> accepted) {
        if (answer == null || ShortAnswerMatcher.normalize(answer).isEmpty()) return noAnswer();
        return verdict(points, ShortAnswerMatcher.matches(answer, accepted), answer.strip(), joinAccepted(accepted));
    }

    private static Graded noAnswer() {
        return new Graded(0, false, "답 없음");
    }

    private static Graded verdict(int points, boolean right, String your, String correct) {
        return right ? new Graded(points, true, "정답") : new Graded(0, false, "오답 (답: " + your + " / 정답: " + correct + ")");
    }

    private static String joinAccepted(List<String> accepted) {
        return String.join(" 또는 ", accepted);
    }

    /**
     * 이 응시의 TASK 문항을 채점해 question_results 에 남긴다(같은 scoring_id, method=AUTO).
     * 6개 역량 점수에는 넣지 않는다. 문항이 없으면 아무것도 하지 않는다.
     */
    @Transactional
    public void grade(long scoringId, long attemptId) {
        Attempt a = attempt(attemptId);
        List<TaskQuestion> qs = questions.findByTaskIdAndStageAndActiveTrueOrderBySeqAsc(a.getTaskId(), TASK);
        if (qs.isEmpty()) return;
        Map<Long, Graded> graded = gradeAll(qs, attemptId);
        for (TaskQuestion q : qs) {
            Graded g = graded.get(q.getId());
            results.save(new QuestionResult(scoringId, q.getId(), g.score(), QuestionResult.AUTO, g.reason()));
        }
    }

    private Map<Long, Graded> gradeAll(List<TaskQuestion> qs, long attemptId) {
        List<Long> ids = qs.stream().map(TaskQuestion::getId).toList();
        Map<Long, List<QuestionChoice>> choicesByQ = choices.findByQuestionIdInOrderBySeqAsc(ids).stream()
                .collect(Collectors.groupingBy(QuestionChoice::getQuestionId));
        Map<Long, List<String>> acceptedByQ = new HashMap<>();
        for (ShortAnswerKey k : keys.findByQuestionIdInOrderByIdAsc(ids)) {
            acceptedByQ.computeIfAbsent(k.getQuestionId(), x -> new ArrayList<>()).add(k.getAccepted());
        }
        Map<Long, List<Long>> chosen = chosenByQuestion(attemptId);
        Map<Long, String> texts = textAnswers.findByAttemptId(attemptId).stream()
                .collect(Collectors.toMap(TextAnswer::getQuestionId, TextAnswer::getContent));
        Map<Long, Graded> out = new HashMap<>();
        for (TaskQuestion q : qs) {
            int points = q.getPoints() == null ? 0 : q.getPoints();
            if (isChoice(q.getQuestionType())) {
                List<QuestionChoice> cs = choicesByQ.getOrDefault(q.getId(), List.of());
                Set<Long> correct = cs.stream().filter(QuestionChoice::isCorrect).map(QuestionChoice::getId).collect(Collectors.toSet());
                Set<Long> mine = new LinkedHashSet<>(chosen.getOrDefault(q.getId(), List.of()));
                out.put(q.getId(), gradeChoice(points, mine, correct, choiceText(cs, mine), choiceText(cs, correct)));
            } else {
                out.put(q.getId(), gradeShort(points, texts.get(q.getId()), acceptedByQ.getOrDefault(q.getId(), List.of())));
            }
        }
        return out;
    }

    // ── 리포트 ─────────────────

    /** 가장 최근에 끝난 채점의 문항 결과. 문항 채점 결과가 없으면 null (문항 없는 과제, 옛 채점) */
    @Transactional(readOnly = true)
    public Report report(long attemptId) {
        var scoring = scorings.findFirstByAttemptIdAndStatusOrderByIdDesc(attemptId, "DONE").orElse(null);
        if (scoring == null) return null;
        List<QuestionResult> rs = results.findByScoringId(scoring.getId());
        if (rs.isEmpty()) return null;
        Map<Long, TaskQuestion> byId = questions.findAllById(rs.stream().map(QuestionResult::getQuestionId).toList()).stream()
                .collect(Collectors.toMap(TaskQuestion::getId, q -> q));
        List<Long> ids = new ArrayList<>(byId.keySet());
        Map<Long, List<QuestionChoice>> choicesByQ = choices.findByQuestionIdInOrderBySeqAsc(ids).stream()
                .collect(Collectors.groupingBy(QuestionChoice::getQuestionId));
        Map<Long, List<String>> acceptedByQ = new HashMap<>();
        for (ShortAnswerKey k : keys.findByQuestionIdInOrderByIdAsc(ids)) {
            acceptedByQ.computeIfAbsent(k.getQuestionId(), x -> new ArrayList<>()).add(k.getAccepted());
        }
        Map<Long, List<Long>> chosen = chosenByQuestion(attemptId);
        Map<Long, String> texts = textAnswers.findByAttemptId(attemptId).stream()
                .collect(Collectors.toMap(TextAnswer::getQuestionId, TextAnswer::getContent));
        List<ReportItem> items = new ArrayList<>();
        int score = 0;
        int max = 0;
        for (QuestionResult r : rs.stream().sorted(Comparator.comparingInt(x -> byId.get(x.getQuestionId()).getSeq())).toList()) {
            TaskQuestion q = byId.get(r.getQuestionId());
            int points = q.getPoints() == null ? 0 : q.getPoints();
            String your;
            String correct;
            if (isChoice(q.getQuestionType())) {
                List<QuestionChoice> cs = choicesByQ.getOrDefault(q.getId(), List.of());
                Set<Long> mine = new LinkedHashSet<>(chosen.getOrDefault(q.getId(), List.of()));
                your = mine.isEmpty() ? null : choiceText(cs, mine);
                correct = choiceText(cs, cs.stream().filter(QuestionChoice::isCorrect).map(QuestionChoice::getId).collect(Collectors.toSet()));
            } else {
                your = texts.get(q.getId());
                correct = joinAccepted(acceptedByQ.getOrDefault(q.getId(), List.of()));
            }
            items.add(new ReportItem(q.getSeq(), q.getQuestionType(), q.getContent(), points, r.getScore(), your, correct,
                    points > 0 && r.getScore() >= points));
            score += r.getScore();
            max += points;
        }
        return new Report(score, max, items);
    }

    // ── 보조 ─────────────────

    /** 문항 id → 고른 보기 id (보기 id 순) */
    private Map<Long, List<Long>> chosenByQuestion(long attemptId) {
        Map<Long, List<Long>> m = new HashMap<>();
        choiceAnswers.findByAttemptId(attemptId).stream().sorted(Comparator.comparing(ChoiceAnswer::getChoiceId))
                .forEach(c -> m.computeIfAbsent(c.getQuestionId(), k -> new ArrayList<>()).add(c.getChoiceId()));
        return m;
    }

    /** 보기 내용을 보기 순서대로 쉼표로 이은 글 */
    private static String choiceText(List<QuestionChoice> all, Set<Long> pick) {
        return all.stream().filter(c -> pick.contains(c.getId())).map(QuestionChoice::getContent).collect(Collectors.joining(", "));
    }

    private static boolean isChoice(String type) {
        return "SINGLE".equals(type) || "MULTI".equals(type);
    }

    private Attempt attempt(long attemptId) {
        return attempts.findById(attemptId).orElseThrow(() -> new NoSuchElementException("없는 응시입니다: " + attemptId));
    }
}
