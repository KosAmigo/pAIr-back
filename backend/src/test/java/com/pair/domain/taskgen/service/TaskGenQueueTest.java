package com.pair.domain.taskgen.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pair.domain.taskgen.dto.GenCheckResultDto;
import com.pair.domain.taskgen.dto.ReviewResultDto;
import com.pair.domain.taskgen.dto.TaskGenRequest;
import com.pair.domain.taskgen.repository.TaskGenCallRepository;
import com.pair.domain.taskgen.repository.TaskGenJobRepository;
import com.pair.domain.taskgen.service.TaskGenJobService.State;
import com.pair.domain.taskgen.service.TaskPipelineService.Stage;
import com.pair.global.config.ExamIntegrationTest;
import com.pair.global.config.TaskGenProperties;
import com.pair.global.error.ConflictException;
import com.pair.infra.storage.TaskFileStorageGuard;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 생성 작업 대기열·워커·이어 가기. 공유 스프링 컨텍스트(진짜 H2 테이블)에 가짜 생성기를 끼운 서비스를 직접 만들어 쓴다.
 * 테스트마다 다른 env 이름을 써서 다른 테스트의 작업과 섞이지 않는다.
 */
class TaskGenQueueTest extends ExamIntegrationTest {

    private static final TaskGenProperties PROPS = new TaskGenProperties(null, null, null, null, null);
    private static final TaskFileStorageGuard SAFE = new TaskFileStorageGuard("s3", "jdbc:h2:mem:x");

    @Autowired TaskGenJobRepository jobRepo;
    @Autowired TaskGenCallRepository callRepo;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    private static String uniqueEnv() {
        return "test-" + UUID.randomUUID();
    }

    private TaskGenJobService service(DevTaskGeneratorService dev, String env, int max, boolean worker) {
        return new TaskGenJobService(dev, mock(BizTaskGeneratorService.class), SAFE, jobRepo, callRepo, json, env, max, worker);
    }

    private static TaskGenRequest form(String topic) {
        return new TaskGenRequest("DEV", topic, "경계 함정", "SELF", 60, List.of("usage"));
    }

    private static TaskGeneratorService.Outcome outcome(String code) {
        return new TaskGeneratorService.Outcome(code, true, 1, List.of(new GenCheckResultDto("컴파일", true, "ok")));
    }

