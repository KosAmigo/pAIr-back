package com.pair.global.error;

/** 이미 등록된 이메일로 회원가입할 때 API 409 응답을 만들기 위한 예외. */
public class ConflictException extends RuntimeException {
    public ConflictException(String message) {
        super(message);
    }
}
