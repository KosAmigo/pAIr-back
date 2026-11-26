package com.pair.domain.attempt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import com.pair.domain.attempt.dto.AttemptStateDto;
import com.pair.domain.attempt.dto.AttemptStateDto.Status;
import com.pair.domain.attempt.exception.TooManyRescoresException;
import com.pair.domain.attempt.repository.AttemptRepository;
import com.pair.domain.attempt.repository.AttemptStateRepository;
import com.pair.domain.attempt.service.AttemptService;
import com.pair.domain.attempt.service.RescoreService;
import com.pair.domain.auth.AuthTestSupport;
import com.pair.domain.auth.dto.AuthResponse;
import com.pair.domain.auth.service.AuthService;
import com.pair.domain.scoring.entity.Scoring;
import com.pair.domain.scoring.repository.ScoringRepository;
import com.pair.domain.scoring.service.ScoringService;
import com.pair.domain.task.TestTasks;
import com.pair.domain.task.repository.TaskRepository;
import com.pair.global.config.BearerIntegrationTest;

/** 다시 채점은 실패한 응시와 옛 방식 리포트만, 한 번에 하나씩, 24시간에 정해진 횟수까지만. 실제 채점(AI)은 부르지 않는다 */
class RescoreIntegrationTest extends BearerIntegrationTest {

    private static final int MAX = 3;

    @Autowired MockMvc mvc;
    @Autowired AuthService auth;
    @Autowired AttemptService attempts;
    @Autowired AttemptStateRepository store;
    @Autowired AttemptRepository records;
    @Autowired ScoringRepository scorings;
    @Autowired com.pair.domain.scoring.service.ScoringCatalogService catalog;
    @Autowired com.pair.domain.grader.repository.PromptVersionStateRepository prompts;
    @Autowired TaskRepository tasks;
    @Autowired TransactionTemplate transactions;

    ScoringService scoring;
    RescoreService service;
    AuthResponse owner;
    String id;

    @BeforeEach
    void setUp() {
        TestTasks.seed(tasks);
        scoring = mock(ScoringService.class);
        service = new RescoreService(store, records, scorings, scoring, transactions, Clock.systemUTC(), MAX);
        owner = AuthTestSupport.signup(auth, mail, "회원", UUID.randomUUID() + "@example.com");
        id = attempts.startSelf(TestTasks.SELF_DEV, owner.user().id()).id();
    }

    private void set(Status status) {
        AttemptStateDto a = store.load(id);
        if (a.submittedAt() == null) a = a.withSubmitted(Instant.now());
        if (a.followupsDoneAt() == null) a = a.withFollowUps(List.of(), Instant.now());
        store.save(status == Status.ERROR ? a.withError("AI 오류") : a.withStatus(status));
    }

    /** 채점 기록 하나(실패로 끝난 것) */
    private void scoredAt(Instant at) {
        Scoring s = new Scoring(Long.parseLong(id), catalog.versionId(prompts.active()), at);
        s.fail("실패", at);
        scorings.save(s);
    }

    private void rejected() {
        assertThatThrownBy(() -> service.rescore(id)).isInstanceOf(IllegalStateException.class);
        verify(scoring, never()).scoreAsync(id);
    }

    @Test
    void 실패한_응시는_다시_채점하고_오류_문구를_지운다() {
        set(Status.ERROR);
        AttemptStateDto a = service.rescore(id);
        assertThat(a.status()).isEqualTo(Status.SCORING);
        assertThat(a.error()).isNull();
        verify(scoring).scoreAsync(id);
    }

    @Test
    void 상태가_저장된_뒤에_채점을_시작한다() throws Exception {
        set(Status.ERROR);
        var seen = new AtomicReference<Status>();
        doAnswer(call -> {
            // 채점 스레드처럼 다른 트랜잭션에서 읽는다
            try (var pool = Executors.newSingleThreadExecutor()) {
                seen.set(pool.submit(() -> store.load(id).status()).get());
            }
            return null;
        }).when(scoring).scoreAsync(id);
        service.rescore(id);
        assertThat(seen.get()).isEqualTo(Status.SCORING);
    }

    @Test
    void 옛_방식_리포트는_새_기준으로_다시_채점할_수_있다() {
        set(Status.DONE);
        store.writeReport(id, Map.of("version", 1, "total", 70));
        assertThat(service.rescore(id).status()).isEqualTo(Status.SCORING);
        verify(scoring).scoreAsync(id);
    }

    @Test
    void 최신_리포트가_있으면_거절한다() {
        set(Status.DONE);
        store.writeReport(id, Map.of("version", 2, "total", 70));
        rejected();
        assertThat(store.load(id).status()).isEqualTo(Status.DONE);
    }

    @Test
    void 리포트가_없는_완료_응시는_거절한다() {
        set(Status.DONE);
        rejected();
    }

    @Test
    void 채점_중이거나_풀이_중이면_거절한다() {
        rejected();
        set(Status.SCORING);
        rejected();
    }

    @Test
    void 스물네_시간에_정해진_횟수를_넘으면_거절하고_지난_기록은_세지_않는다() {
        set(Status.ERROR);
        scoredAt(Instant.now().minus(Duration.ofHours(25)));
        for (int i = 0; i < MAX - 1; i++) scoredAt(Instant.now());
        service.rescore(id);
        verify(scoring).scoreAsync(id);

        scoredAt(Instant.now());
        set(Status.ERROR);
        assertThatThrownBy(() -> service.rescore(id)).isInstanceOf(TooManyRescoresException.class);
        verify(scoring, times(1)).scoreAsync(id);
        assertThat(store.load(id).status()).isEqualTo(Status.ERROR);
    }

    @Test
    void 동시에_여러_번_눌러도_한_번만_채점한다() throws Exception {
        set(Status.ERROR);
        var start = new CountDownLatch(1);
        List<Future<?>> all = new ArrayList<>();
        try (var pool = Executors.newFixedThreadPool(5)) {
            for (int i = 0; i < 5; i++) {
                all.add(pool.submit(() -> {
                    start.await();
                    return service.rescore(id);
                }));
            }
            start.countDown();
        }
        int ok = 0;
        for (Future<?> f : all) {
            try {
                f.get();
                ok++;
            } catch (java.util.concurrent.ExecutionException e) {
                assertThat(e.getCause()).isInstanceOf(RuntimeException.class);
            }
        }
        assertThat(ok).isEqualTo(1);
        verify(scoring, times(1)).scoreAsync(id);
    }

    @Test
    void API_는_다시_채점할_수_없는_응시에_400() throws Exception {
        set(Status.SCORING);
        mvc.perform(post("/api/attempts/{id}/rescore", id).header("Authorization", "Bearer " + owner.token()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void API_는_횟수를_넘으면_429() throws Exception {
        set(Status.ERROR);
        for (int i = 0; i < 10; i++) scoredAt(Instant.now());
        mvc.perform(post("/api/attempts/{id}/rescore", id).header("Authorization", "Bearer " + owner.token()))
                .andExpect(status().isTooManyRequests());
    }
}
