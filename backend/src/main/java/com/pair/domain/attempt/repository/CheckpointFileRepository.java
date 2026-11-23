package com.pair.domain.attempt.repository;

import com.pair.domain.attempt.entity.CheckpointFile;
import com.pair.domain.attempt.entity.Checkpoint;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CheckpointFileRepository extends JpaRepository<CheckpointFile, Long> {

    /** 한 응시의 체크포인트 파일 중 turn_no 가 upTo 이하인 것을 오래된 순으로 (겹치면 뒤의 것이 이긴다). 쿼리 한 번 */
    @Query("""
            select f from CheckpointFile f, Checkpoint c
            where f.checkpointId = c.id and c.attemptId = :attemptId and c.turnNo <= :upTo
            order by c.turnNo asc, f.path asc
            """)
    List<CheckpointFile> findUpTo(@Param("attemptId") Long attemptId, @Param("upTo") int upTo);
}
