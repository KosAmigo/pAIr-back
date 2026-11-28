package com.pair.domain.taskgen.repository;

import com.pair.domain.taskgen.entity.TaskGenCall;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface TaskGenCallRepository extends JpaRepository<TaskGenCall, String> {

    Optional<TaskGenCall> findByJobIdAndInputHashAndNth(String jobId, String inputHash, int nth);

    List<TaskGenCall> findByJobId(String jobId);

    /** 이 작업이 배치에 맡기고 아직 결과를 못 받은 호출이 있는가 */
    boolean existsByJobIdAndStatus(String jobId, String status);

    /** 배치로 보낸 호출들에 배치 id 를 적는다 */
    @Transactional
    @Modifying
    @Query("update TaskGenCall c set c.providerBatchId = :batchId where c.id in :ids and c.status = 'PENDING'")
    int attachBatch(@Param("ids") Collection<String> ids, @Param("batchId") String batchId);

    /** 관리자 통계: 과제 생성 모델 호출 합계 */
    interface TokenSum {
        long getCalls();
        long getInputTokens();
        long getOutputTokens();
        long getCachedTokens();
    }

    @org.springframework.data.jpa.repository.Query("""
            select count(c) as calls, coalesce(sum(c.inputTokens), 0) as inputTokens,
                   coalesce(sum(c.outputTokens), 0) as outputTokens, coalesce(sum(c.cachedInputTokens), 0) as cachedTokens
            from TaskGenCall c where c.createdAt >= :from and c.createdAt < :to
            """)
    TokenSum sumBetween(@org.springframework.data.repository.query.Param("from") java.time.Instant from, @org.springframework.data.repository.query.Param("to") java.time.Instant to);
}
