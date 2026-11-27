package com.pair.domain.attempt.exception;

/** AI 채팅을 1분에 허용한 횟수보다 많이 보냄(1인 또는 서비스 전체). 429 와 Retry-After 로 돌려준다 */
public class TooManyChatsException extends RuntimeException {

    private final long retryAfterSeconds;

    public TooManyChatsException(String message, long retryAfterSeconds) {
        super(message);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
