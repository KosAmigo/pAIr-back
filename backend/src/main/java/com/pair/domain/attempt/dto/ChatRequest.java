package com.pair.domain.attempt.dto;

import java.util.List;
import com.pair.domain.attempt.service.ChatAttachmentService;

public record ChatRequest(String message, List<ChatAttachmentService.Ref> attachments) {}
