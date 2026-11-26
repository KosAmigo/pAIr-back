package com.pair.domain.taskgen.service;

import org.junit.jupiter.api.Tag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.pair.domain.task.entity.Task;
import com.pair.domain.task.repository.TaskFileRepository;
import com.pair.domain.task.repository.TaskRepository;
import com.pair.domain.taskgen.dto.DevDraftDto;
import com.pair.domain.taskgen.repository.GeneratedTaskRepository;

/** 저장 중 code 유니크 충돌이 나면 다음 번호(-2, -3 …)로 다시 저장하는지 확인한다 (H2) */
@SpringBootTest
@ActiveProfiles("examtest")
@Tag("slow")
class GeneratedTaskSaveRetryTest {

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("pair.data-dir", () -> dataDir.toString());
    }

    @MockitoBean JavaMailSender mail;
    @MockitoSpyBean TaskRepository records;
    @Autowired TaskFileRepository files;
    @Autowired GeneratedTaskRepository writer;

    /** 스파이로 바꾼 findByCode 대신 같은 일을 하는 다른 조회로 진짜 결과를 얻는다 */
    private Optional<Task> realFind(String code) {
        return records.findByCodeIn(List.of(code)).stream().findFirst();
    }

    private static DevDraftDto draft(String code) {
        return TaskGenFixtures.edit(TaskGenFixtures.good(), b -> b.code(code));
    }

    private static TaskGeneratorService.GenReport report() {
        return new TaskGeneratorService.GenReport("test", 1, 0, 0, 0, true, TaskGenFixtures.form(), List.of(), null, Map.of());
    }

    @Test
    void savedSettingsKeepAiLimitsByMinutes() throws Exception {
        // E6: 생성기가 저장할 때 과제별 AI 제한(제한시간 기본값)을 settings 에 함께 넣는다
        var form = TaskGenFixtures.form();
        String code = writer.saveDev(form, draft("limits-solo"), report(), 1L);
        var settings = new com.fasterxml.jackson.databind.ObjectMapper().readTree(realFind(code).orElseThrow().getSettings());
        var expected = com.pair.domain.task.dto.AiLimitsDto.forMinutes(form.minutes());
        assertThat(settings.path("aiTurns").asInt()).isEqualTo(expected.turns());
        assertThat(settings.path("aiMaxAttach").asInt()).isEqualTo(expected.maxAttach());
        assertThat(settings.path("aiContextChars").asInt()).isEqualTo(expected.contextChars());
    }

    @Test
    void conflictAtSaveRetriesWithNextCode() {
        writer.saveDev(TaskGenFixtures.form(), draft("retry-solo"), report(), 1L);

        // 확인 조회는 "없다"고 답하게 해서(동시 저장 흉내), 저장 시점에 유니크 충돌이 나게 한다
        AtomicInteger calls = new AtomicInteger();
        doAnswer(inv -> calls.getAndIncrement() == 0 ? Optional.empty() : realFind(inv.getArgument(0)))
                .when(records).findByCode(eq("retry-solo"));

        String code = writer.saveDev(TaskGenFixtures.form(), draft("retry-solo"), report(), 1L);

        assertThat(code).isEqualTo("retry-solo-2");
        Task first = records.findByCode("retry-solo").orElseThrow();
        Task second = records.findByCode("retry-solo-2").orElseThrow();
        assertThat(second.getId()).isNotEqualTo(first.getId());
        // 실패한 시도의 파일이 남지 않았다: 과제마다 같은 수의 파일
        long n1 = files.findAll().stream().filter(f -> first.getId().equals(f.getTaskId())).count();
        long n2 = files.findAll().stream().filter(f -> second.getId().equals(f.getTaskId())).count();
        assertThat(n2).isEqualTo(n1);
    }

    @Test
    void twoThreadsSavingSameCodeBothSucceed() throws Exception {
        CyclicBarrier bothChecked = new CyclicBarrier(2);
        AtomicInteger calls = new AtomicInteger();
        // 두 스레드 모두 첫 확인 조회를 마칠 때까지 기다렸다가 저장 → 둘 다 같은 code 를 고른다
        doAnswer(inv -> {
            Object r = realFind(inv.getArgument(0));
            if (calls.getAndIncrement() < 2) bothChecked.await(10, TimeUnit.SECONDS);
            return r;
        }).when(records).findByCode(eq("retry-race"));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<String> a = pool.submit(() -> writer.saveDev(TaskGenFixtures.form(), draft("retry-race"), report(), 1L));
            Future<String> b = pool.submit(() -> writer.saveDev(TaskGenFixtures.form(), draft("retry-race"), report(), 2L));
            List<String> codes = List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
            assertThat(codes).doesNotHaveDuplicates().contains("retry-race");
            assertThat(records.findByCode("retry-race")).isPresent();
            assertThat(records.findByCode(codes.stream().filter(c -> !c.equals("retry-race")).findFirst().orElseThrow())).isPresent();
        } finally {
            pool.shutdownNow();
        }
    }
}
