package com.pair.domain.taskgen.service;

import org.junit.jupiter.api.Tag;

import com.pair.domain.taskgen.repository.GeneratedTaskRepository;
import com.pair.domain.taskgen.dto.TaskGenRequest;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pair.domain.task.repository.TaskRepository;

/**
 * 실제 OpenAI 와 실제 LocalJvmRunner 로 과제 1개를 만든다 (H2 테스트 DB, 비용 발생).
 * 환경 변수 PAIR_TASKGEN_LIVE=1 일 때만 돈다. 키는 application-secret.yml 이나 환경 변수 OPENAI_API_KEY 에서 읽는다.
 * 팀 공용 RDS 에는 쓰지 않는다 (examtest 프로필이 H2 로 바꿔 둔다). 결과는 build/taskgen-live.json 에 남는다.
 */
@SpringBootTest
@ActiveProfiles("examtest")
@EnabledIfEnvironmentVariable(named = "PAIR_TASKGEN_LIVE", matches = "1")
@Tag("slow")
class TaskGenLiveTest {

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("pair.data-dir", () -> dataDir.toString());
        // examtest 프로필은 AI 키를 비워 둔다. 이 테스트만 application-secret.yml·환경 변수의 실제 키를 다시 넣는다
        r.add("spring.ai.openai.api-key", () -> "${OPENAI_API_KEY:}");
        r.add("spring.ai.anthropic.api-key", () -> "${ANTHROPIC_API_KEY:}");
    }

    @MockitoBean JavaMailSender mail;
    @Autowired DevTaskGeneratorService generator;
    @Autowired BizTaskGeneratorService bizGenerator;
    @Autowired GeneratedTaskRepository writer;
    @Autowired TaskRepository records;
    @Autowired ObjectMapper json;

    @Test
    void realGeneration() throws Exception {
        TaskGenRequest form = new TaskGenRequest("DEV", "쿠폰 할인 계산", "쿠폰 두 장을 겹쳐 쓰면 할인이 중복 적용됨", "SELF", 60).validated();
        long t = System.nanoTime();
        var o = generator.generate(form, 1L, step -> System.out.println("[live] " + step));
        long sec = Math.round((System.nanoTime() - t) / 1e9);

        String report = writer.report(o.taskCode());
        Files.writeString(Path.of("build", "taskgen-live.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(json.readTree(report)));
        System.out.println("[live] 통과=" + o.passed() + " 시도=" + o.attempts() + " 걸린 시간=" + sec + "초 code=" + o.taskCode());
        o.checks().forEach(c -> System.out.println("[live]   " + (c.ok() ? "OK  " : "FAIL") + " " + c.name() + " - " + c.detail()));

        // 통과 여부와 상관없이 검수 전으로 저장되어야 한다
        assertThat(records.findByCode(o.taskCode())).isPresent();
    }

    /** 비개발: 실제 OpenAI 생성 + 실제 Claude 풀이 + 실제 PDF (로컬 파일 저장소). 결과는 build/taskgen-live-biz.json */
    @Test
    void realBizGeneration() throws Exception {
        TaskGenRequest form = new TaskGenRequest("BIZ", "저가 요금제 출시 여부", "설문의 전환 의향을 실제 전환율로 보정하지 않음", "SELF", 60).validated();
        long t = System.nanoTime();
        var o = bizGenerator.generate(form, 1L, step -> System.out.println("[live-biz] " + step));
        long sec = Math.round((System.nanoTime() - t) / 1e9);

        String report = writer.report(o.taskCode());
        Files.writeString(Path.of("build", "taskgen-live-biz.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(json.readTree(report)));
        System.out.println("[live-biz] 통과=" + o.passed() + " 시도=" + o.attempts() + " 걸린 시간=" + sec + "초 code=" + o.taskCode());
        o.checks().forEach(c -> System.out.println("[live-biz]   " + (c.ok() ? "OK  " : "FAIL") + " " + c.name() + " - " + c.detail()));
        assertThat(records.findByCode(o.taskCode())).isPresent();
    }
}
