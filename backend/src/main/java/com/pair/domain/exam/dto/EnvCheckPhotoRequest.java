package com.pair.domain.exam.dto;

import com.pair.domain.exam.entity.EnvCheckPhoto;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** image: data:image/jpeg;base64,... 또는 base64 본문 */
public record EnvCheckPhotoRequest(@NotNull EnvCheckPhoto.Stage stage, @NotBlank String image) {}
