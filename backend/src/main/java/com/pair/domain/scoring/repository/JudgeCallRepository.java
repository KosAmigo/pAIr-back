package com.pair.domain.scoring.repository;

import com.pair.domain.scoring.entity.JudgeCall;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface JudgeCallRepository extends JpaRepository<JudgeCall, Long> {
    List<JudgeCall> findByScoringId(Long scoringId);
}
