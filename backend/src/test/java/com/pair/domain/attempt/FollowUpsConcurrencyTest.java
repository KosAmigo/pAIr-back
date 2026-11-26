package com.pair.domain.attempt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.pair.domain.attempt.dto.AttemptStateDto.Status;
import com.pair.domain.attempt.repository.AttemptStateRepository;
import com.pair.domain.attempt.service.AttemptService;
import com.pair.domain.auth.AuthTestSupport;
import com.pair.domain.auth.service.AuthService;
import com.pair.domain.task.TestTasks;
import com.pair.domain.task.repository.TaskRepository;
import com.pair.global.config.BearerIntegrationTest;

/** 후속 질문 제출이 동시에 여러 번 들어와도(두 번 누름, 응시자 제출과 시험 종료 자동 마무리가 겹침) 하나만 채점을 시작한다 */
class FollowUpsConcurrencyTest extends BearerIntegrationTest {

    private static final int THREADS = 8;

    @Autowired AuthService auth;
    @Autowired AttemptService attempts;
    @Autowired AttemptStateRepository store;
    @Autowired TaskRepository tasks;

    String id;

    @BeforeEach
    void setUp() {
        TestTasks.seed(tasks);
        long user = AuthTestSupport.signup(auth, mail, "회원", UUID.randomUUID() + "@example.com").user().id();
        id = attempts.startSelf(TestTasks.SELF_DEV, user).id();
        attempts.submit(id);
    }

    @Test
    void onlyOneConcurrentFollowUpStartsScoring() throws Exception {
        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        List<Future<Boolean>> results = new ArrayList<>();
        try {
            for (int i = 0; i < THREADS; i++) {
                String answer = "답 " + i;
                results.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    try {
                        attempts.followUps(id, List.of(answer));
                        return true;
                    } catch (IllegalStateException e) {
                        return false;
                    }
                }));
            }
            ready.await();
            go.countDown();
            int succeeded = 0;
            for (Future<Boolean> f : results) if (f.get()) succeeded++;

            assertThat(succeeded).isEqualTo(1);
            // 이긴 요청의 답 하나만 저장되고, 채점 단계로 넘어갔다(채점 자체는 AI 키가 없어 실패할 수 있다)
            assertThat(store.load(id).followUpAnswers()).hasSize(1);
            assertThat(store.load(id).status()).isIn(Status.SCORING, Status.DONE, Status.ERROR);
            assertThat(store.load(id).followupsDoneAt()).isNotNull();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void secondFollowUpIsRejected() {
        attempts.followUps(id, List.of("첫 답"));
        assertThatThrownBy(() -> attempts.followUps(id, List.of("두 번째 답"))).isInstanceOf(IllegalStateException.class);
        assertThat(store.load(id).followUpAnswers()).containsExactly("첫 답");
    }
}
