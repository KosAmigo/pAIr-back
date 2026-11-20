package com.pair.attempt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.TreeMap;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.mail.javamail.JavaMailSender;

import com.pair.attempt.db.CheckpointRecordRepository;
import com.pair.task.TaskRepository;
import com.pair.web.auth.AuthAccount;
import com.pair.web.auth.AuthAccountRepository;
import com.pair.web.auth.AuthDtos;
import com.pair.web.auth.AuthService;
import com.pair.web.auth.AuthTestSupport;
import com.pair.credit.CreditService;
import com.pair.credit.CreditLedgerRepository;

/**
 * 체크포인트·롤백·복기 트리를 운영과 같은 Flyway 스키마(PostgreSQL)에서 확인한다. H2 테스트(CheckpointRollbackTest)가
 * 못 보는 복합 FK, CHECK, 엔티티-스키마 일치(ddl-auto=validate)를 본다.
 * 팀 공용 RDS 를 건드리지 않도록 PAIR_PG_TEST_URL 이 있을 때만 돌고, 빈 로컬 DB 에 V1~ 마이그레이션을 새로 적용한다.
 *   docker run -d -p 55432:5432 -e POSTGRES_PASSWORD=verify -e POSTGRES_DB=pair postgres:16
 *   PAIR_PG_TEST_URL=jdbc:postgresql://localhost:55432/pair PAIR_PG_TEST_PASSWORD=verify sh gradlew test --tests '*PostgresTest'
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "PAIR_PG_TEST_URL", matches = ".+")
class CheckpointRollbackPostgresTest {

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("pair.data-dir", () -> dataDir.toString());
        r.add("spring.datasource.url", () -> System.getenv("PAIR_PG_TEST_URL"));
        r.add("spring.datasource.username", () -> System.getenv().getOrDefault("PAIR_PG_TEST_USER", "postgres"));
        r.add("spring.datasource.password", () -> System.getenv("PAIR_PG_TEST_PASSWORD"));
        r.add("pair.exam.scheduler-enabled", () -> "false");
    }

    @Autowired AttemptStore store;
    @Autowired AttemptRegistry registry;
    @Autowired AttemptRecordRepository records;
    @Autowired TaskRepository tasks;
    @Autowired CheckpointService checkpoints;
    @Autowired RollbackService rollbacks;
    @Autowired ReplayTreeService replayTree;
    @Autowired CheckpointRecordRepository checkpointRecords;
    @Autowired AuthAccountRepository accounts;
    @Autowired AuthService auth;
    @Autowired CreditService credits;
    @Autowired CreditLedgerRepository ledger;
    @MockitoBean JavaMailSender mail;

    @Test
    void 체크포인트_롤백_복기_트리가_운영_스키마에서_돈다() {
        // V3 기본 데이터: 과제 1 = order-deadlock (자가진단)
        Long userId = AuthTestSupport.signup(auth, mail, "응시자", UUID.randomUUID() + "@pair.test")
                .user().id();
        Map<String, String> files = tasks.visibleFiles("order-deadlock");
        String path = files.keySet().iterator().next();
        String edited = files.get(path) + "\n// 고침";

        AttemptRecord r = registry.save(AttemptRecord.self(userId, 1L, "gpt-test", Instant.now()));
        String id = String.valueOf(r.getId());
        store.create(attempt(id), files);
        checkpoints.appendQuestion(id, turn(1, "user"), files);
        store.appendTurn(id, turn(2, "ai"));
        Map<String, String> after = new TreeMap<>(files);
        after.put(path, edited);
        checkpoints.appendQuestion(id, turn(3, "user"), after);
        store.appendTurn(id, turn(4, "ai"));

        // 체크포인트: 질문마다 1개, 복원
        assertThat(checkpoints.list(id)).extracting(CheckpointService.View::turnNo).containsExactly(1, 3);
        assertThat(checkpoints.restore(id, 1).get(path)).isEqualTo(files.get(path));
        assertThat(checkpoints.restore(id, 3).get(path)).isEqualTo(edited);

        // 롤백: 새 응시가 갈라지고 파일·앞 대화가 복원된다
        Long cp3 = checkpointRecords.findByAttemptIdOrderByTurnNoAsc(Long.valueOf(id)).get(1).getId();
        String forkId = rollbacks.rollback(userId, cp3).attemptId();
        assertThat(credits.balance(userId)).isEqualTo(190);
        var charge = ledger.findFirstByUserIdOrderByIdDesc(userId).orElseThrow();
        assertThat(charge.getReason()).isEqualTo("ROLLBACK");
        assertThat(charge.getAttemptId()).isEqualTo(Long.valueOf(forkId));
        AttemptRecord fork = records.findById(Long.valueOf(forkId)).orElseThrow();
        assertThat(fork.getForkedFromCheckpointId()).isEqualTo(cp3);
        assertThat(store.readFiles(forkId).get(path)).isEqualTo(edited);
        assertThat(store.turns(forkId)).extracting(ChatTurn::turn).containsExactly(1, 2);
        assertThat(store.turns(id)).hasSize(4);

        // 갈라진 응시에서 새 질문 3 → 한 번 더 롤백, 트리
        checkpoints.appendQuestion(forkId, turn(3, "user"), checkpoints.restore(forkId, Integer.MAX_VALUE));
        Long forkCp = checkpointRecords.findByAttemptIdOrderByTurnNoAsc(Long.valueOf(forkId)).get(0).getId();
        String grandId = rollbacks.rollback(userId, forkCp).attemptId();
        assertThat(credits.balance(userId)).isEqualTo(180);
        var nodes = replayTree.tree(userId, grandId);
        assertThat(nodes).extracting(ReplayTreeService.Node::attemptId).containsExactly(id, forkId, grandId);
        assertThat(nodes.get(2).parentAttemptId()).isEqualTo(forkId);

        // 남의 체크포인트는 못 쓴다
        Long other = accounts.save(new AuthAccount(UUID.randomUUID() + "@pair.test", "다른 사람", "x")).getId();
        assertThatThrownBy(() -> rollbacks.rollback(other, cp3)).isInstanceOf(NoSuchElementException.class);
    }

    static ChatTurn turn(int no, String role) {
        return new ChatTurn(no, role, Instant.now(), role + no, null, null, null, null, null, null);
    }

    static Attempt attempt(String id) {
        return new Attempt(id, "order-deadlock", "openai", "gpt-test", Instant.now(), null, null, null,
                Attempt.Status.SOLVING, List.of(), null, null);
    }
}
