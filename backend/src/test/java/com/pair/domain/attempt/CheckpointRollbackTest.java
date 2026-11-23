package com.pair.domain.attempt;

import com.pair.domain.attempt.entity.AttemptRecord;
import com.pair.domain.attempt.repository.AttemptRecordRepository;
import com.pair.domain.attempt.repository.AttemptStore;
import com.pair.domain.attempt.service.Attempt;
import com.pair.domain.attempt.service.AttemptRegistry;
import com.pair.domain.attempt.service.ChatTurn;
import com.pair.domain.attempt.service.CheckpointService;
import com.pair.domain.attempt.service.ReplayTreeService;
import com.pair.domain.attempt.service.RollbackService;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.mail.javamail.JavaMailSender;

import com.pair.domain.attempt.repository.CheckpointFileRecordRepository;
import com.pair.domain.attempt.repository.CheckpointRecordRepository;
import com.pair.domain.credit.entity.CreditLedger;
import com.pair.domain.credit.repository.CreditLedgerRepository;
import com.pair.domain.credit.service.CreditService;
import com.pair.domain.credit.service.InsufficientCreditException;
import com.pair.domain.task.repository.TaskRecordRepository;
import com.pair.domain.task.repository.TaskFileRecordRepository;
import com.pair.domain.task.repository.TaskQuestionRecordRepository;
import com.pair.domain.task.repository.TaskRepository;
import com.pair.domain.task.TestTasks;
import com.pair.domain.auth.entity.AuthAccount;
import com.pair.domain.auth.repository.AuthAccountRepository;
import com.pair.domain.auth.dto.AuthDtos;
import com.pair.domain.auth.service.AuthService;
import com.pair.domain.auth.AuthTestSupport;

