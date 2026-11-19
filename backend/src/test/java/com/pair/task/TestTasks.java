package com.pair.task;

import java.time.Instant;

/**
 * 테스트 DB(H2)는 Flyway 를 돌리지 않으므로 V3__base_rows.sql 과 같은 과제 행을 넣는다.
 * 테스트끼리 DB 를 같이 쓰므로 매번 공개(OPEN) 상태로 되돌린다.
 */
public final class TestTasks {

    public static final String SELF_DEV = "order-deadlock";
    public static final String SELF_BIZ = "pricing-plan";
    public static final String LIVE_DEV = "order-deadlock-live";
    public static final String LIVE_BIZ = "pricing-plan-live";

    private TestTasks() {}

    public static void seed(TaskRecordRepository repo) {
        Instant at = Instant.parse("2026-11-19T00:00:00Z");
        repo.save(new TaskRecord(1L, SELF_DEV, "주문 처리 장애 원인 분석", "DEV", 60, TaskRecord.Usage.SELF, TaskRecord.Status.OPEN, null, 1L, at));
        repo.save(new TaskRecord(2L, SELF_BIZ, "신규 저가 요금제 출시 기획안", "BIZ", 60, TaskRecord.Usage.SELF, TaskRecord.Status.OPEN, null, 1L, at));
        repo.save(new TaskRecord(3L, LIVE_DEV, "주문 처리 장애 원인 분석", "DEV", 60, TaskRecord.Usage.LIVE, TaskRecord.Status.OPEN, 1L, 1L, at));
        repo.save(new TaskRecord(4L, LIVE_BIZ, "신규 저가 요금제 출시 기획안", "BIZ", 60, TaskRecord.Usage.LIVE, TaskRecord.Status.OPEN, 2L, 1L, at));
    }
}
