package com.pair.global.error;

/** 401: 로그인하지 않았거나 로그인 정보가 틀림 */
public class UnauthorizedException extends RuntimeException {
    public UnauthorizedException(String message) {
        super(message);
    }
}
