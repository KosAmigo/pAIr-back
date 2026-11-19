package com.pair.attempt;

import java.time.Instant;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * 응시 목록 정보(DB attempts)와 응시 파일(AttemptStore)을 잇는다.
 * 응시 ID 는 DB 가 만든 attempt_id 이고, 파일 폴더 이름도 같은 숫자다.
 * 파일의 응시 상태가 저장될 때마다(제출, 후속 질문, 채점 완료·실패) DB 행을 맞춘다.
 */
@Component
public class AttemptRegistry {

    private static final Logger log = LoggerFactory.getLogger(AttemptRegistry.class);

    private final AttemptRecordRepository records;
    private final AttemptStore store;

    public AttemptRegistry(AttemptRecordRepository records, AttemptStore store) {
        this.records = records;
        this.store = store;
    }

    @PostConstruct
    void listen() {
        store.onSave(this::sync);
    }

    public AttemptRecord save(AttemptRecord r) {
        return records.save(r);
    }

    public Optional<AttemptRecord> find(String attemptId) {
        Long id = parse(attemptId);
        return id == null ? Optional.empty() : records.findById(id);
    }

    /** 파일 상태 → DB. 채점 툴 샘플처럼 DB 에 없는 응시는 건너뛴다 */
    public void sync(Attempt a) {
        find(a.id()).ifPresent(r -> {
            r.sync(a.status(), a.submittedAt(), a.followupsDoneAt());
            records.save(r);
        });
    }

    /** 회차 제출: 파일 쪽 제출과 별개로 DB 에 제출 시각을 바로 남긴다 (두 번 불러도 안전) */
    public void markSubmitted(String attemptId, Instant at) {
        find(attemptId).ifPresentOrElse(r -> {
            r.sync(Attempt.Status.FOLLOWUP, at, null);
            records.save(r);
        }, () -> log.warn("DB 에 없는 응시를 제출하려 함: {}", attemptId));
    }

    static Long parse(String attemptId) {
        if (attemptId == null || !attemptId.matches("\\d{1,18}")) return null;
        return Long.valueOf(attemptId);
    }
}
