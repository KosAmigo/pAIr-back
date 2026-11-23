package com.pair.domain.exam.dto;

import com.pair.domain.exam.entity.ProctorEvent;
import jakarta.validation.constraints.Size;

/** 담당자 판정. review 가 null 이면 판정을 지운다. 판정자는 로그인 정보에서 꺼낸다 */
public record ReviewRequest(ProctorEvent.Review review, @Size(max = 1000) String note) {}
