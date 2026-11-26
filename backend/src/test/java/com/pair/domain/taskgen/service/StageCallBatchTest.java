package com.pair.domain.taskgen.service;

import static com.pair.domain.taskgen.service.BatchTestKit.await;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.pair.domain.taskgen.dto.ReviewResultDto;
import com.pair.domain.taskgen.dto.StageUsageDto;
import com.pair.domain.taskgen.entity.TaskGenCall;
import com.pair.domain.taskgen.service.BatchTestKit.Calls;
import com.pair.domain.taskgen.service.BatchTestKit.FakeBatch;
import com.pair.domain.taskgen.service.BatchTestKit.MutableClock;
import com.pair.domain.taskgen.service.TaskPipelineService.Stage;
import com.pair.global.config.TaskGenBatchProperties;
import com.pair.global.config.TaskGenProperties;
import com.pair.infra.ai.BatchClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 배치(BATCH) 작업의 단계 호출: 창구에 맡기기, 바로 호출로 바꾸는 이유 4가지(대기 초과·마감·끊김·실패), 실제 비용(배치 50%), report 요약 */
class StageCallBatchTest {

    private static final TaskGenProperties PROPS = new TaskGenProperties(null, null, null, null, null);
    private static final String JOB = "job-1";

    private final MutableClock clock = new MutableClock();
    private final FakeBatch batch = new FakeBatch();
    private final Calls calls = new Calls();
    private final TaskPipelineServiceTest.StageAi direct = new TaskPipelineServiceTest.StageAi().add(ReviewResultDto.class, new ReviewResultDto(List.of()));
    private BatchCallService window;

    @BeforeEach
    void setUp() {
        window = new BatchCallService(batch, calls.repo, BatchTestKit.jobsRunning(new AtomicInteger(1)), TaskGenBatchProperties.defaults(), clock, "test");
    }

    @AfterEach
    void tearDown() {
        JobCallService.unbind();
    }

    /** 배치 작업의 단계 호출기: 마감은 지금부터 deadline 뒤 */
    private StageCallService stageCalls(Duration deadline) {
        new JobCallService(JOB, calls.repo, BatchTestKit.JSON, window, clock.instant().plus(deadline)).bind();
        return new StageCallService(direct, PROPS, true);
    }

    private CompletableFuture<ReviewResultDto> callAsync(StageCallService s, Stage stage, String user) {
        return CompletableFuture.supplyAsync(() -> s.call(stage, "sys", user, ReviewResultDto.class), Executors.newVirtualThreadPerTaskExecutor());
    }

    /** 맡긴 호출이 창구에 들어갈 때까지 기다렸다가 보낸다 */
    private void sendNow() {
        await(() -> window.queuedCount() == 1);
        window.tick();
    }

    private void finishBatchAfterPoll() {
        batch.finish("batch-" + batch.created.size());
        clock.advance(Duration.ofSeconds(30));
        window.tick();
    }

    @Test
    @DisplayName("배치로 받은 호출은 바로 호출을 부르지 않고, 비용은 실제 가격의 50%이며, 호출 기록은 DONE + via=BATCH 로 남는다")
    void batchAnswerIsHalfPrice() {
        batch.answerFor = id -> new BatchClient.Answer(true, BatchTestKit.EMPTY_REVIEW, 1000, 2000, 0, "stop", null);
        var s = stageCalls(Duration.ofHours(3));
        var f = callAsync(s, Stage.REVIEW, "u");
        sendNow();
        finishBatchAfterPoll();
        assertThat(f.join()).isEqualTo(new ReviewResultDto(List.of()));
        assertThat(direct.models).isEmpty(); // 바로 호출은 없다
        double full = (1000 * 2.0 + 2000 * 10.0) / 1_000_000; // sol $2 / $10
        assertThat(s.cost()).isCloseTo(full / 2, within(1e-12));
        assertThat(s.usage()).singleElement().satisfies(u -> {
            assertThat(u.via()).isEqualTo("BATCH");
            assertThat(u.waitedMs()).isEqualTo(30_000);
            assertThat(u.directReason()).isEmpty();
        });
        assertThat(calls.all()).singleElement().satisfies(c -> {
            assertThat(c.getStatus()).isEqualTo(TaskGenCall.DONE);
            assertThat(c.getVia()).isEqualTo(TaskGenCall.BATCH);
            assertThat(c.getWaitedMs()).isEqualTo(30_000);
            assertThat(c.getResult()).isNotNull();
        });
        assertThat(JobCallService.current().costUsd()).isCloseTo(full / 2, within(1e-12)); // 과제당 비용 상한은 이 실제 비용으로 센다
    }

