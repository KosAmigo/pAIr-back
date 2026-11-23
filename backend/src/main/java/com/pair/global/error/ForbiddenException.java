package com.pair.global.error;

/** 403: 로그인했지만 권한(역할)이 없음. 다른 회사 데이터는 존재를 숨기려고 404 로 돌려준다 */
public class ForbiddenException extends RuntimeException {
    public ForbiddenException(String message) {
        super(message);
    }
}
