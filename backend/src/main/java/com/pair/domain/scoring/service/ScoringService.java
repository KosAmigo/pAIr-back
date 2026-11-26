package com.pair.domain.scoring.service;

import com.pair.domain.scoring.repository.ScoringResultRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import com.pair.infra.ai.AiUnavailableException;
import com.pair.infra.ai.ModelClient;
import com.pair.infra.ai.ModelJudge;
import com.pair.infra.ai.Judge;
import com.pair.domain.attempt.dto.AttemptStateDto;
import com.pair.domain.attempt.repository.AttemptStateRepository;
import com.pair.domain.attempt.dto.ChatTurnStateDto;
import com.pair.global.config.PairProperties;
import com.pair.domain.grader.repository.PromptVersionStateRepository;
import com.pair.domain.grader.dto.PromptVersionStateDto;
import com.pair.infra.sandbox.CodeRunner;
import com.pair.domain.scoring.service.RubricService.Check;
import com.pair.domain.scoring.service.RubricService.Method;
import com.pair.domain.task.dto.TaskDefDto;
import com.pair.domain.task.repository.TaskDefRepository;

import jakarta.annotation.PreDestroy;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * 감점식 채점 (pAIr-docs 평가/채점_기준표.md).
 * 자동 체크는 기록(숨은 테스트, 이벤트)으로, 판정 체크는 채점 모델이 근거와 함께 판정한다.
 * 결과는 report.json 과 콘솔 요약으로 남긴다.
 */
@Service
public class ScoringService {

    private static final Logger log = LoggerFactory.getLogger(ScoringService.class);
    /** 과제 제한시간을 읽지 못할 때(0 이하) 쓰는 기본값 */
    static final int D5_DEFAULT_LIMIT_MINUTES = 60;

    static int effectiveTimeLimit(int minutes) {
        return minutes > 0 ? minutes : D5_DEFAULT_LIMIT_MINUTES;
    }

    /** 제한시간 이내(같으면 이내)면 0, 넘기거나 도달 못 하면 배점 전체 */
    static int d5Penalty(Double firstValidMin, int timeLimitMin, int points) {
        return firstValidMin == null || firstValidMin > timeLimitMin ? points : 0;
    }

    private final AttemptStateRepository store;
    private final TaskDefRepository tasks;
    private final CodeRunner runner;
    private final List<Judge> judges;
    private final PairProperties props;
    private final PromptVersionStateRepository prompts;
    private final ReferenceDocService referenceDocs;
    private final ScoringResultRepository records;
    private final com.pair.domain.question.service.QuestionService questions;
    /** 채점 작업(실제 응시 채점, 채점 툴 실험 채점). 여러 건을 동시에 채점한다. */
    private final ExecutorService executor;
    /** 채점 큐(Redis). pair.scoring-queue.enabled=true 일 때만 있다. */
    private final ObjectProvider<ScoringQueueService> queues;
    /** 두 채점 모델을 동시에 부를 때 쓴다. */
    private final ExecutorService judgePool = Executors.newFixedThreadPool(8, r -> {
        Thread t = new Thread(r, "judge");
        t.setDaemon(true);
        return t;
    });

    public ScoringService(AttemptStateRepository store, TaskDefRepository tasks, CodeRunner runner,
                          ModelClient models, PairProperties props,
                          PromptVersionStateRepository prompts, ReferenceDocService referenceDocs, ScoringResultRepository records,
                          com.pair.domain.question.service.QuestionService questions,
                          ObjectProvider<ScoringQueueService> queues) {
        this.queues = queues;
        this.store = store;
        this.tasks = tasks;
        this.runner = runner;
        this.judges = List.of(new ModelJudge("claude", props.judge().claude(), models), new ModelJudge("openai", props.judge().openai(), models));
        this.props = props;
        this.prompts = prompts;
        this.referenceDocs = referenceDocs;
        this.records = records;
        this.questions = questions;
        int parallel = props.grader() == null || props.grader().parallel() < 1 ? 4 : props.grader().parallel();
        this.executor = Executors.newFixedThreadPool(parallel, r -> {
            Thread t = new Thread(r, "scoring");
            t.setDaemon(true);
            return t;
        });
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
        judgePool.shutdownNow();
    }

    /**
     * 채점 설정.
     * @param version 프롬프트 버전 (판정 기준, 공통 규칙)
     * @param judgeRepeat 채점 툴 실험용 배수. 모델마다 calls × judgeRepeat 번 부른다
     */
    public record Options(PromptVersionStateDto version, int judgeRepeat) {}

    /** 실제 응시 채점 설정: 채점 툴에서 지정한 버전, 채점 모델 1번씩. */
    public Options defaultOptions() {
        return new Options(prompts.active(), 1);
    }

    /** 채점 작업 풀에 작업을 넣는다 (채점 툴 실험 채점용). */
    public <T> Future<T> submit(Callable<T> task) {
        return executor.submit(task);
    }

