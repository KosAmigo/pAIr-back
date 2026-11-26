package com.pair.global.error;

import org.springframework.http.HttpStatus;

/** 코드가 붙은 오류: 응답 본문이 {"message","code"} 가 된다 (프론트가 문장이 아니라 code 로 분기할 때 쓴다) */
public class CodedException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public CodedException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
