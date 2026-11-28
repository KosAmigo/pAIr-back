package com.pair.domain.task.dto;

import com.pair.domain.task.repository.TaskDefRepository;
import java.util.List;

/**
 * 과제 정의. DB(tasks, task_questions, settings JSON)에서 TaskDefRepository 가 만든다.
 * 숨은 파일(task_files 의 HIDDEN)은 이 객체에 담지 않고 채점할 때만 따로 읽는다.
 */
public record TaskDefDto(
        String id,
        String type,               // dev | biz
        String title,
        int minutes,               // 제한 시간
        List<String> description,
        List<String> requirements,
        List<String> notes,
        List<String> followUps,
        String openFile,           // 처음 열어 둘 파일
        List<Integer> d4Minutes,   // D4 환산 구간 상한(분). 예: [20,30,40,50] → 100/85/70/55, 그 이상 40
        List<String> requiredHeadings, // 비개발: 첫 유효 결과 판정에 쓰는 필수 제목
        String editableFile,       // 비개발: 문서 편집기로 여는 파일
        AiLimitsDto aiLimits) {    // 과제별 AI 제한(E6). 없으면 null → 전역 pair.assistant.*

    public boolean isDev() {
        return "dev".equals(type);
    }
}
