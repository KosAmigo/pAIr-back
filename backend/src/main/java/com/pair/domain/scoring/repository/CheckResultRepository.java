package com.pair.domain.scoring.repository;

import com.pair.domain.scoring.entity.CheckResult;
import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CheckResultRepository extends JpaRepository<CheckResult, Long> {
    List<CheckResult> findByScoringId(Long scoringId);

    List<CheckResult> findByScoringIdIn(Collection<Long> scoringIds);
}