    /**
     * 채점을 시작한다. 채점 큐가 켜져 있으면 큐에 넣고(이미 대기 중이면 그대로 둔다), 꺼져 있거나 큐에 넣지 못하면
     * 서버 안의 스레드 풀에서 바로 채점한다. 그래서 Redis 가 잠시 죽어도 제출은 막히지 않는다.
     */
    public void scoreAsync(String attemptId) {
        ScoringQueueService queue = queues.getIfAvailable();
        if (queue != null) {
            try {
                if (!queue.enqueue(attemptId)) log.info("이미 채점 대기 중인 응시입니다: {}", attemptId);
                return;
            } catch (RuntimeException e) {
                log.warn("채점 큐에 넣지 못해 서버에서 바로 채점합니다: {} ({})", attemptId, e.getClass().getSimpleName());
            }
        }
        executor.submit(() -> {
            try {
                score(attemptId);
            } catch (Exception e) {
                log.error("채점 실패: {}", attemptId, e);
                markFailed(attemptId, e);
            }
        });
    }

    /** 응시의 채점이 이미 끝났는지. 같은 작업이 두 번 전달돼도 다시 채점하지 않으려고 쓴다. */
    public boolean isScored(String attemptId) {
        return store.load(attemptId).status() == AttemptStateDto.Status.DONE;
    }

    /** 채점을 끝내 포기한 응시를 ERROR 로 둔다. 다시 채점(rescore)하면 풀린다. */
    public void markFailed(String attemptId, Throwable e) {
        store.save(store.load(attemptId).withError(e.getMessage() == null ? e.toString() : e.getMessage()));
    }

    // ── 채점 모델 응답 형식 ──────────────

    /** 판정 체크 하나의 결과. count 는 '1개당 감점' 체크(D1 비개발)에서만 쓴다. */
    public record CheckVerdict(String code, boolean violated, int count, List<String> evidence, String reason) {}

    public record JudgeResult(List<CheckVerdict> verdicts) {}

    // ── 리포트 형식 ───────────────────

    /**
     * @param status DEDUCTED(감점), PASSED(통과), SKIPPED(적용 안 함), NO_EVIDENCE(걸렸다고 했지만 근거가 없어 감점 안 함),
     *               SPLIT(판정 엇갈림: 일부 호출만 걸렸다고 봄. 호출이 홀수면 다수결, 짝수면 평균만큼 감점)
     * @param judges 판정 체크에서 채점 모델별 판정 (자동 체크는 빈 목록)
     */
    public record CheckOutcome(String code, String label, String method, int points, int deducted,
                              String status, List<String> evidence, String reason, List<JudgeView> judges) {
        public CheckOutcome(String code, String label, String method, int points, int deducted, String status, List<String> evidence, String reason) {
            this(code, label, method, points, deducted, status, evidence, reason, List.of());
        }
    }

    /** 채점 모델 하나의 판정. */
    public record JudgeView(String judge, String model, boolean violated, int count, List<String> evidence, String reason) {}

    /** 채점 모델 호출 한 번의 토큰. judge 는 JudgeView.judge() 와 같은 이름(예: openai#2). */
    public record JudgeUsage(String judge, String model, long input, long output, long cached) {}

    public record ItemResult(String key, String name, int max, int score, List<CheckOutcome> checks) {}

    /** 실제 응시 채점: 지정한 버전으로 채점하고 DB(채점 표, 리포트 JSON)에 저장한다. */
    public void score(String attemptId) {
        Options opt = defaultOptions();
        long scoringId = records.start(Long.parseLong(attemptId), opt.version());
        Map<String, Object> report;
        try {
            report = evaluate(store, attemptId, opt);
        } catch (RuntimeException | Error e) {
            records.fail(scoringId, e.getMessage() == null ? e.toString() : e.getMessage());
            throw e;
        }
        saveResult(scoringId, attemptId, report);
    }

    /**
     * 채점 결과를 저장한다. 순서: 리포트 JSON → 채점 표(DONE 확정) → 응시 상태 DONE.
     * 채점 표의 DONE 이 마지막에 정해지므로 "DONE 인데 리포트 없음"이 생기지 않는다. 중간에 실패하면 ERROR 로 남긴다.
     */
    @SuppressWarnings("unchecked")
    void saveResult(long scoringId, String attemptId, Map<String, Object> report) {
        AttemptStateDto a = store.load(attemptId);
        List<ItemResult> items = (List<ItemResult>) report.get("items");
        try {
            store.writeReport(a.id(), report);
            questions.grade(scoringId, Long.parseLong(a.id())); // 문항 채점: 코드로만, 6개 역량 점수와 따로
            records.finish(scoringId, tasks.get(a.taskId()).isDev(), items, (List<JudgeUsage>) report.get("judgeCalls"));
            store.save(store.load(a.id()).withStatus(AttemptStateDto.Status.DONE));
        } catch (RuntimeException | Error e) {
            records.fail(scoringId, e.getMessage() == null ? e.toString() : e.getMessage());
            throw e;
        }
        logSummary(a, tasks.get(a.taskId()), (int) report.get("total"), items);
    }

