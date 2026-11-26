package com.pair.domain.auth.exception;

/** 로그인 실패가 너무 많을 때(pair.auth.login-limit). 429 와 Retry-After 로 돌려준다 */
public class TooManyLoginAttemptsException extends RuntimeException {

    private final long retryAfterSeconds;

    public TooManyLoginAttemptsException(long retryAfterSeconds) {
        super("로그인 시도가 너무 많습니다. " + (retryAfterSeconds < 60 ? retryAfterSeconds + "초" : (retryAfterSeconds + 59) / 60 + "분")
                + " 뒤에 다시 시도해 주세요.");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
