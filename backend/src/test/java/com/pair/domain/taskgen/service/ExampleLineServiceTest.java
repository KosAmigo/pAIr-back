package com.pair.domain.taskgen.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.pair.domain.taskgen.dto.BlueprintDto;
import com.pair.domain.taskgen.dto.GenCheckResultDto;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ExampleLineServiceTest {

    private static final String SIG = "PassRegistry.availableCodes(LocalDate visitDate): List<String>";
    private static final String IN1 = "새 PassRegistry에 register(\"POOL-A\", LocalDate.of(2026, 6, 30)), register(\"GYM-B\", LocalDate.of(2026, 6, 1)) 뒤 availableCodes(LocalDate.of(2026, 6, 10))";
    private static final String IN2 = "새 PassRegistry에 register(\"POOL-A\", LocalDate.of(2026, 6, 30)), register(\"GYM-B\", LocalDate.of(2026, 6, 1)) 뒤 availableCodes(LocalDate.of(2026, 5, 20))";
    private static final BlueprintDto.Example EX1 = new BlueprintDto.Example(IN1, "[\"POOL-A\"]", "PassRegistry.availableCodes", List.of(List.of("visitDate", "2026-06-10")));
    private static final BlueprintDto.Example EX2 = new BlueprintDto.Example(IN2, "[\"POOL-A\", \"GYM-B\"]", "PassRegistry.availableCodes", List.of(List.of("visitDate", "2026-05-20")));
    private static final BlueprintDto.Example EXISTING = new BlueprintDto.Example("새 PassRegistry에 register(\"POOL-A\", LocalDate.of(2026, 6, 30)) 뒤 size()", "1", "PassRegistry.size", List.of());

    private static BlueprintDto bp(List<BlueprintDto.Example> examples, List<BlueprintDto.ExampleSetup> setups, String note) {
        return new BlueprintDto("t", "s", "pass", "기능 구현", List.of(), "상황", List.of("기존: PassRegistry.size(): int", "추가: " + SIG), List.of(), examples,
                List.of(), List.of(), List.of(), null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), setups, note);
    }

    @Test
    @DisplayName("Q7: 예 줄은 프런트와 맞춘 한 줄 JSON 이다(method·args·expected 순서, 예시 준비는 method·text)")
    void lineShape() {
        assertThat(ExampleLineService.line(EX1))
                .isEqualTo("예시: {\"method\":\"PassRegistry.availableCodes\",\"args\":[[\"visitDate\",\"2026-06-10\"]],\"expected\":\"[\\\"POOL-A\\\"]\"}");
        assertThat(ExampleLineService.setupLine(new BlueprintDto.ExampleSetup("PassRegistry.availableCodes", "등록된 이용권: POOL-A 2026-06-30 만료")))
                .isEqualTo("예시 준비: {\"method\":\"PassRegistry.availableCodes\",\"text\":\"등록된 이용권: POOL-A 2026-06-30 만료\"}");
        assertThat(ExampleLineService.line(new BlueprintDto.Example("f(1)", "2"))).isEqualTo("f(1) → 2"); // 표 칸 없는 옛 설계도
    }

    @Test
    @DisplayName("Q7: 작성 모델이 쓴 예 줄은 빼고, 예시를 함수별로 만들 기능·매개변수 줄 뒤에 모은다(공통 줄은 첫 예시 묶음 앞, 기존 메서드 예시는 맨 뒤)")
    void placesExamplesUnderFeature() {
        var setup = new BlueprintDto.ExampleSetup("PassRegistry.availableCodes", "등록된 이용권: POOL-A 2026-06-30 만료, GYM-B 2026-06-01 만료");
        BlueprintDto b = bp(List.of(EXISTING, EX1, EX2), List.of(setup), "모든 날짜는 한국 날짜");
        List<String> req = List.of("만들 기능: `" + SIG + "` — 쓸 수 있는 이용권 코드를 돌려준다\n- visitDate: 방문 예정 날짜", "availableCodes(2026-06-10) → [\"POOL-A\"]", "예시: 엉뚱한 줄");

        List<String> out = ExampleLineService.withExamples(b, req);

        assertThat(out).containsExactly(
                "만들 기능: `" + SIG + "` — 쓸 수 있는 이용권 코드를 돌려준다",
                "- visitDate: 방문 예정 날짜",
                "예시 공통: 모든 날짜는 한국 날짜",
                ExampleLineService.setupLine(setup),
                ExampleLineService.line(EX1),
                ExampleLineService.line(EX2),
                ExampleLineService.line(EXISTING));
        assertThat(TaskWritingService.featureLinesCheck(b, out).ok()).isTrue();
    }

    @Test
    @DisplayName("Q7: 사례 근거가 예 문장이면 설명 사실로 넣지 않고(코드가 예 줄로 넣음), 근거 검사·사실 검사는 그 예 줄을 본다")
    void exampleBasisUsesLine() {
        var c = new BlueprintDto.Case("availableOnVisit", "availableCodes(2026-06-10)", "[\"POOL-A\"]", IN1 + " → [\"POOL-A\"]");
        BlueprintDto raw = new BlueprintDto("t", "s", "pass", "기능 구현", List.of(), "상황", List.of("추가: " + SIG), List.of(), List.of(EX1, EX2),
                List.of(), List.of(), List.of(), null, List.of(), List.of(), List.of(), List.of(), List.of(c), List.of(), List.of());
        BlueprintDto b = BlueprintService.normalized(raw, false);
        assertThat(b.facts()).isEmpty();
        assertThat(ExampleLineService.basisExample(b, c)).isEqualTo(EX1);

        String visible = String.join("\n", ExampleLineService.withExamples(b, List.of("만들 기능: `" + SIG + "` — 코드")));
        assertThat(TaskWritingService.basisCheck(b, visible).ok()).isTrue();
        assertThat(TaskWritingService.factsCheck(b, visible, visible).ok()).isTrue();
        assertThat(TaskWritingService.basisCheck(b, "만들 기능만").ok()).isFalse();
    }

    @Test
    @DisplayName("Q7 경고: 표시값 30자 초과, 목록 4개 이상, 함수당 4개·전체 7개 이상, api 에 없는 method 를 알린다(실패가 아니라 경고)")
    void shapeWarnings() {
        assertThat(ExampleLineService.shapeCheck(bp(List.of(EX1, EX2, EXISTING), List.of(), "")).ok()).isTrue();

        var longValue = new BlueprintDto.Example(IN1, "x", "PassRegistry.availableCodes", List.of(List.of("visitDate", "2026-03-03T10:00:00+09:00[Asia/Seoul]")));
        var longList = new BlueprintDto.Example(IN1, "[\"A\", \"B\", \"C\", \"D\"]", "PassRegistry.availableCodes", List.of(List.of("visitDate", "2026-06-10")));
        var unknown = new BlueprintDto.Example(IN1, "1", "PassRegistry.countAvailable", List.of());
        GenCheckResultDto r = ExampleLineService.shapeCheck(bp(List.of(longValue, longList, EX1, EX2, unknown), List.of(), ""));
        assertThat(r.ok()).isFalse();
        assertThat(r.needsRegeneration()).isFalse();
        assertThat(r.detail()).contains("표시값 37자").contains("목록 4개").contains("PassRegistry.availableCodes 예시 4개").contains("api 에 없는 예시 method PassRegistry.countAvailable");

        List<BlueprintDto.Example> seven = List.of(EX1, EX2, EX1, EXISTING, EXISTING, EXISTING, EX2);
        assertThat(ExampleLineService.shapeCheck(bp(seven, List.of(), "")).detail()).contains("예시 전체 7개");
    }

    @Test
    @DisplayName("Q7 형식 검사: 예에 method·args 가 없으면 설계도를 다시 받는다")
    void blueprintNeedsTableFields() {
        BlueprintDto b = bp(List.of(new BlueprintDto.Example(IN1, "[\"POOL-A\"]"), EX2), List.of(), "");
        assertThat(DevBlueprintService.problems(b)).anyMatch(p -> p.contains("표 칸이 없습니다"));
        assertThat(DevBlueprintService.problems(bp(List.of(EX1, EX2), List.of(), ""))).noneMatch(p -> p.contains("표 칸"));
    }
}
