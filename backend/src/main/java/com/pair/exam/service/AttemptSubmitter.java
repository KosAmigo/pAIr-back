package com.pair.exam.service;

import java.util.Collection;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.pair.attempt.Attempt;
import com.pair.attempt.AttemptRecord;
import com.pair.attempt.AttemptService;
import com.pair.attempt.AttemptStore;

/**
 * 회차와 응시 파일(AttemptStore) 사이의 경계. 응시 행(DB attempts)은 회차 서비스가 만들고, 여기서는 파일만 다룬다.
 * 아직 풀고 있는(SOLVING) 응시만 제출한다. 이미 제출했거나 후속 질문 단계면 건드리지 않는다.
 * 제출 뒤 후속 질문·채점 큐는 AttemptService 가 이어 받는다.
 */
@Component
public class AttemptSubmitter {
    private static final Logger log = LoggerFactory.getLogger(AttemptSubmitter.class);

    private final AttemptService attempts;
    private final AttemptStore store;

    public AttemptSubmitter(AttemptService attempts, AttemptStore store) {
        this.attempts = attempts;
        this.store = store;
    }

    /** 응시 행에 작업 파일을 만든다. definitionCode: 과제 내용이 있는 원본 code */
    public void prepare(AttemptRecord r, String definitionCode) {
        attempts.createFiles(r, definitionCode);
    }

    /** @return 실제로 제출한 응시 수 */
    public int submitAll(Collection<String> attemptIds) {
        int n = 0;
        for (String attemptId : attemptIds) {
            try {
                if (submitOne(attemptId)) n++;
            } catch (RuntimeException e) {
                // 한 응시 파일이 깨져도 나머지 응시자의 제출은 계속한다
                log.error("응시 제출 실패: {}", attemptId, e);
            }
        }
        return n;
    }

    /** 과제 하나 제출. 이미 제출했으면 false (두 번 불러도 안전) */
    public boolean submitOne(String attemptId) {
        try {
            if (store.load(attemptId).status() != Attempt.Status.SOLVING) return false;
            attempts.submit(attemptId);
            return true;
        } catch (IllegalStateException e) {
            // 그 사이 응시자가 응시 화면에서 직접 제출한 경우
            log.debug("이미 제출된 응시: {}", attemptId);
            return false;
        }
    }
}
