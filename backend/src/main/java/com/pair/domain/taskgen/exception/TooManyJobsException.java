package com.pair.domain.taskgen.exception;

/** AI 추천 호출이 분당 한도를 넘었을 때 등 요청이 너무 잦을 때. 429 로 돌려준다(생성 작업은 상한을 넘으면 거절하지 않고 대기열에서 기다린다) */
public class TooManyJobsException extends RuntimeException {
    public TooManyJobsException(String message) {
        super(message);
    }
}
