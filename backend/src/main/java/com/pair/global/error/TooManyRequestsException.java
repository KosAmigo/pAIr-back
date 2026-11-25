package com.pair.global.error;

/** 한 IP 에서 너무 많이 요청했을 때(pair.ip-limit). 429 와 Retry-After 로 돌려준다 */
public class TooManyRequestsException extends RuntimeException {

    private final long retryAfterSeconds;

    public TooManyRequestsException(long retryAfterSeconds) {
        super("요청이 너무 많습니다. " + (retryAfterSeconds < 60 ? retryAfterSeconds + "초" : (retryAfterSeconds + 59) / 60 + "분")
                + " 뒤에 다시 시도해 주세요.");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
