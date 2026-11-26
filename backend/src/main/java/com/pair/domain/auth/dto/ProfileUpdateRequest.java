package com.pair.domain.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 내 정보 수정. 지금은 이름만 바꿀 수 있다(이메일은 로그인 ID 라 바꾸지 않는다). 앞뒤 공백은 지운다 */
public record ProfileUpdateRequest(@NotBlank @Size(max = 50) String name) {}
