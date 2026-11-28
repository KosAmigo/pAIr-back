package com.pair.domain.taskgen.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * 검사 1개의 결과. detail 은 통과해도 실패해도 한 줄 설명을 담는다.
 * level: "fail" 은 실패하면 다시 생성하고, "warn" 은 다시 생성하지 않고 검수자에게 알리기만 한다.
 * stages: 이 검사가 실패했을 때 다시 돌릴 단계 이름(TaskPipelineService.Stage 이름, 예: "WRITING"). 검사를 만든 쪽이 직접 담는다.
 * 비어 있으면 검사 이름 표로 정한다(그래도 모르면 설계도부터).
 */
public record GenCheckResultDto(String name, boolean ok, String detail, String level, @JsonInclude(JsonInclude.Include.NON_EMPTY) List<String> stages) {

    public static final String FAIL = "fail";
    public static final String WARN = "warn";

    /** 등급 없이 만들면 fail 이다 */
    public GenCheckResultDto(String name, boolean ok, String detail) {
        this(name, ok, detail, FAIL);
    }

    public GenCheckResultDto(String name, boolean ok, String detail, String level) {
        this(name, ok, detail, level, List.of());
    }

    public GenCheckResultDto {
        if (!WARN.equals(level)) level = FAIL;
        stages = stages == null ? List.of() : List.copyOf(stages);
    }

    /** 다시 돌릴 단계를 담은 복사본(이미 담긴 단계는 바꾼다) */
    public GenCheckResultDto withStages(String... names) {
        return new GenCheckResultDto(name, ok, detail, level, List.of(names));
    }

    /** 통과하지 못했고 등급이 fail 이라 다시 생성해야 하는 검사 */
    public boolean needsRegeneration() {
        return !ok && !WARN.equals(level);
    }
}
