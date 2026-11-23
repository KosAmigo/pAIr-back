package com.pair.domain.taskgen.service;

/** 동시에 도는 과제 생성 작업이 상한(pair.taskgen.max-concurrent)을 넘었을 때. 429 로 돌려준다 */
public class TooManyJobsException extends RuntimeException {
    public TooManyJobsException(String message) {
        super(message);
    }
}
