package com.pair.domain.taskgen.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.pair.domain.taskgen.dto.DevDraftDto;
import com.pair.domain.taskgen.dto.GenCheckResultDto;
import com.pair.domain.taskgen.dto.TaskGenRequest;
import com.pair.global.config.TaskGenProperties;
import com.pair.infra.sandbox.CodeRunner.CompileDiagnostic;
import com.pair.infra.sandbox.CodeRunner.RunResult;
import com.pair.infra.sandbox.CodeRunner.TestCase;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** AI 단독 풀이 판정(g5 03): 놓침은 컴파일되고 공개 테스트를 통과했는데 결함 숨은 테스트만 실패한 경우, 나머지 실패는 무효(놓침 비율 분모에서 뺀다) */
class SoloInvalidTest {

    private static DevDraftDto draft() {
        var ai = new StageOutputLimitTest.CutWriting(0);
        return new DevPipelineService(ai, new TaskGenProperties(null, null, null, null, null)).start(new TaskGenRequest("DEV", "결제 할인 계산", "소수 금액 누적 오차", "SELF", 20)).create(s -> {});
    }

    private static RunResult run(boolean compiled, TestCase... tests) {
        long failed = java.util.Arrays.stream(tests).filter(t -> !t.passed()).count();
        return new RunResult(compiled, List.of(), (int) (tests.length - failed), (int) failed, List.of(tests),
                compiled ? List.of() : List.of(new CompileDiagnostic("src/shop/PaymentDiscount.java", 3, 1, "ERROR", "non-static method cannot be referenced")), 100);
    }

    @Test
    @DisplayName("컴파일 실패·공개 테스트 실패·결함 밖 숨은 테스트만 실패는 무효, 결함 숨은 테스트 실패와 고친 파일 없음은 놓침")
    void classifies() {
        DevDraftDto d = draft(); // 결함 숨은 테스트: totalsExactly, 공개 테스트 클래스: PaymentDiscountTest
        Map<String, String> answer = Map.of("src/shop/PaymentDiscount.java", "class X {}");
        assertThat(SoloSolveService.invalidReason(d, answer, run(false))).startsWith("풀이가 컴파일되지 않음").contains("non-static");
        assertThat(SoloSolveService.invalidReason(d, answer, run(true, new TestCase("shop.PaymentDiscountTest.totals", false, "x"), new TestCase("shop.H.totalsExactly", false, "x"))))
                .startsWith("공개(기존) 테스트 실패");
        assertThat(SoloSolveService.invalidReason(d, answer, run(true, new TestCase("shop.H.payableAfterDiscount", false, "x")))).startsWith("결함을 겨냥하지 않은");
        assertThat(SoloSolveService.invalidReason(d, Map.of(), run(true))).isNull(); // 고칠 곳이 없다고 본 것은 놓침
        assertThat(SoloSolveService.invalidReason(d, answer, run(true, new TestCase("shop.PaymentDiscountTest.totals", true, null), new TestCase("shop.H.totalsExactly", false, "x")))).isNull();
    }

    @Test
    @DisplayName("무효 판정은 report 의 solo.status 가 invalid(이유 포함)")
    void reportStatus() {
        var c = new GenCheckResultDto(SoloSolveService.NAME, true, SoloSolveService.INVALID_PREFIX + "풀이가 컴파일되지 않음 (1번 중 1번)", GenCheckResultDto.WARN);
        Map<String, Object> m = TaskGeneratorService.soloOutcome(List.of(c));
        assertThat(m.get("status")).isEqualTo("invalid");
        assertThat((String) m.get("reason")).startsWith("풀이가 컴파일되지 않음");
        assertThat(TaskGenBatchLiveTest.soloLine(new String[] {"- 01 | passed=true | solo=missed", "- 02 | passed=true | solo=invalid", "- 03 | passed=true | solo=caught"}))
                .contains("놓침 1/2 (50%)").contains("무효(컴파일 실패 등) 1개");
    }
}
