package com.pair.domain.attempt.exception;

/** 같은 응시를 너무 많이 채점했을 때(pair.rescore.max-per-day). 429 로 돌려준다 */
public class TooManyRescoresException extends RuntimeException {
    public TooManyRescoresException(String message) {
        super(message);
    }
}
