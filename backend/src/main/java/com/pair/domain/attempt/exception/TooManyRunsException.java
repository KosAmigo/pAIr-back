package com.pair.domain.attempt.exception;

/** 빌드·실행을 너무 자주 눌렀을 때(pair.run.rate-limit-per-minute). 429 로 돌려준다 */
public class TooManyRunsException extends RuntimeException {
    public TooManyRunsException(String message) {
        super(message);
    }
}