    @Test
    @DisplayName("대기 상한을 넘으면 그 호출만 바로 호출로 바꾸고(이유 wait), 같은 기록 행이 DONE + DIRECT 가 된다")
    void waitLimitFallsBackToDirect() {
        var s = stageCalls(Duration.ofHours(6));
        var f = callAsync(s, Stage.REVIEW, "u"); // 관문: 10분
        sendNow();
        clock.advance(Duration.ofMinutes(10));
        window.tick();
        assertThat(f.join()).isEqualTo(new ReviewResultDto(List.of()));
        assertThat(direct.models).hasSize(1);
        assertThat(s.usage()).singleElement().satisfies(u -> {
            assertThat(u.via()).isEqualTo("DIRECT");
            assertThat(u.directReason()).isEqualTo("wait");
            assertThat(u.waitedMs()).isEqualTo(600_000);
        });
        assertThat(calls.all()).singleElement().satisfies(c -> {
            assertThat(c.getStatus()).isEqualTo(TaskGenCall.DONE);
            assertThat(c.getVia()).isEqualTo(TaskGenCall.DIRECT);
        });
    }

    @Test
    @DisplayName("마감이 모자라면 그 작업의 이후 모든 호출을 바로 호출로 한다(창구에 맡기지 않는다)")
    void deadlineMakesRestDirect() {
        var s = stageCalls(Duration.ofMinutes(5)); // 15분 여유도 없다
        s.call(Stage.BLUEPRINT, "sys", "u1", ReviewResultDto.class);
        s.call(Stage.REVIEW, "sys", "u2", ReviewResultDto.class);
        assertThat(batch.created).isEmpty();
        assertThat(window.queuedCount()).isZero();
        assertThat(direct.models).hasSize(2);
        assertThat(s.usage()).allSatisfy(u -> {
            assertThat(u.via()).isEqualTo("DIRECT");
            assertThat(u.directReason()).isEqualTo("deadline");
        });
    }

    @Test
    @DisplayName("마감이 충분하면 시간이 흘러 부족해질 때부터 바로 호출로 바뀌고 계속 바로 호출이다")
    void deadlineBecomesTightLater() {
        batch.answerFor = id -> new BatchClient.Answer(true, BatchTestKit.EMPTY_REVIEW, 10, 10, 0, "stop", null);
        var s = stageCalls(Duration.ofHours(1));
        var f = callAsync(s, Stage.BLUEPRINT, "u1");
        sendNow();
        finishBatchAfterPoll();
        f.join();
        clock.advance(Duration.ofMinutes(50)); // 남은 시간 약 9분 30초
        s.call(Stage.ANSWER, "sys", "u2", ReviewResultDto.class);
        clock.advance(Duration.ofMinutes(-30)); // 시계가 되돌아가도 한 번 바로 호출로 돌린 작업은 계속 바로 호출
        s.call(Stage.REVIEW, "sys", "u3", ReviewResultDto.class);
        assertThat(batch.created).hasSize(1);
        assertThat(s.usage()).extracting(StageUsageDto::via).containsExactly("BATCH", "DIRECT", "DIRECT");
        assertThat(s.usage().get(2).directReason()).isEqualTo("deadline");
    }

