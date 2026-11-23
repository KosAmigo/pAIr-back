package com.pair.domain.attempt;

import com.pair.domain.attempt.entity.Attempt;
import com.pair.domain.attempt.repository.AttemptStateRepository;
import com.pair.domain.attempt.dto.AttemptStateDto;
import com.pair.domain.attempt.service.AttemptRegistryService;
import com.pair.domain.attempt.dto.ChatTurnStateDto;
import com.pair.domain.attempt.service.CheckpointService;
import com.pair.domain.attempt.service.RollbackService;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.pair.domain.attempt.repository.CheckpointRepository;
import com.pair.domain.task.repository.TaskDefRepository;
import com.pair.domain.auth.service.AuthService;
import com.pair.domain.auth.AuthTestSupport;

/**
 * 체크포인트 복원 시간을 실제 PostgreSQL 에서 잰다 (명세 목표: 1초 이내, 질문 40개까지). 빌드에서는 건너뛴다.
 * 질문 N개짜리 응시를 만들고 질문 N 시점 복원(변경분을 가장 많이 덮어야 하는 최악 지점)과 그 체크포인트로의 롤백을 잰다.
 *   PAIR_BENCH=1 PAIR_CREDIT_SIGNUP=500 PAIR_PG_TEST_URL=jdbc:postgresql://localhost:55433/pair PAIR_PG_TEST_PASSWORD=verify \
 *     sh gradlew test --tests '*CheckpointRestoreBenchTest'
 * 결과 표는 build/checkpoint-restore-bench.txt 에 남는다.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "PAIR_PG_TEST_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "PAIR_BENCH", matches = ".+")
class CheckpointRestoreBenchTest {

    static final int[] LEVELS = {1, 5, 10, 20, 40};
    static final int RESTORE_REPEAT = 30;

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("pair.data-dir", () -> dataDir.toString());
        r.add("spring.datasource.url", () -> System.getenv("PAIR_PG_TEST_URL"));
        r.add("spring.datasource.username", () -> System.getenv().getOrDefault("PAIR_PG_TEST_USER", "postgres"));
        r.add("spring.datasource.password", () -> System.getenv("PAIR_PG_TEST_PASSWORD"));
        r.add("pair.exam.scheduler-enabled", () -> "false");
        r.add("spring.ai.openai.api-key", () -> "");
        r.add("spring.ai.anthropic.api-key", () -> "");
    }

    @Autowired AttemptStateRepository store;
    @Autowired AttemptRegistryService registry;
    @Autowired TaskDefRepository tasks;
    @Autowired CheckpointService checkpoints;
    @Autowired RollbackService rollbacks;
    @Autowired CheckpointRepository checkpointRecords;
    @Autowired AuthService auth;
    @MockitoBean JavaMailSender mail;

    @Test
    void measure() throws IOException {
        Long userId = AuthTestSupport.signup(auth, mail, "측정", UUID.randomUUID() + "@pair.test").user().id();
        Map<String, String> original = tasks.visibleFiles("order-deadlock");
        int totalBytes = original.values().stream().mapToInt(String::length).sum();

        StringBuilder out = new StringBuilder();
        out.append("과제 order-deadlock: 파일 ").append(original.size()).append("개, 원본 ").append(totalBytes).append("자\n");
        out.append("복원 = 질문 N 시점 복원을 ").append(RESTORE_REPEAT).append("번 반복(첫 1번은 예열이라 뺌), 롤백 = 그 체크포인트로 1번\n\n");
        for (boolean allFiles : new boolean[] {false, true}) {
            out.append(allFiles ? "[최악] 질문마다 모든 파일이 바뀜\n" : "[보통] 질문마다 파일 1개가 바뀜\n");
            out.append(String.format("%-6s %-10s %-10s %-10s %-10s %-10s%n", "질문", "저장행", "복원중앙(ms)", "복원p95", "복원최대", "롤백(ms)"));
            for (int n : LEVELS) {
                Row row = run(userId, original, n, allFiles);
                out.append(String.format("%-6d %-10d %-12.1f %-10.1f %-10.1f %-10.1f%n", n, row.storedRows, row.median, row.p95, row.max, row.rollbackMs));
                assertThat(row.max).as("질문 %d개 복원 최대", n).isLessThan(1000);
            }
            out.append('\n');
        }
        Path file = Path.of("build", "checkpoint-restore-bench.txt");
        Files.createDirectories(file.getParent());
        Files.writeString(file, out.toString());
        System.out.println(out);
    }

    record Row(int storedRows, double median, double p95, double max, double rollbackMs) {}

    Row run(Long userId, Map<String, String> original, int questions, boolean allFiles) {
        Attempt r = registry.save(Attempt.self(userId, 1L, "gpt-test", Instant.now()));
        String id = String.valueOf(r.getId());
        store.create(attempt(id), original);
        List<String> paths = new ArrayList<>(original.keySet());
        Map<String, String> cur = new TreeMap<>(original);
        int stored = 0;
        for (int q = 1; q <= questions; q++) {
            List<String> targets = allFiles ? paths : List.of(paths.get((q - 1) % paths.size()));
            for (String p : targets) cur.put(p, cur.get(p) + "\n// 질문 " + q + " 에서 고침");
            checkpoints.appendQuestion(id, turn(2 * q - 1, "user"), cur);
            store.appendTurn(id, turn(2 * q, "ai"));
            stored += targets.size();
        }
        // 체크포인트의 turn_no 는 질문 턴 번호(1, 3, 5, ...)라서 마지막 질문 = 2N-1
        int lastTurn = 2 * questions - 1;
        checkpoints.restore(id, lastTurn); // 예열
        List<Double> ms = new ArrayList<>();
        for (int i = 0; i < RESTORE_REPEAT; i++) {
            long t = System.nanoTime();
            Map<String, String> got = checkpoints.restore(id, lastTurn);
            ms.add((System.nanoTime() - t) / 1e6);
            if (i == 0) assertThat(got).isEqualTo(cur);
        }
        Collections.sort(ms);
        Long lastCp = checkpointRecords.findByAttemptIdOrderByTurnNoAsc(Long.valueOf(id)).get(questions - 1).getId();
        long t = System.nanoTime();
        rollbacks.rollback(userId, lastCp);
        double rollbackMs = (System.nanoTime() - t) / 1e6;
        return new Row(stored, ms.get(ms.size() / 2), ms.get((int) Math.ceil(ms.size() * 0.95) - 1), ms.get(ms.size() - 1), rollbackMs);
    }

    static ChatTurnStateDto turn(int no, String role) {
        return new ChatTurnStateDto(no, role, Instant.now(), role + no, null, null, null, null, null, null);
    }

    static AttemptStateDto attempt(String id) {
        return new AttemptStateDto(id, "order-deadlock", "openai", "gpt-test", Instant.now(), null, null, null,
                AttemptStateDto.Status.SOLVING, List.of(), null, null);
    }
}