    /**
     * 채점해서 리포트를 돌려준다. 저장하지 않는다(채점 툴 실험 채점은 결과를 따로 저장).
     * @param src 응시 기록을 읽을 곳 (실제 응시 또는 샘플 응시)
     */
    public Map<String, Object> evaluate(AttemptStateRepository src, String attemptId, Options opt) {
        return evaluate(src, attemptId, opt, live());
    }

    /**
     * 채점 중 채점 모델 호출. 일반 채점은 바로 부르고(live), 채점 툴의 배치 채점은 미리 받아 둔 배치 결과를 돌려준다.
     * 같은 응시를 다시 계산해도 같은 호출을 찾을 수 있게 모델(base)과 반복 순번(repeat)으로 구분한다.
     */
    public interface Calls {
        Judge.Result<JudgeResult> judge(Judge base, int repeat, String system, String prefix, String body);
    }

    /** 바로 부르는 방식. */
    public Calls live() {
        return (base, repeat, system, prefix, body) -> base.judge(system, prefix, body, JudgeResult.class);
    }

    public List<Judge> judges() {
        return judges;
    }

    public Map<String, Object> evaluate(AttemptStateRepository src, String attemptId, Options opt, Calls calls) {
        AttemptStateDto a = src.load(attemptId);
        TaskDefDto task = tasks.get(a.taskId());
        boolean dev = task.isDev();
        List<Check> checks = opt.version().apply(RubricService.checksFor(dev));
        List<ChatTurnStateDto> turns = src.turns(attemptId);
        Map<String, String> files = src.readFiles(attemptId);
        List<JsonNode> events = src.events(attemptId);
        Map<String, long[]> judgeTokens = new LinkedHashMap<>(); // 모델별 {입력, 출력, 캐시 입력}
        for (Judge j : judges) judgeTokens.put(j.name(), new long[3]);
        Map<String, CheckOutcome> results = new LinkedHashMap<>();
        List<String> notes = new ArrayList<>();
        Map<String, Object> extras = new LinkedHashMap<>();
        // 빈 AI 답변은 시스템 오류(추론 모델이 한도 안에서 답을 끝내지 못함)라 채점에서 뺀다
        List<ChatTurnStateDto> aiTurns = turns.stream().filter(t -> "ai".equals(t.role()) && t.request() != null && !t.text().isBlank()).toList();
        long emptyAi = turns.stream().filter(t -> "ai".equals(t.role()) && t.text().isBlank()).count();
        if (emptyAi > 0) notes.add("시스템 오류로 비어 있는 AI 답변 " + emptyAi + "건은 응시자 책임으로 보지 않음");
        boolean usedAi = !aiTurns.isEmpty();

        // ── 자동: AI 사용 여부 (U0) ─────────
        results.put("U0", auto(checks, "U0", usedAi ? 0 : check(checks, "U0").points(), usedAi ? null : "AI 대화 기록이 없습니다."));
        // AI를 안 쓰면 통제력, 문제 분해력, 위임 판단력도 볼 수 없으므로 0점 (AI를 안 쓴 응시가 이 항목들을 공짜로 만점 받는 것을 막는다)
        for (String code : List.of("C0", "K0", "J0")) {
            results.put(code, auto(checks, code, usedAi ? 0 : check(checks, code).points(), usedAi ? null : "AI를 쓰지 않았습니다."));
        }

        // ── 자동: 숨은 테스트 (D0~D4 개발) ─────────
        if (dev) {
            Map<String, String> sources = new HashMap<>();
            files.forEach((k, v) -> {
                if (k.startsWith("src/") && k.endsWith(".java")) sources.put(k, v);
            });
            tasks.hiddenFiles(task.id()).forEach((k, v) -> sources.put("hidden/" + k, v));
            CodeRunner.RunResult hr = runner.run(sources);
            int reqTotal = 0, reqFail = 0;
            boolean optFail = false;
            for (CodeRunner.TestCase tc : hr.tests()) {
                if (tc.name().contains("required_")) {
                    reqTotal++;
                    if (!tc.passed()) reqFail++;
                } else if (tc.name().contains("optional_") && !tc.passed()) {
                    optFail = true;
                }
            }
            if (!hr.compiled()) {
                reqTotal = 4;
                reqFail = 4;
                optFail = true;
            }
            int originalTests = countTests(tasks.visibleFiles(task.id()));
            int nowTests = countTests(files);
            results.put("D0", auto(checks, "D0", hr.compiled() ? 0 : 30, hr.compiled() ? null : "숨은 테스트를 붙여 컴파일하지 못했습니다."));
            int d1 = check(checks, "D1").points();
            results.put("D1", auto(checks, "D1", Math.min(4 * d1, reqFail * d1),
                    "숨은 필수 테스트 " + (reqTotal - reqFail) + "/" + reqTotal + " 통과" + (reqFail > 0 ? " (실패 " + reqFail + "개 × " + d1 + "점)" : "")));
            results.put("D2", auto(checks, "D2", optFail ? check(checks, "D2").points() : 0, optFail ? "숨은 선택 테스트(여러 스레드 동시 주문) 실패" : "숨은 선택 테스트 통과"));
            results.put("D3", auto(checks, "D3", nowTests > originalTests ? 0 : check(checks, "D3").points(),
                    "테스트 " + originalTests + "개 → " + nowTests + "개"));
            List<Map<String, Object>> hidden = new ArrayList<>();
            for (CodeRunner.TestCase tc : hr.tests()) {
                hidden.add(Map.of("name", tc.name().replaceFirst("^shop\\.HiddenOrderTest\\.", ""), "passed", tc.passed()));
            }
            extras.put("hiddenTests", hidden);
        }

        // ── 자동: 첫 유효 결과 시간 (D5) ─────────
        Instant firstValidAt = dev ? firstValidFromEvents(events) : a.firstValidAt();
        Double firstValidMin = firstValidAt == null ? null
                : Duration.between(a.startedAt(), firstValidAt).toSeconds() / 60.0;
        int d5Points = check(checks, "D5").points();
        int timeLimitMin = effectiveTimeLimit(task.minutes());
        if (timeLimitMin != task.minutes()) {
            log.warn("과제 {} 제한시간을 읽지 못해 {}분으로 D5를 채점합니다 (minutes={})", task.id(), timeLimitMin, task.minutes());
        }
        int d5 = d5Penalty(firstValidMin, timeLimitMin, d5Points);
        results.put("D5", auto(checks, "D5", d5, firstValidMin == null
                ? "첫 유효 결과에 도달하지 못함"
                : String.format("첫 유효 결과까지 %.1f분 (제한 %d분 %s) - %s", firstValidMin, timeLimitMin,
                        d5 > 0 ? "초과" : "이내",
                        dev ? "src 코드를 고친 뒤 테스트가 전부 통과한 첫 실행" : "필수 제목을 모두 채운 첫 저장")));

        // ── 판정: 채점 모델 ─────────
        List<Check> judgeChecks = checks.stream()
                .filter(c -> c.method() == Method.JUDGE)
                .filter(c -> usedAi || !NEEDS_AI.contains(c.item()))
                .toList();
        if (!usedAi) {
            checks.stream().filter(c -> NEEDS_AI.contains(c.item()) && c.method() == Method.JUDGE)
                    .forEach(c -> results.put(c.code(), skipped(checks, c.code(), "AI를 쓰지 않아 판정하지 않음 (U0, C0, K0, J0)")));
        }
        String prefixReference = null; // full: 자료를 앞부분(캐시)에
        String reference = null;       // rag: 자료를 뒷부분에
        if (!dev) {
            // 제공 자료는 응시 파일이 아니라 과제 원본에서 가져온다(응시자가 고친 자료가 근거가 되지 않게, PDF 도 포함).
            // 제출물은 응시 파일 중 자료/·logs/ 가 아닌 것.
            Map<String, String> materials = tasks.materials(task.id());
            StringBuilder submission = new StringBuilder();
            files.forEach((path, content) -> {
                if (!path.startsWith("자료/") && !path.startsWith("logs/")) submission.append(content).append("\n");
            });
            PairProperties.Judge jp = props.judge();
            String mode = jp == null ? "auto" : jp.referenceMode();
            int maxChars = jp == null ? PairProperties.Judge.DEFAULT_FULL_MAX_CHARS : jp.fullMaxChars();
            boolean rag = ReferenceDocService.useRag(materials, mode, maxChars);
            ReferenceDocService.Section ref = referenceDocs.build(materials, submission.toString(), rag);
            if (rag) reference = ref.text();
            else prefixReference = ref.text();
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("mode", ref.mode());
            info.put("setting", mode);
            info.put("chars", ref.chars());
            info.put("chunksUsed", ref.chunksUsed());
            info.put("chunksTotal", ref.chunksTotal());
            info.put("embedTokens", ref.embedTokens());
            info.put("inCachedPrefix", !rag);
            extras.put("referenceDocs", info);
        }
        String prefix = judgePrefix(task, judgeChecks, prefixReference);
        String body = judgeBody(task, judgeChecks, transcript(turns, a.startedAt()), files, reference,
                a.followUpQuestions() != null && !a.followUpQuestions().isEmpty() ? a.followUpQuestions() : task.followUps(),
                a.followUpAnswers(), eventSummary(events, a.startedAt()));
        // 두 채점 모델을 동시에 부른다. 한쪽이 실패하면 남은 한쪽으로만 판정한다.
        // 채점 모델마다 calls(설정) × judgeRepeat(채점 툴 실험) 번 부른다. 모든 호출의 판정을 합친다(combine: 홀수면 다수결).
        Map<Judge, CompletableFuture<Judge.Result<JudgeResult>>> judgeCalls = new LinkedHashMap<>();
        String judgeSystem = opt.version().judgeSystem();
        int repeat = Math.max(1, opt.judgeRepeat());
        Map<String, Integer> callCounts = new LinkedHashMap<>();
        for (Judge base : judges) {
            int n = base.calls() * repeat;
            callCounts.put(base.model(), n);
            for (int r = 1; r <= n; r++) {
                Judge j = n == 1 ? base : new Repeated(base, r);
                int rr = r;
                judgeCalls.put(j, CompletableFuture.supplyAsync(() -> calls.judge(base, rr, judgeSystem, prefix, body), judgePool));
            }
        }
        Map<Judge, Map<String, CheckVerdict>> byJudge = new LinkedHashMap<>();
        List<JudgeUsage> judgeCallList = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        for (var e : judgeCalls.entrySet()) {
            try {
                Judge.Result<JudgeResult> jr = e.getValue().join();
                addTokens(judgeTokens, e.getKey(), jr);
                judgeCallList.add(new JudgeUsage(e.getKey().name(), e.getKey().model(), jr.inputTokens(), jr.outputTokens(), jr.cachedInputTokens()));
                Map<String, CheckVerdict> m = new HashMap<>();
                for (CheckVerdict v : jr.value().verdicts()) m.put(v.code(), v);
                byJudge.put(e.getKey(), m);
            } catch (CompletionException ex) {
                Throwable cause = ex.getCause() == null ? ex : ex.getCause();
                log.warn("채점 모델 {} 실패: {}", e.getKey().model(), cause.getMessage());
                failures.add(e.getKey().model() + ": " + cause.getMessage());
            }
        }
        if (byJudge.isEmpty()) throw new AiUnavailableException("채점 모델 두 개가 모두 실패했습니다. " + String.join(" / ", failures));
        if (!failures.isEmpty()) notes.add("채점 모델 한쪽이 실패해 남은 모델로만 판정함 (" + String.join(" / ", failures) + ")");
        for (Check c : judgeChecks) results.put(c.code(), combine(c, byJudge));

        // ── 항목 점수 ─────────
        List<ItemResult> items = new ArrayList<>();
        int total = 0;
        for (RubricService.Item item : RubricService.ITEMS) {
            List<CheckOutcome> list = new ArrayList<>();
            int deducted = 0;
            for (Check c : checks) {
                if (!c.item().equals(item.key())) continue;
                CheckOutcome r = results.get(c.code());
                if (r == null) continue;
                list.add(r);
                deducted += r.deducted();
            }
            int score = Math.max(0, item.max() - deducted);
            total += score;
            items.add(new ItemResult(item.key(), item.name(), item.max(), score, list));
        }

        notes.add("채점 기준: pAIr-docs 평가/채점_기준표.md (감점식, 총점 " + RubricService.total() + "점)");
        notes.add("이중 채점: 판정 체크는 두 채점 모델(" + String.join(", ", judges.stream().map(Judge::model).toList())
                + ")을 여러 번 불러(Haiku 1번 + terra 2번 등) 호출마다 감점을 정한 뒤 합침" + (repeat > 1 ? "(모델마다 " + repeat + "배로 호출)" : "")
                + ". 남은 호출이 홀수면 다수결(가운데 값), 2개만 남으면 평균(소수점 버림), 1개만 남으면 그 판정을 씀. 근거(대화 턴, 파일) 없이 걸렸다고 한 판정은 감점하지 않음. 일부만 걸렸다고 하면 '판정 엇갈림'으로 표시");
        notes.add("프롬프트 버전: " + opt.version().id() + " (" + opt.version().name() + ")");

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("version", 2);
        report.put("attemptId", a.id());
        report.put("taskId", task.id());
        report.put("taskTitle", task.title());
        report.put("taskType", task.type());
        report.put("assistant", a.provider() + " / " + a.model());
        report.put("judgeModel", String.join(" + ", judges.stream().map(Judge::model).toList()));
        report.put("promptVersion", opt.version().id());
        report.put("settings", Map.of("judgeRepeat", repeat, "judgeCalls", callCounts));
        report.put("startedAt", a.startedAt());
        report.put("submittedAt", a.submittedAt());
        report.put("durationSec", a.submittedAt() == null ? null : Duration.between(a.startedAt(), a.submittedAt()).toSeconds());
        report.put("chatTurns", turns.size());
        report.put("firstValidMinutes", firstValidMin == null ? null : round1(firstValidMin));
        report.put("timeLimitMinutes", timeLimitMin);
        report.put("total", total);
        report.put("max", RubricService.total());
        report.put("items", items);
        report.put("notes", notes);
        report.putAll(extras);
        long assistantIn = turns.stream().filter(t -> t.inputTokens() != null).mapToLong(ChatTurnStateDto::inputTokens).sum();
        long assistantOut = turns.stream().filter(t -> t.outputTokens() != null).mapToLong(ChatTurnStateDto::outputTokens).sum();
        Map<String, Object> tokens = new LinkedHashMap<>();
        tokens.put("assistant", Map.of("input", assistantIn, "output", assistantOut));
        for (Judge j : judges) {
            long[] t = judgeTokens.get(j.name());
            tokens.put("judge_" + j.name(), Map.of("model", j.model(), "input", t[0], "output", t[1], "cachedInput", t[2]));
        }
        report.put("tokens", tokens);
        report.put("judgeCalls", judgeCallList);
        report.put("transcript", turns.stream().map(t -> Map.of(
                "turn", t.turn(), "role", t.role(), "time", clock(a.startedAt(), t.at()), "text", t.text())).toList());
        return report;
    }

