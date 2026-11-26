package com.pair.domain.taskgen.service;

import static com.pair.domain.taskgen.service.BatchTestKit.EMPTY_REVIEW;
import static com.pair.domain.taskgen.service.BatchTestKit.await;
import static org.assertj.core.api.Assertions.assertThat;

import com.pair.domain.taskgen.dto.ReviewResultDto;
import com.pair.domain.taskgen.entity.TaskGenCall;
import com.pair.domain.taskgen.service.BatchTestKit.Calls;
import com.pair.domain.taskgen.service.BatchTestKit.FakeBatch;
import com.pair.domain.taskgen.service.BatchTestKit.MutableClock;
import com.pair.domain.taskgen.service.TaskPipelineService.Stage;
import com.pair.global.config.TaskGenBatchProperties;
import com.pair.infra.ai.BatchClient;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 배치 창구: 묶기, 회사별 나누기, 결과 나눠 주기, 대기 초과, 끊김·실패, 서버를 다시 켠 뒤 이어 가기 (가짜 시계·가짜 BatchClient) */
class BatchCallServiceTest {

    private final MutableClock clock = new MutableClock();
    private final FakeBatch batch = new FakeBatch();
    private final Calls calls = new Calls();
    private final AtomicInteger running = new AtomicInteger(1);
    private BatchCallService svc;
    private final java.util.concurrent.ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();

    @BeforeEach
    void setUp() {
        svc = new BatchCallService(batch, calls.repo, BatchTestKit.jobsRunning(running), TaskGenBatchProperties.defaults(), clock, "test");
    }

    private CompletableFuture<BatchCallService.Reply> submit(String job, String model, boolean review, String user) {
        var req = new BatchCallService.Request(job, "REVIEW", null, "h-" + job + "-" + user, 0, model, "SYS", user, ReviewResultDto.class, 1000, "low", review);
        return CompletableFuture.supplyAsync(() -> svc.submit(req), pool);
    }

    @Test
    @DisplayName("묶기 (a): 배치로 도는 작업이 모두 창구에서 기다리면 바로 보낸다(그 전에는 보내지 않는다)")
    void sendsWhenAllRunningJobsWait() {
        running.set(2);
        var f1 = submit("job-1", "gpt-6.1-sol", false, "a");
        await(() -> svc.queuedCount() == 1);
        svc.tick();
        assertThat(batch.created).isEmpty(); // 작업 2 가 아직 호출을 안 맡겼다
        var f2 = submit("job-2", "gpt-6.1-sol", false, "b");
        await(() -> svc.queuedCount() == 2);
        svc.tick();
        assertThat(batch.created).hasSize(1);
        assertThat(batch.created.get(0).reqs()).hasSize(2);
        assertThat(f1.isDone()).isFalse();
        assertThat(f2.isDone()).isFalse();
    }

    @Test
    @DisplayName("묶기 (b): 가장 오래 기다린 요청이 60초가 되면 다른 작업이 안 와도 보낸다")
    void sendsAfterSixtySeconds() {
        running.set(3);
        submit("job-1", "gpt-6.1-sol", false, "a");
        await(() -> svc.queuedCount() == 1);
        clock.advance(Duration.ofSeconds(59));
        svc.tick();
        assertThat(batch.created).isEmpty();
        clock.advance(Duration.ofSeconds(1));
        svc.tick();
        assertThat(batch.created).hasSize(1);
    }

    @Test
    @DisplayName("회사별로 나눠 보낸다: Anthropic 한 배치, OpenAI 한 배치")
    void splitsByVendor() {
        running.set(1);
        submit("job-1", "gpt-6.1-sol", false, "a");
        submit("job-1", "claude-sonnet-5-5", true, "b");
        submit("job-1", "gpt-6.1-sol", false, "c");
        await(() -> svc.queuedCount() == 3);
        svc.tick();
        assertThat(batch.created).hasSize(2);
        assertThat(batch.created).extracting(BatchTestKit.Created::vendor).containsExactlyInAnyOrder("openai", "anthropic");
        var openai = batch.created.stream().filter(c -> c.vendor().equals("openai")).findFirst().orElseThrow();
        assertThat(openai.reqs()).hasSize(2);
        // 보낸 호출은 task_gen_calls 에 PENDING + via=BATCH + vendor 로 남는다
        assertThat(calls.all()).hasSize(3).allSatisfy(c -> {
            assertThat(c.getStatus()).isEqualTo(TaskGenCall.PENDING);
            assertThat(c.getVia()).isEqualTo(TaskGenCall.BATCH);
            assertThat(c.getVendor()).isIn("openai", "anthropic");
        });
    }

