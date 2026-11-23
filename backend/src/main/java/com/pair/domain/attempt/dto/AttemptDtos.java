package com.pair.domain.attempt.dto;

import java.util.List;
import java.util.Map;

import com.pair.domain.attempt.service.AttemptService;
import com.pair.domain.attempt.service.ChatAttachmentService;

public final class AttemptDtos {

    private AttemptDtos() {}

    public record CheckpointLabelRequest(String label) {}

    public record StartRequest(String taskId) {}

    public record SaveRequest(String content) {}

    public record ChatRequest(String message, List<ChatAttachmentService.Ref> attachments) {}

    public record FollowUpRequest(List<String> answers) {}

    public record TurnView(int turn, String role, String time, String text, List<AttemptService.EditView> edits,
            List<ChatAttachmentService.View> attachments) {}

    public record EditRequest(int turn, int index, String action) {}

    public record DiagnosticsRequest(Map<String, String> files) {}

    /** 커서 앞 "식." 의 멤버 자동 완성. offset 은 path 파일 안의 글자 위치(0부터). */
    public record CursorRequest(Map<String, String> files, String path, int offset) {}
}
