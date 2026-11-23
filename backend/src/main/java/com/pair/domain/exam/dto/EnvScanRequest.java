package com.pair.domain.exam.dto;

import java.util.List;
import com.pair.domain.exam.entity.EnvScanPhoto;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record EnvScanRequest(
        @NotNull EnvScanPhoto.Stage stage,
        /** 방 스캔에서 이 사진을 찍은 방향(0~359도). 책상은 비움 */
        @Min(0) @Max(359) Integer angle,
        /** 사진에서 감지한 물체 이름 */
        @Size(max = 12) List<@Size(max = 30) String> objects,
        /** data:image/jpeg;base64,... 또는 base64 본문 */
        @NotBlank String image) {}
