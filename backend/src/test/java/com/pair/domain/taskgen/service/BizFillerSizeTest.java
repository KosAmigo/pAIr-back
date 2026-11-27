package com.pair.domain.taskgen.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.pair.domain.task.dto.AiLimitsDto;
import com.pair.domain.taskgen.dto.BlueprintDto;
import java.util.List;
import org.junit.jupiter.api.Test;

/** BIZ 관련 적은 자료 분량: 헷갈리는 자료는 필요한 파일 하나 크기, 나머지가 남는 분량을 나눈다(크기로 필요한 파일을 고르지 못하게, 2026-11-27) */
class BizFillerSizeTest {

    private static BlueprintDto bp(List<BlueprintDto.Filler> fillers) {
        return TaskPipelineServiceTest.bizBlueprint().withMaterials(List.of("자료/검토 안내.pdf", "자료/운영 메모.pdf"), fillers);
    }

    @Test
    void 헷갈리는_자료는_필요한_파일_평균_크기이고_나머지가_남는_분량을_채운다() {
        var limits = AiLimitsDto.forMinutes(20); // 한도 8000자, 목표 전체 18400자
        var b = bp(List.of(new BlueprintDto.Filler("자료/6월 매출 검산 정리.pdf", "같은 지표의 다른 지점 검산", true),
                new BlueprintDto.Filler("자료/시설 점검 기록.pdf", "사무실 시설 점검"), new BlueprintDto.Filler("자료/행사 운영 기록.pdf", "사내 행사")));
        var chars = MaterialGateService.bizFillerChars(b, 4400, limits);
        assertThat(chars.get("자료/6월 매출 검산 정리.pdf")).isEqualTo(2200); // 4400자 / 필요한 파일 2개
        assertThat(chars.get("자료/시설 점검 기록.pdf")).isEqualTo(chars.get("자료/행사 운영 기록.pdf")).isEqualTo(5900); // (18400-4400-2200)/2
        int total = 4400 + chars.values().stream().mapToInt(Integer::intValue).sum();
        assertThat(total).isGreaterThanOrEqualTo((int) (limits.contextChars() * MaterialGateService.TOTAL_MIN_RATIO));
    }

    @Test
    void 헷갈리는_자료만_있거나_없으면_고르게_나눈다() {
        var limits = AiLimitsDto.forMinutes(20);
        var onlyConfusing = bp(List.of(new BlueprintDto.Filler("자료/a.pdf", "a", true), new BlueprintDto.Filler("자료/b.pdf", "b", true)));
        int even = MaterialGateService.perFillerChars(onlyConfusing, 4400, limits);
        assertThat(MaterialGateService.bizFillerChars(onlyConfusing, 4400, limits).values()).containsOnly(even);
        var none = bp(List.of(new BlueprintDto.Filler("자료/a.pdf", "a"), new BlueprintDto.Filler("자료/b.pdf", "b")));
        assertThat(MaterialGateService.bizFillerChars(none, 4400, limits).values()).containsOnly(MaterialGateService.perFillerChars(none, 4400, limits));
    }
}
