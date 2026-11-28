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
    @DisplayName("Q7: 작성 모델이 쓴 예 줄은 빼고, 예시를 함수별로 만들 기능·매개변수 줄 뒤에 모은다(공통 줄은 첫 예시 묶음 앞). 기존 메서드 예시는 내지 않는다")
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
                ExampleLineService.line(EX2));
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
    @DisplayName("Q7 경고: 추가 함수당 3개 이상, 표시값 30자 초과, 목록 4개 이상, 시간대 오프셋, api 에 없는 method 를 알린다(실패가 아니라 경고). 기존 메서드 예는 모양을 안 본다")
    void shapeWarnings() {
        assertThat(ExampleLineService.shapeCheck(bp(List.of(EX1, EX2, EXISTING), List.of(), "")).ok()).isTrue();

        var longValue = new BlueprintDto.Example(IN1, "x", "PassRegistry.availableCodes", List.of(List.of("visitDate", "2026-03-03T10:00:00+09:00[Asia/Seoul]")));
        var longList = new BlueprintDto.Example(IN1, "[\"A\", \"B\", \"C\", \"D\"]", "PassRegistry.availableCodes", List.of(List.of("visitDate", "2026-06-10")));
        var unknown = new BlueprintDto.Example(IN1, "1", "PassRegistry.countAvailable", List.of());
        GenCheckResultDto r = ExampleLineService.shapeCheck(bp(List.of(longValue, longList, EX1, EX2, unknown), List.of(), ""));
        assertThat(r.ok()).isFalse();
        assertThat(r.needsRegeneration()).isFalse();
        assertThat(r.detail()).contains("표시값 37자").contains("목록 4개").contains("PassRegistry.availableCodes 예시 4개").contains("api 에 없는 예시 method PassRegistry.countAvailable")
                .contains("시간대 오프셋");

        var offset = new BlueprintDto.Example(IN1, "1", "PassRegistry.availableCodes", List.of(List.of("visitDate", "2026-06-10T16:00Z")));
        assertThat(ExampleLineService.shapeCheck(bp(List.of(offset), List.of(), "")).detail()).contains("시간대 오프셋");
        var existingLong =new BlueprintDto.Example("x", "[\"A\", \"B\", \"C\", \"D\"]", "PassRegistry.size", List.of());
        assertThat(ExampleLineService.shapeCheck(bp(List.of(EX1, existingLong, existingLong, existingLong), List.of(), "")).ok()).isTrue();
    }

    @Test
    @DisplayName("Q7 개정: 기존 메서드 예는 화면에 안 나오니 사례 근거로 쓰면 설계도를 다시 받고, 근거 검사도 실패한다")
    void existingExampleIsNotBasis() {
        var c = new BlueprintDto.Case("sizeAfterRegister", "register 뒤 size()", "1", EXISTING.input() + " → 1");
        BlueprintDto b = new BlueprintDto("t", "s", "pass", "기능 구현", List.of(), "상황", List.of("기존: PassRegistry.size(): int", "추가: " + SIG), List.of(),
                List.of(EXISTING, EX1, EX2), List.of(), List.of(), List.of(), null, List.of(), List.of(), List.of(), List.of(), List.of(c), List.of(), List.of());
        assertThat(ExampleLineService.shown(b, EXISTING)).isFalse();
        assertThat(DevBlueprintService.problems(b)).anyMatch(p -> p.contains("기존 메서드 예") && p.contains("사실(facts)"));
        String req = String.join("\n", ExampleLineService.withExamples(b, List.of()));
        assertThat(req).doesNotContain("PassRegistry.size");
        assertThat(TaskWritingService.basisCheck(b, req).ok()).isFalse();
        assertThat(TaskWritingService.factsCheck(b, "", req).ok()).isTrue();
    }

    private static BlueprintDto batch(String setup, List<BlueprintDto.Example> examples) {
        return new BlueprintDto("t", "s", "batch", "기능 구현", List.of(), "상황", List.of("기존: SettlementBatch.load(String content): void", "추가: SettlementBatch.feeFor(String reference): long"),
                List.of(), examples, List.of(), List.of(), List.of(), null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(new BlueprintDto.ExampleSetup("SettlementBatch.feeFor", setup)), "");
    }

    @Test
    @DisplayName("g25 03: 표시값·준비 문장의 이름·숫자가 input 과 다르면 형식 실패(재요청 문구 포함). 날짜·쉼표 숫자·줄바꿈 낀 문자열은 같은 값으로 본다")
    void displayMustMatchInput() {
        String in = "새 SettlementBatch에 load(\"바일;10000\\n바이;20000\")를 한 뒤 feeFor(\"바일\")";
        var renamed = new BlueprintDto.Example(in, "300", "SettlementBatch.feeFor", List.of(List.of("reference", "바다")));
        List<String> bad = ExampleLineService.mismatches(batch("수신 파일에는 정상 거래 바다 10,000원과 하늘 20,000원이 들어 있다.", List.of(renamed)));
        assertThat(bad).anyMatch(p -> p.contains("표시값 '바다' 이 input 에 없습니다"))
                .anyMatch(p -> p.contains("input 의 값 '바일'"))
                .allMatch(p -> p.endsWith(ExampleLineService.MISMATCH_RULE));
        assertThat(ExampleLineService.mismatches(batch("수신 파일에는 바일 10,000원과 바이 25,000원이 들어 있다.",
                List.of(new BlueprintDto.Example(in, "300", "SettlementBatch.feeFor", List.of(List.of("reference", "바일")))))))
                .containsExactly("예시 SettlementBatch.feeFor 의 준비 문장 숫자 25000 이 input 에 없습니다: 수신 파일에는 바일 10,000원과 바이 25,000원이 들어 있다.. " + ExampleLineService.MISMATCH_RULE);

        // 오탐 없음: 같은 이름·숫자를 짧게 쓴 것
        var ok = new BlueprintDto.Example(in, "300", "SettlementBatch.feeFor", List.of(List.of("reference", "바일")));
        assertThat(ExampleLineService.mismatches(batch("수신 파일에는 바일 10,000원과 바이 20,000원이 들어 있다.", List.of(ok)))).isEmpty();
        var setup = new BlueprintDto.ExampleSetup("PassRegistry.availableCodes", "등록된 이용권: POOL-A 2026-06-30 만료, GYM-B 2026-06-01 만료");
        assertThat(ExampleLineService.mismatches(bp(List.of(EX1, EX2), List.of(setup), ""))).isEmpty();
        var time = new BlueprintDto.Example("새 RoomCalendar에 registerRoom(\"한빛\"), reserve(\"한빛\", LocalTime.of(10, 0), LocalTime.of(11, 0)) 뒤 availableRooms(LocalTime.of(11, 30), LocalTime.of(11, 45))",
                "[\"한빛\"]", "PassRegistry.availableCodes", List.of(List.of("start", "11:30"), List.of("end", "11:45")));
        assertThat(ExampleLineService.mismatches(bp(List.of(time), List.of(new BlueprintDto.ExampleSetup("PassRegistry.availableCodes", "한빛에는 10:00부터 11:00까지 예약이 있다.")), ""))).isEmpty();
        var decimal = new BlueprintDto.Example("새 PaymentDiscount에 add(new BigDecimal(\"0.10\")) 뒤 availableCodes(LocalDate.of(2026, 6, 10))", "[]", "PassRegistry.availableCodes",
                List.of(List.of("visitDate", "2026-06-10")));
        assertThat(ExampleLineService.mismatches(bp(List.of(decimal), List.of(new BlueprintDto.ExampleSetup("PassRegistry.availableCodes", "할인 0.1 등록")), ""))).isEmpty();
        assertThat(ExampleLineService.numbers("2026-06-10 40,000원 0.10 [1,2]")).containsExactly("2026", "6", "10", "40000", "0.1", "1", "2");
    }

    @Test
    @DisplayName("g25 01·04: where 에 api 클래스를 적으면 where 를 고치라고 알린다")
    void whereNamingApiClassSaysFixWhere() {
        BlueprintDto b = new BlueprintDto("t", "s", "room", "기능 구현", List.of(), "상황", List.of("추가: RoomCalendar.availableRooms(LocalTime start, LocalTime end): List<String>"), List.of(),
                List.of(), List.of(new BlueprintDto.Flaw("R1 경계", "ReservationOverlap.overlaps (src/meeting/ReservationOverlap.java), RoomCalendar.isAvailable (src/meeting/RoomCalendar.java)", "근거", "t")),
                List.of(), List.of(), null, List.of(), List.of(), List.of(), List.of(), List.of());
        assertThat(DevBlueprintService.flawHidden(b)).hasSize(1).allMatch(p -> p.startsWith("flaws.where 가 결함 클래스와 함께 그것을 부르는 공개 API 클래스 RoomCalendar"));
    }

    @Test
    @DisplayName("Q7 형식 검사: 예에 method·args 가 없으면 설계도를 다시 받는다")
    void blueprintNeedsTableFields() {
        BlueprintDto b = bp(List.of(new BlueprintDto.Example(IN1, "[\"POOL-A\"]"), EX2), List.of(), "");
        assertThat(DevBlueprintService.problems(b)).anyMatch(p -> p.contains("표 칸이 없습니다"));
        assertThat(DevBlueprintService.problems(bp(List.of(EX1, EX2), List.of(), ""))).noneMatch(p -> p.contains("표 칸"));
    }
}
