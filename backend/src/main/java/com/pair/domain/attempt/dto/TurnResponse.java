package com.pair.domain.attempt.dto;

import java.util.List;
import com.pair.domain.attempt.service.AttemptService;
import com.pair.domain.attempt.service.ChatAttachmentService;

public record TurnResponse(int turn, String role, String time, String text, List<AttemptService.EditView> edits,
        List<ChatAttachmentService.View> attachments) {}
