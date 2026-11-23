package com.pair.domain.attempt.service;

import com.pair.domain.attempt.entity.AttemptRecord;
import com.pair.domain.attempt.repository.AttemptRecordRepository;
import com.pair.domain.attempt.repository.DbAttemptStore;
import java.time.Instant;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 응시 행(DB attempts)을 만들고 찾는다. 응시 ID 는 DB 가 만든 attempt_id 를 문자열로 쓴다.
 * 응시 상태·작업 파일·대화 등 나머지는 DbAttemptStore 가 같은 행과 하위 테이블에 저장한다.
 */
@Component
public class AttemptRegistry {

    private static final Logger log = LoggerFactory.getLogger(AttemptRegistry.class);

    private final AttemptRecordRepository records;

    public AttemptRegistry(AttemptRecordRepository records) {
        this.records = records;
    }

    public AttemptRecord save(AttemptRecord r) {
        return records.save(r);
    }

    public Optional<AttemptRecord> find(String attemptId) {
        Long id = parse(attemptId);
        return id == null ? Optional.empty() : records.findById(id);
    }

    /** 회차 제출: 응시 화면 제출이 실패해도 제출 시각은 남긴다 (두 번 불러도 안전) */
    public void markSubmitted(String attemptId, Instant at) {
        find(attemptId).ifPresentOrElse(r -> {
            r.sync(Attempt.Status.FOLLOWUP, at, null);
            records.save(r);
        }, () -> log.warn("DB 에 없는 응시를 제출하려 함: {}", attemptId));
    }

    public static Long parse(String attemptId) {
        if (attemptId == null || !attemptId.matches("\\d{1,18}")) return null;
        return Long.valueOf(attemptId);
    }
}