    @Test
    @DisplayName("확인: 30초마다만 상태를 보고, 끝나면 결과를 호출마다 나눠 기다리던 스레드를 깨운다")
    void pollsAndDistributesResults() {
        running.set(1);
        var f1 = submit("job-1", "gpt-6.1-sol", false, "a");
        var f2 = submit("job-1", "gpt-6.1-sol", false, "b");
        await(() -> svc.queuedCount() == 2);
        svc.tick();
        String id = "batch-1";
        batch.finish(id);
        clock.advance(Duration.ofSeconds(10));
        svc.tick();
        assertThat(batch.statusCalls).isEmpty(); // 아직 30초가 안 됐다
        assertThat(f1.isDone()).isFalse();
        clock.advance(Duration.ofSeconds(20));
        batch.answerFor = customId -> new BatchClient.Answer(true, EMPTY_REVIEW, 100 + customId.length(), 50, 0, "stop", null);
        svc.tick();
        assertThat(batch.statusCalls).containsExactly(id);
        var r1 = f1.join();
        var r2 = f2.join();
        assertThat(r1.answered()).isTrue();
        assertThat(r1.value()).isEqualTo(new ReviewResultDto(java.util.List.of()));
        assertThat(r1.inputTokens()).isGreaterThan(100);
        assertThat(r1.waitedMs()).isEqualTo(30_000);
        assertThat(r2.answered()).isTrue();
        assertThat(svc.sentBatchCount()).isZero();
    }

    @Test
    @DisplayName("대기 상한: 관문은 10분, 생성은 20분이 넘으면 그 요청만 빼고 바로 호출로 바꾼다. 배치는 남은 요청이 모두 빠졌을 때만 취소한다")
    void waitLimitDropsOnlyThatRequest() {
        running.set(1);
        var gate = submit("job-1", "gpt-6.1-sol", true, "gate");
        var gen = submit("job-1", "gpt-6.1-sol", false, "gen");
        await(() -> svc.queuedCount() == 2);
        svc.tick();
        clock.advance(Duration.ofMinutes(10));
        svc.tick();
        assertThat(gate.join().fallback()).isEqualTo(BatchCallService.WAIT);
        assertThat(gen.isDone()).isFalse();
        assertThat(batch.cancelled).isEmpty(); // 생성 요청이 아직 남아 있다
        clock.advance(Duration.ofMinutes(10));
        svc.tick();
        assertThat(gen.join().fallback()).isEqualTo(BatchCallService.WAIT);
        assertThat(batch.cancelled).containsExactly("batch-1");
    }

    @Test
    @DisplayName("대기 상한을 넘겨 빠진 요청의 늦은 배치 결과는 버린다")
    void lateResultIsDiscarded() {
        running.set(1);
        var gate = submit("job-1", "gpt-6.1-sol", true, "gate");
        var gen = submit("job-1", "gpt-6.1-sol", false, "gen");
        await(() -> svc.queuedCount() == 2);
        svc.tick();
        clock.advance(Duration.ofMinutes(10));
        svc.tick();
        assertThat(gate.join().fallback()).isEqualTo(BatchCallService.WAIT);
        batch.finish("batch-1");
        clock.advance(Duration.ofSeconds(30));
        svc.tick(); // 배치가 끝났다: gen 은 결과를 받고, gate 는 이미 빠졌으니 결과를 받지 않는다
        assertThat(gen.join().answered()).isTrue();
        assertThat(gate.join().answered()).isFalse();
    }

