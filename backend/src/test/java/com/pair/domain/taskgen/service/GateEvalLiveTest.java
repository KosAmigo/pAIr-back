package com.pair.domain.taskgen.service;

import com.pair.domain.taskgen.dto.ReviewResultDto.Violation;
import com.pair.global.config.TaskGenProperties;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 관문 평가(비용 발생): 정답 세트(gold-final.json)의 과제 폴더를 읽어 관문(답 노출 두 눈, 정답 키, 테스트 근거, 일관성)만 돌리고,
 * 정답과 맞춰 놓침·오탐·적중률을 build/gate-eval/result.md 에 쓴다. 과제를 새로 만들지 않는다.
 * PAIR_TASKGEN_LIVE=1 일 때만 돈다. 정답 폴더는 환경변수 PAIR_GOLD_DIR(기본 ../../taskgen-runs/_verify/gold), 과제 간 동시 실행은 PAIR_TASKGEN_PARALLEL(기본 6).
 */
@SpringBootTest
@ActiveProfiles("examtest")
@EnabledIfEnvironmentVariable(named = "PAIR_TASKGEN_LIVE", matches = "1")
@Tag("slow")
class GateEvalLiveTest {

    static final Path OUT = Path.of("build", "gate-eval").toAbsolutePath();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.ai.openai.api-key", () -> "${OPENAI_API_KEY:}");
        r.add("spring.ai.anthropic.api-key", () -> "${ANTHROPIC_API_KEY:}");
    }

    @MockitoBean JavaMailSender mail;
    @Autowired TaskGenAiService ai;
    @Autowired TaskGenProperties props;
    @Autowired TaskGenSolverService solver;

    /** 정답 키의 결정적 검사: 단답형 검산(CALC)과 다른 모델 풀이(SOLVE). 실패한 검사마다 위반 하나 */
    private List<Violation> deterministicKey(GateEvalSupport.Loaded l) {
        List<Violation> out = new ArrayList<>();
        for (var c : BizCheckService.forDraft(new PdfService(), solver, props.solverModel(), l.biz(), l.minutes(), null)) {
            if (!c.name().equals(BizCheckService.CALC) && !c.name().equals(BizCheckService.SOLVE)) continue;
            var r = c.run(l.biz());
            if (r.detail() != null && r.detail().startsWith("형식이 맞지 않아")) continue; // 형식을 못 읽은 과제는 확인하지 않은 것(위반 아님)
            if (!r.ok() && r.needsRegeneration()) out.add(new Violation("M2", r.detail(), c.name(), ""));
        }
        return out;
    }

    @Test
    void evaluateGates() throws Exception {
        Path goldDir = Path.of(System.getenv().getOrDefault("PAIR_GOLD_DIR", "../../taskgen-runs/_verify/gold")).toAbsolutePath().normalize();
        int parallel = Integer.parseInt(System.getenv().getOrDefault("PAIR_TASKGEN_PARALLEL", "6"));
        List<GateEvalSupport.GoldTask> tasks = GateEvalSupport.loadGold(goldDir.resolve("gold-final.json"));
        Files.createDirectories(OUT);

        List<GateEvalSupport.TaskScore> scores = Collections.synchronizedList(new ArrayList<>());
        List<String> errors = Collections.synchronizedList(new ArrayList<>());
        try (ExecutorService pool = Executors.newFixedThreadPool(parallel)) {
            List<Future<?>> futures = new ArrayList<>();
            for (var t : tasks) {
                futures.add(pool.submit(() -> {
                    try {
                        var loaded = GateEvalSupport.loadTask(t.path());
                        StageCallService calls = new StageCallService(ai, props, loaded.dev() != null); // 과제마다 따로(토큰 기록이 섞이지 않게)
                        TaskReviewService reviewer = new TaskReviewService(calls);
                        Map<GateEvalSupport.EvalGate, List<Violation>> flagged = GateEvalSupport.runGates(loaded, (check, stage, input) -> reviewer.run(check, stage, input).violations(), l -> deterministicKey(l));
                        flagged.forEach((gate, flags) -> scores.add(GateEvalSupport.score(t, gate, flags)));
                        System.out.println("[gate-eval] " + t.kind() + " " + t.path().getFileName() + " " + flagged.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue().size()).toList());
                    } catch (Exception | Error e) {
                        errors.add(t.path() + ": " + e);
                    }
                }));
            }
            for (Future<?> f : futures) f.get();
        }
        StringBuilder md = new StringBuilder(GateEvalSupport.markdown(scores, tasks.size()));
        if (!errors.isEmpty()) {
            md.append("\n## 실행하지 못한 과제\n\n");
            errors.forEach(e -> md.append("- ").append(e).append('\n'));
        }
        Files.writeString(OUT.resolve("result.md"), md.toString(), StandardCharsets.UTF_8);
    }
}
