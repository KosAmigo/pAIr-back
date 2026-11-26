package com.pair.domain.auth.exception;

/** 이메일 인증 코드 확인 실패. 실패 횟수가 롤백되지 않도록 호출하는 트랜잭션에서 noRollbackFor 로 지정한다. */
public class EmailVerificationException extends IllegalArgumentException {

    public EmailVerificationException(String message) {
        super(message);
    }
}
