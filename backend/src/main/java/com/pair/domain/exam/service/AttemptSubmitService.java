package com.pair.domain.exam.service;

import java.util.Collection;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.pair.domain.attempt.dto.AttemptStateDto;
import com.pair.domain.attempt.entity.Attempt;
import com.pair.domain.attempt.service.AttemptService;
import com.pair.domain.attempt.repository.AttemptStateRepository;

/**
 * 회차와 응시 파일(AttemptStateRepository) 사이의 경계. 응시 행(DB attempts)은 회차 서비스가 만들고, 여기서는 파일만 다룬다.
 * submitAll/submitOne 은 아직 풀고 있는(SOLVING) 응시만 제출한다. 이미 제출했거나 후속 질문 단계면 건드리지 않는다.
 * 제출 뒤 후속 질문·채점 큐는 AttemptService 가 이어 받는다.
 * finishAll 은 시험 종료 시각에 쓴다. 후속 질문 단계에서 멈춘 응시를 빈 답으로 마무리해 채점까지 보낸다.
 */
@Component
public class AttemptSubmitService {
    private static final Logger log = LoggerFactory.getLogger(AttemptSubmitService.class);

    private final AttemptService attempts;
    private final AttemptStateRepository store;

    public AttemptSubmitService(AttemptService attempts, AttemptStateRepository store) {
        this.attempts = attempts;
        this.store = store;
    }

    /** 응시 행에 작업 파일을 만든다. definitionCode: 과제 내용이 있는 원본 code */
    public void prepare(Attempt r, String definitionCode) {
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

    /**
     * 시험 종료 시각에 응시를 끝까지 넘긴다: 풀이 중이면 제출하고, 후속 질문 단계면 빈 답으로 마무리해 채점 큐에 올린다.
     * 이미 채점 단계(SCORING, DONE, ERROR)인 응시는 건드리지 않아 두 번 불러도 안전하다.
     * 한 응시가 깨져도 나머지는 계속한다.
     *
     * @return 마무리하지 못한 응시 수
     */
    public int finishAll(Collection<String> attemptIds) {
        int failed = 0;
        for (String attemptId : attemptIds) {
            try {
                finishOne(attemptId);
            } catch (RuntimeException e) {
                failed++;
                log.error("종료 시각 응시 마무리 실패: {}", attemptId, e);
            }
        }
        return failed;
    }

    private void finishOne(String attemptId) {
        try {
            if (store.load(attemptId).status() == AttemptStateDto.Status.SOLVING) attempts.submit(attemptId);
            if (store.load(attemptId).status() == AttemptStateDto.Status.FOLLOWUP) attempts.followUps(attemptId, List.of());
        } catch (IllegalStateException e) {
            // 그 사이 응시자가 직접 제출하거나 후속 질문을 마쳐 이미 채점 단계로 넘어갔으면 할 일이 없다. 아니면 진짜 실패다
            AttemptStateDto.Status now = store.load(attemptId).status();
            if (now == AttemptStateDto.Status.SOLVING || now == AttemptStateDto.Status.FOLLOWUP) throw e;
            log.debug("이미 마무리된 응시: {} ({})", attemptId, now);
        }
    }

    /** 과제 하나 제출. 이미 제출했으면 false (두 번 불러도 안전) */
    public boolean submitOne(String attemptId) {
        try {
            if (store.load(attemptId).status() != AttemptStateDto.Status.SOLVING) return false;
            attempts.submit(attemptId);
            return true;
        } catch (IllegalStateException e) {
            // 그 사이 응시자가 응시 화면에서 직접 제출한 경우
            log.debug("이미 제출된 응시: {}", attemptId);
            return false;
        }
    }
}
