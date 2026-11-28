package com.pair.domain.taskgen.repository;

import com.pair.domain.taskgen.entity.TaskGenJob;
import java.time.Instant;
import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * 상태를 바꾸는 쿼리는 모두 조건부 UPDATE 다(SKIP LOCKED 대신, H2·PostgreSQL 둘 다 됨).
 * 결과가 1행일 때만 내 것으로 본다.
 */
public interface TaskGenJobRepository extends JpaRepository<TaskGenJob, String> {

    List<TaskGenJob> findByEnvAndStatusOrderByCreatedAtAsc(String env, String status, Pageable page);

    long countByEnvAndStatus(String env, String status);

    /** 배치(BATCH) 모드로 실행 중인 작업 수(배치 창구가 '모두 기다리는 중인지' 볼 때) */
    long countByEnvAndStatusAndMode(String env, String status, String mode);

    /** 나보다 먼저 만든 대기 작업 수(대기 순번 = 이 값 + 1) */
    long countByEnvAndStatusAndCreatedAtBefore(String env, String status, Instant createdAt);

    List<TaskGenJob> findByBatchIdOrderByCreatedAtAsc(String batchId);

    /** 최근 끝난 작업(예상 비용 계산용, TaskGenCostService) */
    List<TaskGenJob> findTop200ByStatusAndCostUsdNotNullOrderByFinishedAtDesc(String status);

    /** 내 env 의 대기·실행 중인 작업과 since 이후에 끝난 작업 */
    @Query("select j from TaskGenJob j where j.env = :env and (j.status in :active or j.finishedAt >= :since) order by j.createdAt asc")
    List<TaskGenJob> findActive(@Param("env") String env, @Param("active") Collection<String> active, @Param("since") Instant since);

    /** 대기 → 실행. 1행이면 내가 집은 것 */
    @Transactional
    @Modifying
    @Query("update TaskGenJob j set j.status = 'RUNNING', j.workerId = :worker, j.startedAt = :now, j.heartbeatAt = :now, j.step = '시작' "
            + "where j.id = :id and j.status = 'QUEUED'")
    int claim(@Param("id") String id, @Param("worker") String worker, @Param("now") Instant now);

    /** 대기 작업 취소. 1행이면 취소됨 */
    @Transactional
    @Modifying
    @Query("update TaskGenJob j set j.status = 'CANCELED', j.finishedAt = :now, j.step = '취소됨' where j.id = :id and j.status = 'QUEUED'")
    int cancel(@Param("id") String id, @Param("now") Instant now);

    @Transactional
    @Modifying
    @Query("update TaskGenJob j set j.heartbeatAt = :now where j.status = 'RUNNING' and j.workerId = :worker")
    int heartbeat(@Param("worker") String worker, @Param("now") Instant now);

    /** 내 env 에서 heartbeat 가 끊긴 실행 중 작업을 대기로 되돌린다(서버가 꺼졌던 것) */
    @Transactional
    @Modifying
    @Query("update TaskGenJob j set j.status = 'QUEUED', j.workerId = null, j.step = '다시 대기' "
            + "where j.env = :env and j.status = 'RUNNING' and j.heartbeatAt < :limit")
    int requeueStale(@Param("env") String env, @Param("limit") Instant limit);

    @Transactional
    @Modifying
    @Query("update TaskGenJob j set j.step = :step, j.costUsd = :cost where j.id = :id and j.status = 'RUNNING' and j.workerId = :worker")
    int updateStep(@Param("id") String id, @Param("worker") String worker, @Param("step") String step, @Param("cost") Double cost);

    /** 끝남(내가 줍은 실행 중 작업만) */
    @Transactional
    @Modifying
    @Query("update TaskGenJob j set j.status = :status, j.step = :step, j.taskCode = :taskCode, j.passed = :passed, j.checks = :checks, "
            + "j.error = :error, j.costUsd = :cost, j.finishedAt = :now where j.id = :id and j.status = 'RUNNING' and j.workerId = :worker")
    int finish(@Param("id") String id, @Param("worker") String worker, @Param("status") String status, @Param("step") String step,
            @Param("taskCode") String taskCode, @Param("passed") Boolean passed, @Param("checks") String checks,
            @Param("error") String error, @Param("cost") Double cost, @Param("now") Instant now);
}
