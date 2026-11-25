package com.pair.domain.exam.dto;

/**
 * 감독관이 응시자 영상을 열 수 있는지 확인한 결과.
 * ALLOWED 열어도 된다 / OFFLINE 지금은 열 수 없지만 곧 가능하다(시작 전·아직 입장 전) / DENIED 권한이 없다 / ERROR 열 수 없는 회차·응시자다.
 * userId 는 열람 기록(로그)에 남기는 감독관 번호이고, 허용되지 않았으면 null 일 수 있다.
 */
public record ViewerAccessDto(State state, String message, Long userId) {

    public enum State { ALLOWED, OFFLINE, DENIED, ERROR }

    public static ViewerAccessDto allowed(Long userId) {
        return new ViewerAccessDto(State.ALLOWED, null, userId);
    }

    public static ViewerAccessDto offline(Long userId) {
        return new ViewerAccessDto(State.OFFLINE, null, userId);
    }

    public static ViewerAccessDto denied() {
        return new ViewerAccessDto(State.DENIED, "이 응시자의 영상을 볼 권한이 없습니다.", null);
    }

    public static ViewerAccessDto error(String message, Long userId) {
        return new ViewerAccessDto(State.ERROR, message, userId);
    }
}
