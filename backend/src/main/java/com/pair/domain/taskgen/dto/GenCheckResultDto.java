package com.pair.domain.taskgen.dto;

/**
 * 검사 1개의 결과. detail 은 통과해도 실패해도 한 줄 설명을 담는다.
 * level: "fail" 은 실패하면 다시 생성하고, "warn" 은 다시 생성하지 않고 검수자에게 알리기만 한다.
 */
public record GenCheckResultDto(String name, boolean ok, String detail, String level) {

    public static final String FAIL = "fail";
    public static final String WARN = "warn";

    /** 등급 없이 만들면 fail 이다 */
    public GenCheckResultDto(String name, boolean ok, String detail) {
        this(name, ok, detail, FAIL);
    }

    public GenCheckResultDto {
        if (!WARN.equals(level)) level = FAIL;
    }

    /** 통과하지 못했고 등급이 fail 이라 다시 생성해야 하는 검사 */
    public boolean needsRegeneration() {
        return !ok && !WARN.equals(level);
    }
}
