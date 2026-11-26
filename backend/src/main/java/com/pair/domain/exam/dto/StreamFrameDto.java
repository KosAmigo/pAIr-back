package com.pair.domain.exam.dto;

/** 상시 저장 묶음 안의 사진 1장 (atMs 는 받은 시각 epoch ms). 파일 저장소 안에서만 쓰고 API 로는 나가지 않는다 */
public record StreamFrameDto(long atMs, byte[] jpeg) {}