/** 체크포인트 자동 생성(바뀐 파일만), 시점 복원, 롤백(분기), 복기 트리 (H2 메모리 DB) */
@SpringBootTest
@ActiveProfiles("examtest")
class CheckpointRollbackTest {

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("pair.data-dir", () -> dataDir.toString());
    }

    @Autowired AttemptStore store;
    @Autowired AttemptRegistry registry;
    @Autowired AttemptRecordRepository records;
    @Autowired TaskRecordRepository taskRecords;
    @Autowired TaskFileRecordRepository taskFiles;
    @Autowired TaskQuestionRecordRepository taskQuestions;
    @Autowired TaskRepository tasks;
    @MockitoSpyBean CheckpointService checkpoints;
    @Autowired RollbackService rollbacks;
    @Autowired CheckpointRecordRepository checkpointRecords;
    @Autowired CheckpointFileRecordRepository checkpointFiles;
    @Autowired AuthAccountRepository accounts;
    @Autowired ReplayTreeService replayTree;
    @Autowired AuthService auth;
    @Autowired CreditService credits;
    @MockitoSpyBean CreditLedgerRepository ledger;
    @MockitoBean JavaMailSender mail;

    Long userId;
    String id;
    String path;
    String original;
    String edited;

    @BeforeEach
    void setUp() {
        TestTasks.seed(taskRecords, taskFiles, taskQuestions);
        userId = AuthTestSupport.signup(auth, mail, "응시자", UUID.randomUUID() + "@pair.test")
                .user().id();
        Map<String, String> files = tasks.visibleFiles(TestTasks.SELF_DEV);
        path = files.keySet().iterator().next();
        original = files.get(path);
        edited = original + "\n// 고침";

        AttemptRecord r = registry.save(AttemptRecord.self(userId, 1L, "gpt-test", Instant.now()));
        id = String.valueOf(r.getId());
        store.create(attempt(id), files);
        // 질문 1(파일 그대로) → 답 2 → 파일을 고침 → 질문 3 → 답 4
        ask(id, 1, files);
        store.appendTurn(id, turn(2, "ai", "답1"));
        Map<String, String> after = new java.util.TreeMap<>(files);
        after.put(path, edited);
        ask(id, 3, after);
        store.appendTurn(id, turn(4, "ai", "답3"));
    }

    @Test
    void 체크포인트는_질문마다_만들고_바뀐_파일만_저장한다() {
        List<CheckpointService.View> list = checkpoints.list(id);
        assertThat(list).extracting(CheckpointService.View::turnNo).containsExactly(1, 3);
        // 질문 1은 과제 원본과 같아서 파일 0행, 질문 3은 고친 파일 1행
        assertThat(checkpointFiles.findUpTo(Long.valueOf(id), 1)).isEmpty();
        assertThat(checkpointFiles.findUpTo(Long.valueOf(id), 3)).hasSize(1);
    }

    @Test
    void 질문_시점의_파일로_복원한다() {
        assertThat(checkpoints.restore(id, 1).get(path)).isEqualTo(original);
        assertThat(checkpoints.restore(id, 3).get(path)).isEqualTo(edited);
    }

    @Test
    void 롤백하면_그_시점_파일과_앞_대화로_새_응시가_갈라지고_크레딧을_차감한다() {
        Long cp3 = checkpointRecords.findByAttemptIdOrderByTurnNoAsc(Long.valueOf(id)).get(1).getId();

        RollbackService.Result res = rollbacks.rollback(userId, cp3);

        assertThat(res.attemptId()).isNotEqualTo(id);
        AttemptRecord fork = records.findById(Long.valueOf(res.attemptId())).orElseThrow();
        assertThat(fork.getForkedFromCheckpointId()).isEqualTo(cp3);
        assertThat(fork.getMode()).isEqualTo(AttemptRecord.Mode.SELF);
        assertThat(fork.getStatus()).isEqualTo(Attempt.Status.SOLVING);
        assertThat(store.readFiles(res.attemptId()).get(path)).isEqualTo(edited);
        // 질문 3 앞의 대화(1, 2)만 같은 번호로 복사된다
        assertThat(store.turns(res.attemptId())).extracting(ChatTurn::turn).containsExactly(1, 2);
        // 원래 응시는 그대로
        assertThat(store.turns(id)).hasSize(4);
        assertThat(credits.balance(userId)).isEqualTo(190);
        CreditLedger entry = ledger.findFirstByUserIdOrderByIdDesc(userId).orElseThrow();
        assertThat(entry.getReason()).isEqualTo("ROLLBACK");
        assertThat(entry.getAmount()).isEqualTo(-10);
        assertThat(entry.getAttemptId()).isEqualTo(fork.getId());
    }

    @Test
    void 남의_체크포인트는_롤백할_수_없다() {
        Long other = accounts.save(new AuthAccount(UUID.randomUUID() + "@pair.test", "다른 사람", "x")).getId();
        Long cp1 = checkpointRecords.findByAttemptIdOrderByTurnNoAsc(Long.valueOf(id)).get(0).getId();

        assertThatThrownBy(() -> rollbacks.rollback(other, cp1)).isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void 복기_트리는_뿌리부터_모든_갈래를_부모와_갈라진_질문_시점과_함께_돌려준다() {
        Long cp3 = checkpointRecords.findByAttemptIdOrderByTurnNoAsc(Long.valueOf(id)).get(1).getId();
        String a = rollbacks.rollback(userId, cp3).attemptId();                   // 원래 응시 → A (질문 3에서)
        ask(a, 3, checkpoints.restore(a, Integer.MAX_VALUE));                      // A 에서 새 질문 3
        Long aCp = checkpointRecords.findByAttemptIdOrderByTurnNoAsc(Long.valueOf(a)).get(0).getId();
        String b = rollbacks.rollback(userId, aCp).attemptId();                   // A → B (질문 3에서)
        assertThat(credits.balance(userId)).isEqualTo(180);

        // 어느 노드에서 물어도 같은 트리
        for (String from : List.of(id, a, b)) {
            List<ReplayTreeService.Node> nodes = replayTree.tree(userId, from);
            assertThat(nodes).extracting(ReplayTreeService.Node::attemptId).containsExactly(id, a, b);
            assertThat(nodes.get(0).parentAttemptId()).isNull();
            assertThat(nodes.get(1).parentAttemptId()).isEqualTo(id);
            assertThat(nodes.get(1).forkedAtTurn()).isEqualTo(3);
            assertThat(nodes.get(2).parentAttemptId()).isEqualTo(a);
            assertThat(nodes.get(2).forkedFromCheckpointId()).isEqualTo(aCp);
        }
    }

    @Test
    void 체크포인트에_이름을_붙이고_지운다() {
        Long cp1 = checkpointRecords.findByAttemptIdOrderByTurnNoAsc(Long.valueOf(id)).get(0).getId();

        assertThat(checkpoints.rename(userId, cp1, "  처음 접근  ").label()).isEqualTo("처음 접근");
        assertThat(checkpoints.list(id).get(0).label()).isEqualTo("처음 접근");

        assertThat(checkpoints.rename(userId, cp1, "   ").label()).isNull();
        assertThat(checkpoints.list(id).get(0).label()).isNull();
    }

    @Test
    void 이름이_너무_길거나_남의_체크포인트면_바꿀_수_없다() {
        Long cp1 = checkpointRecords.findByAttemptIdOrderByTurnNoAsc(Long.valueOf(id)).get(0).getId();
        Long other = accounts.save(new AuthAccount(UUID.randomUUID() + "@pair.test", "다른 사람", "x")).getId();

        assertThatThrownBy(() -> checkpoints.rename(userId, cp1, "가".repeat(101))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> checkpoints.rename(other, cp1, "내 것")).isInstanceOf(NoSuchElementException.class);
        assertThat(checkpoints.list(id).get(0).label()).isNull();
    }

    @Test
    void 복기_트리_노드에_채점이_끝난_응시의_점수가_붙는다() {
        Long cp3 = checkpointRecords.findByAttemptIdOrderByTurnNoAsc(Long.valueOf(id)).get(1).getId();
        String fork = rollbacks.rollback(userId, cp3).attemptId();

        // 원래 응시만 채점이 끝났다
        Attempt a = store.load(id);
        Instant t = Instant.now();
        store.save(a.withSubmitted(t).withFollowUps(List.of(), List.of(), t).withStatus(Attempt.Status.DONE));
        store.writeReport(id, java.util.Map.of("version", 2, "total", 80, "max", 100,
                "items", List.of(java.util.Map.of("key", "usage", "name", "AI 활용력", "max", 20, "score", 16))));

        var nodes = replayTree.tree(userId, fork);
        var root = nodes.get(0);
        assertThat(root.attemptId()).isEqualTo(id);
        assertThat(root.score().total()).isEqualTo(80);
        assertThat(root.score().max()).isEqualTo(100);
        assertThat(root.score().items()).hasSize(1);
        assertThat(root.score().items().get(0).name()).isEqualTo("AI 활용력");
        assertThat(root.score().items().get(0).score()).isEqualTo(16);
        // 아직 풀이 중인 갈래는 점수가 없다
        assertThat(nodes.get(1).attemptId()).isEqualTo(fork);
        assertThat(nodes.get(1).score()).isNull();

        // 이전 형식 리포트(다시 채점 필요)는 점수를 주지 않는다
        store.writeReport(id, java.util.Map.of("version", 1, "total", 70));
        assertThat(replayTree.tree(userId, fork).get(0).score()).isNull();
    }

    @Test
    void 남의_복기_트리는_볼_수_없다() {
        Long other = accounts.save(new AuthAccount(UUID.randomUUID() + "@pair.test", "다른 사람", "x")).getId();
        assertThatThrownBy(() -> replayTree.tree(other, id)).isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void 같은_체크포인트_반복_롤백은_각각_차감한다() {
        Long cp = checkpointRecords.findByAttemptIdOrderByTurnNoAsc(Long.valueOf(id)).get(0).getId();
        String first = rollbacks.rollback(userId, cp).attemptId();
        String second = rollbacks.rollback(userId, cp).attemptId();
        assertThat(first).isNotEqualTo(second);
        assertThat(credits.balance(userId)).isEqualTo(180);
        assertThat(ledger.findFirstByUserIdOrderByIdDesc(userId).orElseThrow().getAttemptId())
                .isEqualTo(Long.valueOf(second));
    }

    @Test
    void 잔액_부족이면_응시와_원장을_만들지_않는다() {
        Long cp = checkpointRecords.findByAttemptIdOrderByTurnNoAsc(Long.valueOf(id)).get(0).getId();
        for (int i = 0; i < 20; i++) rollbacks.rollback(userId, cp);
        int before = records.findByUserIdAndModeOrderByStartedAtDesc(userId, AttemptRecord.Mode.SELF).size();
        long ledgerCount = ledger.count();
        assertThatThrownBy(() -> rollbacks.rollback(userId, cp)).isInstanceOf(InsufficientCreditException.class);
        assertThat(credits.balance(userId)).isZero();
        assertThat(records.findByUserIdAndModeOrderByStartedAtDesc(userId, AttemptRecord.Mode.SELF)).hasSize(before);
        assertThat(ledger.count()).isEqualTo(ledgerCount);
    }

    @Test
    void 복원_실패시_응시와_차감이_모두_취소된다() {
        Long cp = checkpointRecords.findByAttemptIdOrderByTurnNoAsc(Long.valueOf(id)).get(0).getId();
        int before = records.findByUserIdAndModeOrderByStartedAtDesc(userId, AttemptRecord.Mode.SELF).size();
        long ledgerCount = ledger.count();
        doThrow(new IllegalStateException("restore failed")).when(checkpoints).restore(eq(id), anyInt());
        assertThatThrownBy(() -> rollbacks.rollback(userId, cp))
                .isInstanceOf(IllegalStateException.class).hasMessage("restore failed");
        assertThat(records.findByUserIdAndModeOrderByStartedAtDesc(userId, AttemptRecord.Mode.SELF)).hasSize(before);
        assertThat(ledger.count()).isEqualTo(ledgerCount);
        assertThat(credits.balance(userId)).isEqualTo(200);
    }

    @Test
    void 원장_저장_실패시_만들어진_응시와_대화도_취소된다() {
        Long cp = checkpointRecords.findByAttemptIdOrderByTurnNoAsc(Long.valueOf(id)).get(1).getId();
        int before = records.findByUserIdAndModeOrderByStartedAtDesc(userId, AttemptRecord.Mode.SELF).size();
        long ledgerCount = ledger.count();
        doThrow(new IllegalStateException("ledger failed")).when(ledger).save(any(CreditLedger.class));
        assertThatThrownBy(() -> rollbacks.rollback(userId, cp))
                .isInstanceOf(IllegalStateException.class).hasMessage("ledger failed");
        assertThat(records.findByUserIdAndModeOrderByStartedAtDesc(userId, AttemptRecord.Mode.SELF)).hasSize(before);
        assertThat(ledger.count()).isEqualTo(ledgerCount);
        assertThat(credits.balance(userId)).isEqualTo(200);
        assertThat(store.turns(id)).hasSize(4);
    }

    @Test
    void 동시_롤백은_잔액을_초과할_수_없다() throws Exception {
        Long cp = checkpointRecords.findByAttemptIdOrderByTurnNoAsc(Long.valueOf(id)).get(0).getId();
        for (int i = 0; i < 19; i++) rollbacks.rollback(userId, cp);
        var ready = new CountDownLatch(2);
        var go = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var results = java.util.stream.IntStream.range(0, 2).mapToObj(i -> executor.submit(() -> {
                ready.countDown();
                if (!go.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("timed out");
                try {
                    rollbacks.rollback(userId, cp);
                    return true;
                } catch (InsufficientCreditException e) {
                    return false;
                }
            })).toList();
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            assertThat(results.get(0).get(30, TimeUnit.SECONDS) ^ results.get(1).get(30, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(credits.balance(userId)).isZero();
        assertThat(records.findByUserIdAndModeOrderByStartedAtDesc(userId, AttemptRecord.Mode.SELF)).hasSize(21);
    }

    // ── 도우미 ───────────────────────────

    void ask(String attemptId, int turn, Map<String, String> files) {
        checkpoints.appendQuestion(attemptId, turn(turn, "user", "질문" + turn), files);
    }

    static ChatTurn turn(int no, String role, String text) {
        return new ChatTurn(no, role, Instant.now(), text, null, null, null, null, null, null);
    }

    static Attempt attempt(String id) {
        return new Attempt(id, TestTasks.SELF_DEV, "openai", "gpt-test", Instant.now(), null, null, null,
                Attempt.Status.SOLVING, List.of(), null, null);
    }
}
