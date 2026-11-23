package com.pair.domain.exam.dto;

import java.util.List;

/** added: 등록한 인원 (미리 보기면 등록될 인원) */
public record AddParticipantsResponse(int added, List<RejectedParticipantDto> rejected) {}