    private static void await(BooleanSupplier ok) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            if (ok.getAsBoolean()) return;
            Thread.sleep(50);
        }
        throw new AssertionError("기다린 조건이 되지 않았습니다");
    }

    @Test
    @DisplayName("상한 2에 3개를 넣으면 2개 RUNNING, 1개 QUEUED(순번 1). 하나가 끝나면 세 번째가 시작한다")
    void queueRespectsLimit() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        DevTaskGeneratorService dev = mock(DevTaskGeneratorService.class);
        when(dev.generate(any(), anyLong(), any())).thenAnswer(inv -> {
            gate.await(10, TimeUnit.SECONDS);
            return outcome("q-" + UUID.randomUUID().toString().substring(0, 8));
        });
        TaskGenJobService s = service(dev, uniqueEnv(), 2, true);

        String a = s.start(form("a"), 1L); // 상한과 상관없이 바로 QUEUED 로 받는다
        String b = s.start(form("b"), 1L);
        String c = s.start(form("c"), 1L);
        assertThat(s.status(a).status()).isEqualTo(State.QUEUED);
        assertThat(s.status(c).queuePosition()).isEqualTo(3);

        s.tick();
        assertThat(s.status(a).status()).isEqualTo(State.RUNNING);
        assertThat(s.status(b).status()).isEqualTo(State.RUNNING);
        assertThat(s.status(c).status()).isEqualTo(State.QUEUED);
        assertThat(s.status(c).queuePosition()).isEqualTo(1);
        assertThat(s.status(c).topic()).isEqualTo("c");

        s.tick(); // 자리가 없으면 아무것도 안 집는다
        assertThat(s.status(c).status()).isEqualTo(State.QUEUED);

        gate.countDown();
        await(() -> s.status(a).status() == State.DONE && s.status(b).status() == State.DONE);
        s.tick();
        await(() -> s.status(c).status() == State.DONE);
        assertThat(s.status(c).taskCode()).startsWith("q-");
        assertThat(s.status(c).checks()).hasSize(1);
        assertThat(s.status(c).passed()).isTrue();
    }

    @Test
    @DisplayName("다른 env 의 작업은 줍지 않고, 워커를 끄면 아무것도 줍지 않는다")
    void envAndWorkerSwitch() {
        DevTaskGeneratorService dev = mock(DevTaskGeneratorService.class);
        when(dev.generate(any(), anyLong(), any())).thenReturn(outcome("env-1"));
        TaskGenJobService mine = service(dev, uniqueEnv(), 5, true);
        TaskGenJobService other = service(dev, uniqueEnv(), 5, true);
        TaskGenJobService off = service(dev, "x", 5, false);

        String id = mine.start(form("x"), 1L);
        other.tick();
        assertThat(mine.status(id).status()).isEqualTo(State.QUEUED);
        assertThat(other.active()).isEmpty(); // 목록도 내 env 것만

        TaskGenJobService sameEnvOff = service(dev, jobRepo.findById(id).orElseThrow().getEnv(), 5, false);
        sameEnvOff.tick();
        off.tick();
        assertThat(mine.status(id).status()).isEqualTo(State.QUEUED);

        mine.tick();
        assertThat(mine.status(id).status()).isNotEqualTo(State.QUEUED);
    }

    @Test
    @DisplayName("heartbeat 가 2분 넘게 끊긴 RUNNING 작업은 QUEUED 로 돌아와 다시 실행되고, 저장된 모델 호출은 다시 부르지 않는다")
    void staleJobResumesWithoutCallingModelAgain() throws Exception {
        String env = uniqueEnv();
        var ai = new TaskPipelineServiceTest.StageAi().add(ReviewResultDto.class, new ReviewResultDto(List.of()));
        CountDownLatch stuck = new CountDownLatch(1);
        // 첫 서버: 모델을 한 번 부르고(저장됨) 멈춘다(서버가 꺼진 것처럼). 둘째 서버: 같은 호출을 하고 끝낸다
        DevTaskGeneratorService dev1 = mock(DevTaskGeneratorService.class);
        when(dev1.generate(any(), anyLong(), any())).thenAnswer(inv -> {
            new StageCallService(ai, PROPS, true).call(Stage.BLUEPRINT, "system", "user", ReviewResultDto.class);
            stuck.await(10, TimeUnit.SECONDS);
            throw new IllegalStateException("꺼진 서버라 이 결과는 쓰이지 않는다");
        });
        DevTaskGeneratorService dev2 = mock(DevTaskGeneratorService.class);
        when(dev2.generate(any(), anyLong(), any())).thenAnswer(inv -> {
            var calls = new StageCallService(ai, PROPS, true);
            calls.call(Stage.BLUEPRINT, "system", "user", ReviewResultDto.class);
            assertThat(JobCallService.current().reusedCount()).isEqualTo(1);
            return outcome("resumed-1");
        });
        TaskGenJobService first = service(dev1, env, 1, true);
        TaskGenJobService second = service(dev2, env, 1, true);

        String id = first.start(form("resume"), 1L);
        first.tick();
        await(() -> !callRepo.findByJobId(id).isEmpty()); // 첫 서버가 호출을 저장함
        assertThat(ai.models).hasSize(1);

        second.tick(); // 아직 heartbeat 가 신선해서 건드리지 않는다
        assertThat(first.status(id).status()).isEqualTo(State.RUNNING);

        jdbc.update("update task_gen_jobs set heartbeat_at = ? where id = ?",
                java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(180)), id);
        second.tick(); // 끊긴 것을 대기로 되돌리고 바로 다시 집는다
        await(() -> second.status(id).status() == State.DONE);

        assertThat(second.status(id).taskCode()).isEqualTo("resumed-1");
        assertThat(ai.models).as("저장된 호출을 써서 모델을 다시 부르지 않음").hasSize(1);
        verify(dev1, times(1)).generate(any(), anyLong(), any());
        verify(dev2, times(1)).generate(any(), anyLong(), any());
        stuck.countDown(); // 꺼졌던 서버의 스레드가 늦게 끝나도 결과를 덮어쓰지 못한다
        Thread.sleep(300);
        assertThat(second.status(id).status()).isEqualTo(State.DONE);
        assertThat(second.status(id).error()).isNull();
    }

    @Test
    @DisplayName("이어 가기: 같은 입력이 두 번(관문처럼) 나오면 nth 로 구분해 저장된 결과를 순서대로 돌려주고 비용은 한 번만 센다")
    void sameInputTwiceUsesNth() {
        String jobId = UUID.randomUUID().toString();
        var first = new ReviewResultDto(List.of(new ReviewResultDto.Violation("M2", "q", "loc", "fix")));
        var second = new ReviewResultDto(List.of());
        var ai = new TaskPipelineServiceTest.StageAi().add(ReviewResultDto.class, first).add(ReviewResultDto.class, second);

        JobCallService rec1 = new JobCallService(jobId, callRepo, json);
        rec1.bind();
        List<ReviewResultDto> got1;
        double cost1;
        try {
            var calls = new StageCallService(ai, PROPS, true);
            got1 = List.of(calls.call(Stage.REVIEW, "s", "u", ReviewResultDto.class), calls.call(Stage.REVIEW, "s", "u", ReviewResultDto.class));
            cost1 = rec1.costUsd();
        } finally {
            JobCallService.unbind();
        }
        assertThat(ai.models).hasSize(2);
        assertThat(callRepo.findByJobId(jobId)).extracting("nth").containsExactlyInAnyOrder(0, 1);

        JobCallService rec2 = new JobCallService(jobId, callRepo, json); // 다시 돌린 작업
        rec2.bind();
        try {
            var calls = new StageCallService(ai, PROPS, true);
            var again1 = calls.call(Stage.REVIEW, "s", "u", ReviewResultDto.class);
            var again2 = calls.call(Stage.REVIEW, "s", "u", ReviewResultDto.class);
            assertThat(List.of(again1, again2)).isEqualTo(got1);
            assertThat(calls.usage()).hasSize(2); // 토큰 기록도 저장된 값으로 쌓인다
            assertThat(rec2.reusedCount()).isEqualTo(2);
            assertThat(rec2.costUsd()).isCloseTo(cost1, org.assertj.core.data.Offset.offset(1e-12));
            calls.call(Stage.REVIEW, "s", "다른 입력", ReviewResultDto.class); // 저장된 게 없으면 부른다
        } finally {
            JobCallService.unbind();
        }
        assertThat(ai.models).hasSize(3);
    }

    @Test
    @DisplayName("입력 해시는 모델·강도·출력 한도·system·user·타입 중 하나만 달라도 달라진다")
    void hashChangesWithAnyInput() {
        String base = JobCallService.hash("m", "medium", 1000, "s", "u", "T");
        assertThat(base).hasSize(64).isEqualTo(JobCallService.hash("m", "medium", 1000, "s", "u", "T"));
        assertThat(java.util.Set.of(base, JobCallService.hash("m2", "medium", 1000, "s", "u", "T"), JobCallService.hash("m", "low", 1000, "s", "u", "T"),
                JobCallService.hash("m", "medium", 2000, "s", "u", "T"), JobCallService.hash("m", "medium", 1000, "s2", "u", "T"),
                JobCallService.hash("m", "medium", 1000, "s", "u2", "T"), JobCallService.hash("m", "medium", 1000, "s", "u", "T2"),
                JobCallService.hash("m", "medium", 1000, "su", "", "T"))).hasSize(8);
    }

    @Test
    @DisplayName("취소: 대기 작업만 CANCELED, 실행 중이면 409, 없으면 404")
    void cancel() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        DevTaskGeneratorService dev = mock(DevTaskGeneratorService.class);
        when(dev.generate(any(), anyLong(), any())).thenAnswer(inv -> {
            gate.await(10, TimeUnit.SECONDS);
            return outcome("cancel-1");
        });
        TaskGenJobService s = service(dev, uniqueEnv(), 1, true);
        String running = s.start(form("r"), 1L);
        String waiting = s.start(form("w"), 1L);
        s.tick();

        assertThat(s.cancel(waiting).status()).isEqualTo(State.CANCELED);
        assertThatThrownBy(() -> s.cancel(waiting)).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> s.cancel(running)).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> s.cancel("none")).isInstanceOf(NoSuchElementException.class);

        s.tick();
        assertThat(s.status(waiting).status()).isEqualTo(State.CANCELED); // 취소한 것은 줍지 않는다
        gate.countDown();
        await(() -> s.status(running).status() == State.DONE);
    }

    @Test
    @DisplayName("실패한 작업은 FAILED 와 오류 글을 남기고, 목록(active)에는 최근 끝난 것까지 나온다. batchId 로 묶음을 본다")
    void failedAndLists() throws Exception {
        DevTaskGeneratorService dev = mock(DevTaskGeneratorService.class);
        when(dev.generate(any(), anyLong(), any())).thenThrow(new IllegalStateException("모델이 죽었어요"));
        TaskGenJobService s = service(dev, uniqueEnv(), 5, true);
        TaskGenJobService.Batch batch = s.startAll(List.of(form("one"), form("two")), 1L, "BATCH", 6);
        assertThat(batch.jobIds()).hasSize(2);
        String solo = s.start(form("three"), 1L);

        assertThat(s.batch(batch.batchId())).extracting(TaskGenJobService.JobStatus::mode).containsOnly("BATCH");
        assertThat(s.batch(batch.batchId()).get(0).deadlineAt()).isNotNull();
        assertThat(s.status(solo).deadlineAt()).isNull();
        assertThat(s.status(solo).mode()).isEqualTo("NOW");

        s.tick();
        await(() -> s.status(solo).status() == State.FAILED);
        assertThat(s.status(solo).error()).contains("모델이 죽었어요");
        assertThat(s.active()).hasSize(3); // 대기·실행 + 최근 끝난 것
        assertThat(s.batch(batch.batchId())).hasSize(2);
        assertThatThrownBy(() -> s.start(form("x"), 1L, "BATCH", 2, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> s.start(form("x"), 1L, "SOON", null, null)).isInstanceOf(IllegalArgumentException.class);
    }
}
