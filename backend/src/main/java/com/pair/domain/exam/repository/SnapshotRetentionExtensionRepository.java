package com.pair.domain.exam.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.pair.domain.exam.entity.SnapshotRetentionExtension;

public interface SnapshotRetentionExtensionRepository extends JpaRepository<SnapshotRetentionExtension, Long> {

    List<SnapshotRetentionExtension> findBySessionIdOrderBySeqAsc(Long sessionId);

    /** 삭제 배치용: 여러 회차의 연장 횟수를 한 번에 */
    interface SessionCount {
        Long getSessionId();
        long getCount();
    }

    @Query("select x.sessionId as sessionId, count(x) as count from SnapshotRetentionExtension x where x.sessionId in :ids group by x.sessionId")
    List<SessionCount> countBySessionIds(@Param("ids") Collection<Long> ids);
}