    @Test
    @DisplayName("출력 한도 끊김은 바로 호출로 다시 부른다(이유 length). 버려진 배치 호출의 토큰도 비용에 50%로 들어간다")
    void truncatedBatchCallRetriedDirect() {
        batch.answerFor = id -> new BatchClient.Answer(true, "{\"violations\":", 500, 16000, 0, "max_tokens", null);
        var s = stageCalls(Duration.ofHours(3));
        var f = callAsync(s, Stage.REVIEW, "u");
        sendNow();
        finishBatchAfterPoll();
        assertThat(f.join()).isEqualTo(new ReviewResultDto(List.of()));
        assertThat(direct.models).hasSize(1);
        assertThat(s.usage()).hasSize(2);
        assertThat(s.usage().get(0).via()).isEqualTo("BATCH");
        assertThat(s.usage().get(0).directReason()).isEqualTo("length");
        assertThat(s.usage().get(1).via()).isEqualTo("DIRECT");
        assertThat(s.usage().get(1).directReason()).isEqualTo("length");
        double expected = (500 * 2.0 + 16000 * 10.0) / 1_000_000 / 2 + (100 * 2.0 + 200 * 10.0) / 1_000_000; // 버린 배치 호출 50% + 바로 호출 전액
        assertThat(s.cost()).isCloseTo(expected, within(1e-12));
    }

    @Test
    @DisplayName("배치가 실패하면 바로 호출로 바꾼다(이유 failed)")
    void failedBatchRetriedDirect() {
        batch.answerFor = id -> new BatchClient.Answer(false, "", 0, 0, 0, "none", "expired");
        var s = stageCalls(Duration.ofHours(3));
        var f = callAsync(s, Stage.WRITING, "u");
        sendNow();
        finishBatchAfterPoll();
        f.join();
        assertThat(s.usage()).singleElement().satisfies(u -> {
            assertThat(u.via()).isEqualTo("DIRECT");
            assertThat(u.directReason()).isEqualTo("failed");
        });
    }

    @Test
    @DisplayName("이어 가기: 배치로 받아 저장된 호출은 다시 부르지 않고, 비용은 한 번만 50%로 센다")
    void resumedBatchCallStaysHalfPrice() {
        batch.answerFor = id -> new BatchClient.Answer(true, BatchTestKit.EMPTY_REVIEW, 1000, 2000, 0, "stop", null);
        var first = stageCalls(Duration.ofHours(3));
        var f = callAsync(first, Stage.REVIEW, "u");
        sendNow();
        finishBatchAfterPoll();
        f.join();
        JobCallService.unbind();
        var again = stageCalls(Duration.ofHours(3)); // 서버를 다시 켠 뒤 같은 작업
        again.call(Stage.REVIEW, "sys", "u", ReviewResultDto.class);
        assertThat(batch.created).hasSize(1);
        assertThat(direct.models).isEmpty();
        assertThat(again.usage()).singleElement().satisfies(u -> assertThat(u.via()).isEqualTo("BATCH"));
        assertThat(again.cost()).isCloseTo((1000 * 2.0 + 2000 * 10.0) / 1_000_000 / 2, within(1e-12));
        assertThat(JobCallService.current().reusedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("report 요약: 배치로 받은 호출 수와 바로 호출로 바꾼 수·이유")
    void batchSummaryForReport() {
        var usage = List.of(
                new StageUsageDto("REVIEW", "m", "low", 1, 1, 30, "", "BATCH", 30_000, ""),
                new StageUsageDto("WRITING", "m", "low", 1, 1, 90, "", "BATCH", 90_000, ""),
                new StageUsageDto("ANSWER", "m", "low", 1, 1, 0, "", "BATCH", 5_000, "length"),
                new StageUsageDto("ANSWER", "m", "low", 1, 1, 7, "", "DIRECT", 5_000, "length"),
                new StageUsageDto("CODE", "m", "low", 1, 1, 7, "", "DIRECT", 0, "deadline"),
                new StageUsageDto("BLUEPRINT", "m", "low", 1, 1, 7));
        Map<String, Object> b = TaskGeneratorService.batchSummary(usage, clock.instant());
        assertThat(b.get("batchCalls")).isEqualTo(2);
        assertThat(b.get("avgWaitSeconds")).isEqualTo(60L);
        assertThat(b.get("directCalls")).isEqualTo(2L);
        @SuppressWarnings("unchecked") Map<String, Long> reasons = (Map<String, Long>) b.get("directByReason");
        assertThat(reasons).containsEntry("length", 1L).containsEntry("deadline", 1L).containsEntry("wait", 0L).containsEntry("failed", 0L);
    }
}
