package com.pair.domain.attempt.repository;

import com.pair.domain.attempt.entity.AttemptEvent;
import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface AttemptEventRepository extends JpaRepository<AttemptEvent, Long> {

    List<AttemptEvent> findByAttemptIdOrderByIdAsc(Long attemptId);

    /** 이 중 type 말고 다른 작업 기록이 있는 응시 */
    @Query("select distinct e.attemptId from AttemptEvent e where e.attemptId in :attemptIds and e.type <> :type")
    List<Long> findAttemptIdsWithEventsOtherThan(Collection<Long> attemptIds, String type);
}