    /** 같은 채점 모델을 여러 번 부를 때 호출마다 다른 이름(claude#2)을 붙인다. 토큰은 원래 모델 이름으로 모은다. */
    static record Repeated(Judge base, int n) implements Judge {
        public String name() {
            return base.name() + "#" + n;
        }

        public String model() {
            return base.model();
        }

        public <T> Result<T> judge(String system, String cachedPrefix, String user, Class<T> type) {
            return base.judge(system, cachedPrefix, user, type);
        }
    }

    // ── 프롬프트 ───────────────────────

    /** 응시와 관계없이 같은 앞부분(과제, 숨은 정답, 체크 항목). 프롬프트 캐시로 재사용된다. */
    String judgePrefix(TaskDefDto task, List<Check> checks, String reference) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 과제\n").append(task.title()).append(" (").append(task.isDev() ? "개발" : "비개발").append(")\n");
        task.description().forEach(d -> sb.append(d).append("\n"));
        sb.append("\n## 요구사항\n");
        task.requirements().forEach(r -> sb.append("- ").append(r).append("\n"));
        if (task.isDev()) {
            sb.append("\n## 숨은 정답 설명 (응시자는 보지 못함)\n")
              .append(tasks.hiddenFiles(task.id()).getOrDefault("answer.md", "")).append("\n");
        }
        if (!task.isDev()) {
            sb.append("\n## 숨은 체크리스트 (응시자는 보지 못함)\n")
              .append(tasks.hiddenFiles(task.id()).getOrDefault("checklist.md", "")).append("\n");
            String trap = tasks.trapNotes(task.id()); // AI 가 만든 과제만 있다(함정 설명, 근거 문장, 자료 위치)
            if (trap != null) sb.append(trap);
        }
        // 제공 자료를 통째로 넣는 경우: 같은 과제면 글이 똑같아(경로 순 고정) 프롬프트 캐시가 맞는다. 체크 목록보다 앞에 둔다.
        if (reference != null) sb.append("\n# 제공 자료 (수치 대조용)\n").append(reference);
        sb.append("\n# 판정할 체크 항목\n");
        for (Check c : checks) {
            sb.append("- ").append(c.code()).append(": ").append(c.label()).append("\n  판정 기준: ").append(c.judgeHint()).append("\n");
        }
        return sb.toString();
    }

    /** 응시마다 다른 뒷부분(대화, 이벤트, 파일, 후속 답변). */
    private String judgeBody(TaskDefDto task, List<Check> checks, String transcript, Map<String, String> files, String reference,
                             List<String> questions, List<String> answers, String events) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n# 대화 기록 (응시자는 AI가 대화창에 붙여 넣은 내용만 볼 수 있다는 것을 안내받았음)\n").append(transcript).append("\n");
        sb.append("# 응시 이벤트 요약 (저장, 빌드, 테스트 실행)\n").append(events).append("\n");
        sb.append("# 최종 제출 파일\n");
        Map<String, String> original = tasks.visibleFiles(task.id());
        files.forEach((path, content) -> {
            if (path.startsWith("logs/") || path.startsWith("자료/")) return;
            boolean changed = !content.equals(original.get(path));
            sb.append("## ").append(path).append(changed ? " (수정됨)" : " (원본 그대로)").append("\n```\n").append(content).append("\n```\n");
        });
        if (reference != null) sb.append("\n# 제공 자료 (수치 대조용)\n").append(reference);
        sb.append("\n# 후속 질문 답변\n");
        for (int i = 0; i < questions.size(); i++) {
            String ans = answers != null && i < answers.size() ? answers.get(i) : "";
            sb.append("Q").append(i + 1).append(". ").append(questions.get(i)).append("\nA. ").append(ans.isBlank() ? "(무응답)" : ans).append("\n\n");
        }
        sb.append("\n체크 항목마다(").append(String.join(", ", checks.stream().map(Check::code).toList()))
          .append(") code, violated, count, evidence, reason 을 채워 verdicts 배열로 답하세요.");
        return sb.toString();
    }

    /** 채점 모델이 검증 흐름(C1, P3)과 직접 편집 흐름(J3)을 볼 수 있게 이벤트를 짧게 요약한다. */
    static String eventSummary(List<JsonNode> events, Instant start) {
        StringBuilder sb = new StringBuilder();
        for (JsonNode e : events) {
            String type = e.path("type").asText();
            if (!type.equals("run") && !type.equals("build") && !type.equals("save") && !type.equals("firstValid") && !type.equals("submit")
                    && !type.equals("aiEdit")) continue;
            String at;
            try {
                at = clock(start, Instant.parse(e.path("at").asText()));
            } catch (RuntimeException ex) {
                at = "?";
            }
            sb.append(at).append(" ").append(type);
            if (type.equals("run")) sb.append(" passed=").append(e.path("passed").asInt()).append(" failed=").append(e.path("failed").asInt())
                    .append(" compiled=").append(e.path("compiled").asBoolean());
            if (type.equals("build")) sb.append(" compiled=").append(e.path("compiled").asBoolean());
            if (type.equals("save")) sb.append(" ").append(e.path("path").asText()).append(" (").append(e.path("length").asInt()).append("자)");
            if (type.equals("aiEdit")) sb.append(" #T").append(pad(e.path("turn").asInt())).append(" 수정 제안 ")
                    .append(e.path("action").asText().equals("apply") ? "적용" : "거절").append(" ").append(e.path("path").asText());
            sb.append("\n");
        }
        return sb.isEmpty() ? "(기록 없음)\n" : sb.toString();
    }

    /**
     * 개발 과제의 첫 유효 결과: src/ 파일을 처음 저장한 뒤, 컴파일되고 테스트가 전부 통과한 첫 실행 시각.
     * 테스트를 추가했는지와는 따로 본다(테스트 추가는 D3 에서만 감점).
     */
    static Instant firstValidFromEvents(List<JsonNode> events) {
        boolean srcChanged = false;
        for (JsonNode e : events) {
            String type = e.path("type").asText();
            if (type.equals("save") && e.path("path").asText().startsWith("src/")) srcChanged = true;
            if (type.equals("run") && srcChanged && e.path("compiled").asBoolean() && e.path("failed").asInt() == 0
                    && e.path("passed").asInt() > 0) {
                try {
                    return Instant.parse(e.path("at").asText());
                } catch (RuntimeException ex) {
                    return null;
                }
            }
        }
        return null;
    }

    /** AI를 써야 판정할 수 있는 항목. AI를 안 쓰면 판정 체크를 하지 않고 U0, C0, R0 으로 0점 처리한다. */
    static final Set<String> NEEDS_AI = Set.of("usage", "control", "decomposition", "delegation");

    /**
     * 채점 호출들의 판정을 합친다. 호출마다 감점을 정하고(근거를 대고 걸렸다고 하면 배점, D1은 개수 × 배점)
     * 호출 수가 홀수면 가운데 값(다수결), 짝수면 평균(소수점 버림)을 쓴다.
     * 예: Haiku 1번 + terra 2번이면 셋 중 둘 이상이 걸렸다고 할 때만 감점. 두 번이면 한쪽만 걸렸다고 할 때 절반 감점.
     * 호출이 실패해 빠지면 남은 호출만으로 같은 규칙을 쓴다.
     * 판정이 빠진 모델은 "안 걸림"(감점 0)으로 본다.
     */
    static CheckOutcome combine(Check c, Map<? extends Judge, Map<String, CheckVerdict>> byJudge) {
        List<JudgeView> views = new ArrayList<>();
        for (var e : byJudge.entrySet()) {
            CheckVerdict v = e.getValue().get(c.code());
            if (v == null) continue;
            views.add(new JudgeView(e.getKey().name(), e.getKey().model(), v.violated(), v.count(),
                    v.evidence() == null ? List.of() : v.evidence(), v.reason()));
        }
        if (views.isEmpty()) {
            return new CheckOutcome(c.code(), c.label(), "JUDGE", c.points(), 0, "SKIPPED", List.of(), "채점 모델이 판정하지 않음", views);
        }
        List<JudgeView> withEvidence = views.stream().filter(v -> v.violated() && !v.evidence().isEmpty()).toList();
        List<Integer> each = new ArrayList<>();
        for (int i = 0; i < byJudge.size(); i++) each.add(0);
        for (int i = 0; i < withEvidence.size(); i++) {
            JudgeView v = withEvidence.get(i);
            each.set(i, c.code().equals("D1") ? Math.min(4 * c.points(), Math.max(1, v.count()) * c.points()) : c.points());
        }
        boolean majority = each.size() % 2 == 1;
        int deducted = majority ? each.stream().sorted().toList().get(each.size() / 2)
                : each.stream().mapToInt(Integer::intValue).sum() / each.size();
        List<String> ev = withEvidence.stream().flatMap(v -> v.evidence().stream()).distinct().toList();
        if (withEvidence.size() == byJudge.size()) {
            return new CheckOutcome(c.code(), c.label(), "JUDGE", c.points(), deducted, "DEDUCTED", ev, withEvidence.get(0).reason(), views);
        }
        if (!withEvidence.isEmpty()) {
            JudgeView v = withEvidence.get(0);
            return new CheckOutcome(c.code(), c.label(), "JUDGE", c.points(), deducted, "SPLIT", ev,
                    "판정 엇갈림: " + withEvidence.size() + "/" + byJudge.size() + " 판정만 걸렸다고 봐서 "
                            + (majority ? "다수결로 " + deducted + "점 감점. " : "평균(" + deducted + "점) 감점. ") + v.reason(), views);
        }
        var noEvidence = views.stream().filter(JudgeView::violated).findFirst();
        if (noEvidence.isPresent()) {
            return new CheckOutcome(c.code(), c.label(), "JUDGE", c.points(), 0, "NO_EVIDENCE", List.of(),
                    "근거가 없어 감점하지 않음: " + noEvidence.get().reason(), views);
        }
        List<String> pev = views.stream().flatMap(v -> v.evidence().stream()).distinct().toList();
        return new CheckOutcome(c.code(), c.label(), "JUDGE", c.points(), 0, "PASSED", pev, views.get(0).reason(), views);
    }

    private static void addTokens(Map<String, long[]> acc, Judge j, Judge.Result<?> r) {
        long[] t = acc.get(j instanceof Repeated rp ? rp.base().name() : j.name());
        t[0] += r.inputTokens();
        t[1] += r.outputTokens();
        t[2] += r.cachedInputTokens();
    }

    // ── 보조 함수 ─────────────────────

    private static Check check(List<Check> checks, String code) {
        return checks.stream().filter(c -> c.code().equals(code)).findFirst().orElseThrow();
    }

    private static CheckOutcome auto(List<Check> checks, String code, int deducted, String reason) {
        Check c = check(checks, code);
        return new CheckOutcome(code, c.label(), "AUTO", c.points(), deducted, deducted > 0 ? "DEDUCTED" : "PASSED", List.of(), reason);
    }

    private static CheckOutcome skipped(List<Check> checks, String code, String reason) {
        Check c = check(checks, code);
        return new CheckOutcome(code, c.label(), c.method().name(), c.points(), 0, "SKIPPED", List.of(), reason);
    }

    static String transcript(List<ChatTurnStateDto> turns, Instant start) {
        StringBuilder sb = new StringBuilder();
        for (ChatTurnStateDto t : turns) {
            String text = "ai".equals(t.role()) && t.text().isBlank()
                    ? "[시스템 오류] AI 답변이 비어 있음 (추론 모델이 출력 한도 안에서 답을 끝내지 못함. 응시자 책임 아님)"
                    : t.text();
            sb.append("#T").append(pad(t.turn())).append(" ").append("user".equals(t.role()) ? "응시자" : "AI")
              .append(" ").append(clock(start, t.at())).append("\n").append(text).append("\n\n");
        }
        return sb.isEmpty() ? "(대화 없음)\n" : sb.toString();
    }

    static String clock(Instant start, Instant at) {
        long s = Math.max(0, Duration.between(start, at).toSeconds());
        return String.format("%02d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60);
    }

    static String pad(int n) {
        return n < 10 ? "0" + n : String.valueOf(n);
    }

    static int countTests(Map<String, String> files) {
        int n = 0;
        for (var e : files.entrySet()) {
            if (!e.getKey().startsWith("test/")) continue;
            int i = 0;
            while ((i = e.getValue().indexOf("@Test", i)) >= 0) {
                n++;
                i += 5;
            }
        }
        return n;
    }

    static boolean notesFilled(String notes) {
        return notes.lines().map(String::trim).filter(l -> !l.isEmpty() && !l.startsWith("#")).count() >= 1;
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    private void logSummary(AttemptStateDto a, TaskDefDto task, int total, List<ItemResult> items) {
        StringBuilder sb = new StringBuilder("\n========== pAIr 채점 결과 ==========\n");
        sb.append("응시 ").append(a.id()).append(" | ").append(task.title()).append("\n");
        sb.append("총점 ").append(total).append(" / ").append(RubricService.total()).append("\n");
        for (ItemResult it : items) {
            sb.append(String.format("%s %d / %d%n", it.name(), it.score(), it.max()));
            for (CheckOutcome c : it.checks()) {
                if (c.deducted() > 0) sb.append(String.format("  %s -%d  %s%n", c.code(), c.deducted(), c.reason()));
            }
        }
        sb.append("리포트 위치: ").append(store.location(a.id())).append("\n");
        sb.append("====================================");
        log.info(sb.toString());
    }
}
