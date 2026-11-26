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
}