    @Test
    @DisplayName("출력 한도 끊김(finish=length / max_tokens)은 바로 호출로 바꾸고, 버려진 토큰은 알려 준다")
    void truncatedGoesDirect() {
        running.set(1);
        batch.answerFor = id -> new BatchClient.Answer(true, "{\"violations\":", 500, 16000, 0, "max_tokens", null);
        var f = submit("job-1", "claude-sonnet-5-5", false, "a");
        await(() -> svc.queuedCount() == 1);
        svc.tick();
        batch.finish("batch-1");
        clock.advance(Duration.ofSeconds(30));
        svc.tick();
        var r = f.join();
        assertThat(r.fallback()).isEqualTo(BatchCallService.LENGTH);
        assertThat(r.outputTokens()).isEqualTo(16000);
    }

    @Test
    @DisplayName("실패·만료(결과에 없음)·못 읽는 답·배치 생성 실패는 바로 호출로 바꾼다")
    void failuresGoDirect() {
        running.set(1);
        batch.answerFor = id -> id.equals("x") ? null : new BatchClient.Answer(false, "", 0, 0, 0, "none", "expired");
        var failed = submit("job-1", "gpt-6.1-sol", false, "a");
        await(() -> svc.queuedCount() == 1);
        svc.tick();
        batch.finish("batch-1");
        clock.advance(Duration.ofSeconds(30));
        svc.tick();
        assertThat(failed.join().fallback()).isEqualTo(BatchCallService.FAILED);

        batch.answerFor = id -> new BatchClient.Answer(true, "이건 JSON 이 아님", 10, 10, 0, "stop", null);
        var garbage = submit("job-1", "gpt-6.1-sol", false, "b");
        await(() -> svc.queuedCount() == 1);
        svc.tick();
        batch.finish("batch-2");
        clock.advance(Duration.ofSeconds(30));
        svc.tick();
        assertThat(garbage.join().fallback()).isEqualTo(BatchCallService.FAILED);

        batch.failCreate = true;
        var noBatch = submit("job-1", "gpt-6.1-sol", false, "c");
        await(() -> svc.queuedCount() == 1);
        svc.tick();
        assertThat(noBatch.join().fallback()).isEqualTo(BatchCallService.FAILED);
    }

    @Test
    @DisplayName("서버를 다시 켠 뒤: 같은 호출이 배치 id 와 함께 PENDING 으로 남아 있으면 새로 보내지 않고 그 배치를 다시 확인한다")
    void restartRechecksPendingBatch() {
        running.set(1);
        calls.repo.save(TaskGenCall.pendingBatch("row-1", "job-1", "h-job-1-a", 0, "REVIEW", null, "openai", "batch-old", Instant.parse("2026-11-25T23:00:00Z")));
        var f = submit("job-1", "gpt-6.1-sol", false, "a");
        await(() -> svc.reattachedCount() == 1); // submit 이 기록을 보고 다시 확인할 배치로 넘길 때까지
        svc.tick();
        assertThat(batch.created).isEmpty(); // 새로 보내지 않는다
        assertThat(batch.statusCalls).containsExactly("batch-old");
        assertThat(f.isDone()).isFalse();
        batch.finish("batch-old");
        batch.byId.put("batch-old", new BatchTestKit.Created("openai", java.util.List.of(new BatchClient.Req("row-1", null))));
        clock.advance(Duration.ofSeconds(30));
        svc.tick();
        assertThat(f.join().answered()).isTrue();
    }

    @Test
    @DisplayName("마감: 남은 시간이 (남은 단계 × 90초 + 15분)보다 적으면 true, 마감이 없으면 false")
    void deadlineTightRule() {
        Instant now = clock.instant();
        int remaining = Stage.values().length - Stage.ANSWER.ordinal(); // 정답 쪽부터 끝까지
        long need = remaining * 90L + 15 * 60;
        assertThat(svc.deadlineTight(now.plusSeconds(need - 1), Stage.ANSWER)).isTrue();
        assertThat(svc.deadlineTight(now.plusSeconds(need + 1), Stage.ANSWER)).isFalse();
        assertThat(svc.deadlineTight(now.plusSeconds(need - 1), Stage.FILLER_REVIEW)).isFalse(); // 마지막 단계는 필요한 시간이 짧다
        assertThat(svc.deadlineTight(null, Stage.BLUEPRINT)).isFalse();
    }
}
