package com.pair.domain.taskgen.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.pair.domain.taskgen.dto.DevDraftDto;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 2026-11-26 g7: 처음 열어 둘 파일이 결함 파일을 가리키지 않는다(g6 판정: openFile 이 결함 파일이었음) */
class GeneratedTaskOpenFileTest {

    private static DevDraftDto draft(List<String> paths, String flawWhere, String description) {
        List<DevDraftDto.FileDraft> files = paths.stream().map(p -> new DevDraftDto.FileDraft(p, "class X {}")).toList();
        return new DevDraftDto("t", "s", "c", "기능 구현", List.of(description), List.of("addItem 뒤 points() → 200"), "a", files, List.of(), List.of(), "보통",
                List.of(), List.of(), List.of(), "", List.of(), List.of(), List.of(new DevDraftDto.HiddenFlaw("R3 반올림 (" + flawWhere + ")", "기록", "t1")));
    }

    @Test
    @DisplayName("g8: 공개 테스트를 먼저 연다(호출 쪽 파일도 필요한 파일이라 열어 두면 고를 일이 없음). 공개 테스트가 없으면 설명이 이름을 대는 결함 없는 파일")
    void opensPublicTestFirst() {
        DevDraftDto d = draft(List.of("src/order/RewardPolicy.java", "src/order/LegacyPointRule.java", "src/order/OrderCheckout.java", "test/order/OrderCheckoutTest.java"),
                "RewardPolicy.pointsFor (src/order/RewardPolicy.java)", "OrderCheckout 에 결제액 조회를 더하세요.");
        assertThat(GeneratedTaskRepository.openFile(d)).isEqualTo("test/order/OrderCheckoutTest.java");
        DevDraftDto noTest = draft(List.of("src/order/RewardPolicy.java", "src/order/LegacyPointRule.java", "src/order/OrderCheckout.java"),
                "RewardPolicy.pointsFor (src/order/RewardPolicy.java)", "OrderCheckout 에 결제액 조회를 더하세요.");
        assertThat(GeneratedTaskRepository.openFile(noTest)).isEqualTo("src/order/OrderCheckout.java");
    }

    @Test
    @DisplayName("설명이 이름을 대는 파일이 없으면 결함과 상관없는 첫 src 파일, 그것도 없으면 첫 src 파일")
    void fallbacks() {
        DevDraftDto d = draft(List.of("src/order/RewardPolicy.java", "src/order/Other.java"), "RewardPolicy.pointsFor", "결제액 조회를 더하세요.");
        assertThat(GeneratedTaskRepository.openFile(d)).isEqualTo("src/order/Other.java");
        DevDraftDto only = draft(List.of("src/order/RewardPolicy.java"), "RewardPolicy.pointsFor", "결제액 조회를 더하세요.");
        assertThat(GeneratedTaskRepository.openFile(only)).isEqualTo("src/order/RewardPolicy.java");
    }
}
