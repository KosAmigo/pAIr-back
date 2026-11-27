package com.pair.domain.taskgen.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.pair.domain.taskgen.service.TrapTypeService.SoloRecord;
import com.pair.domain.taskgen.service.TrapTypeService.TrapType;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 자동 생성의 함정 유형 목록 읽기와 배정 규칙 */
class TrapTypeServiceTest {

    private static List<String> ids(List<TrapType> t) {
        return t.stream().map(TrapType::id).toList();
    }

    @Test
    @DisplayName("유형 목록은 설계도 결함 유형 파일에서 읽는다: DEV 6개(R1~R6), BIZ 8개")
    void readsTypes() {
        List<TrapType> dev = TrapTypeService.types("DEV");
        assertThat(ids(dev)).containsExactly("R1", "R2", "R3", "R4", "R5", "R6");
        assertThat(dev.get(3).label()).isEqualTo("R4 적용 순서");
        assertThat(dev.get(0).description()).isNotBlank();
        List<TrapType> biz = TrapTypeService.types("BIZ");
        assertThat(biz).hasSize(8);
        assertThat(ids(biz)).allMatch(i -> i.startsWith("B"));
    }

    @Test
    @DisplayName("기록이 없으면 유형을 번호 순으로 고르게 돌리고, count 가 유형 수보다 많으면 처음부터 다시 돈다")
    void evenWhenNoHistory() {
        List<TrapType> dev = TrapTypeService.types("DEV");
        assertThat(ids(TrapTypeService.assign(dev, Map.of(), 4))).containsExactly("R1", "R2", "R3", "R4");
        assertThat(ids(TrapTypeService.assign(dev, Map.of(), 8))).containsExactly("R1", "R2", "R3", "R4", "R5", "R6", "R1", "R2");
        assertThat(TrapTypeService.assign(dev, Map.of(), 0)).isEmpty();
    }

    @Test
    @DisplayName("기록이 5개 이상인 유형은 AI 단독 풀이를 놓친 비율이 높은 것부터, 기록이 적은 유형은 그 뒤에 기록 적은 순으로")
    void missRateFirst() {
        List<TrapType> dev = TrapTypeService.types("DEV");
        Map<String, SoloRecord> history = Map.of(
                "R2", new SoloRecord(4, 1),   // 놓침 80%
                "R4", new SoloRecord(1, 4),   // 20%
                "R5", new SoloRecord(5, 5),   // 50%
                "R1", new SoloRecord(2, 1),   // 기록 3개: 아직 믿을 수 없음
                "R3", new SoloRecord(0, 1));  // 기록 1개
        // R2(80) R5(50) R4(20) 다음에 기록 적은 순: R6(0) R3(1) R1(3)
        assertThat(ids(TrapTypeService.assign(dev, history, 6))).containsExactly("R2", "R5", "R4", "R6", "R3", "R1");
        assertThat(ids(TrapTypeService.assign(dev, history, 2))).containsExactly("R2", "R5");
    }

    @Test
    @DisplayName("놓침 비율이 같으면 번호 순")
    void tieBreaksByNumber() {
        List<TrapType> dev = TrapTypeService.types("DEV");
        Map<String, SoloRecord> history = Map.of("R3", new SoloRecord(3, 2), "R1", new SoloRecord(3, 2));
        assertThat(ids(TrapTypeService.assign(dev, history, 2))).containsExactly("R1", "R3");
    }
}
