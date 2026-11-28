package com.pair.domain.taskgen.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.pair.domain.taskgen.dto.BlueprintDto;
import com.pair.domain.taskgen.dto.TaskGenRequest;
import com.pair.global.config.TaskGenProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Tag;
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

/**
 * DEV 설계도 단계만 실제 모델로 돌려 요구사항 예시 줄(Q7)을 본다(2026-11-28, 전체 생성보다 싸다). 형식 검사·조기 점검(blueprint-leak)과 재시도까지 돈다.
 * PAIR_TASKGEN_LIVE=1 일 때만 돈다(비용 발생). 결과는 build/taskgen-blueprint/summary.md 와 과제별 blueprint json
 */
@SpringBootTest
@ActiveProfiles("examtest")
@EnabledIfEnvironmentVariable(named = "PAIR_TASKGEN_LIVE", matches = "1")
@Tag("slow")
class DevBlueprintExamplesLiveTest {

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("pair.data-dir", () -> dataDir.toString());
        r.add("spring.ai.openai.api-key", () -> "${OPENAI_API_KEY:}");
        r.add("spring.ai.anthropic.api-key", () -> "${ANTHROPIC_API_KEY:}");
    }

    @MockitoBean JavaMailSender mail;
    @Autowired DevPipelineService pipeline;
    @Autowired TaskGenProperties props;

    static final Path OUT = Path.of("build", "taskgen-blueprint").toAbsolutePath();

    /** 주제, 함정, 제한시간(20·40·60 섞음). 시각·날짜가 들어가는 주제를 섞어 오프셋이 남는지 본다 */
    static final List<String[]> TOPICS = List.of(
            new String[] {"회의실 예약 겹침 확인", "앞 예약의 종료 시각과 다음 예약의 시작 시각이 같으면 겹치지 않는다", "20"},
            new String[] {"정산 신청 마감 판정", "마감은 한국 시간 18시 기준인데 서버 시각은 UTC다", "40"},
            new String[] {"정산 파일 합계와 수수료", "수수료는 건별로 계산해 10원 미만을 버린다", "60"},
            new String[] {"이용권 만료 확인", "만료일 당일까지 사용할 수 있다", "20"},
            new String[] {"구매 금액별 적립 등급", "등급 경계 금액은 상위 등급에 포함된다", "40"},
            new String[] {"묶음 배송비 계산", "묶음 배송비는 가장 무거운 상품 하나의 무게 기준이다", "60"});

    @Test
    void blueprintExamples() throws Exception {
        Files.createDirectories(OUT);
        var pool = Executors.newFixedThreadPool(3);
        List<Future<String>> jobs = new ArrayList<>();
        for (int i = 0; i < TOPICS.size(); i++) {
            int n = i + 1;
            String[] t = TOPICS.get(i);
            jobs.add(pool.submit(() -> one(n, new TaskGenRequest("DEV", t[0], t[1], "SELF", Integer.parseInt(t[2])).validated())));
        }
        StringBuilder sb = new StringBuilder("# DEV 설계도 예시 줄 확인\n\n");
        for (var j : jobs) sb.append(j.get()).append('\n');
        pool.shutdown();
        Files.writeString(OUT.resolve("summary.md"), sb.toString());
        System.out.println(sb);
        assertThat(jobs).hasSize(TOPICS.size());
    }

    private String one(int n, TaskGenRequest form) throws Exception {
        StringBuilder sb = new StringBuilder("## " + String.format("%02d", n) + " " + form.topic() + " (" + form.minutes() + "분)\n\n");
        try {
            var run = pipeline.blueprintOnly(form);
            BlueprintDto bp = run.blueprint();
            Files.writeString(OUT.resolve(String.format("%02d", n) + "-blueprint.json"), StageCallService.json(bp));
            List<String> features = new ArrayList<>();
            for (String a : bp.api()) if (a.strip().startsWith("추가")) features.add("만들 기능: `" + a.substring(a.indexOf(':') + 1).strip() + "` — (설명)");
            List<String> lines = ExampleLineService.withExamples(bp, features);
            sb.append("api:\n");
            bp.api().forEach(a -> sb.append("- ").append(a).append('\n'));
            sb.append("\n요구사항 줄(코드가 만든 것):\n```\n");
            lines.forEach(l -> sb.append(l).append('\n'));
            sb.append("```\n");
            Map<String, Integer> shown = new LinkedHashMap<>();
            ExampleLineService.shownExamples(bp).forEach(e -> shown.merge(String.valueOf(e.method()), 1, Integer::sum));
            int hidden = bp.examples().size() - ExampleLineService.shownExamples(bp).size();
            var shape = ExampleLineService.shapeCheck(bp);
            double cost = StageCallService.cost(props, run.usage());
            sb.append("\n화면 예시 함수당: ").append(shown).append(", 기존 메서드 예(안 보임): ").append(hidden).append('\n');
            sb.append("모양 경고: ").append(shape.ok() ? "없음" : shape.detail()).append('\n');
            sb.append("설계도 재시도: ").append(run.blueprintRetries().size()).append("번")
                    .append(run.blueprintRetries().isEmpty() ? "" : " (마지막 문제: " + run.blueprintRetries().get(run.blueprintRetries().size() - 1) + ")").append('\n');
            sb.append("남은 형식 문제: ").append(BlueprintService.problems(bp, false)).append('\n');
            sb.append(String.format("비용: $%.3f%n", cost));
        } catch (RuntimeException e) {
            sb.append("예외: ").append(e.getMessage()).append('\n');
        }
        return sb.toString();
    }
}
