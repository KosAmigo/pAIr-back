package com.pair.domain.scoring.repository;

import com.pair.domain.scoring.entity.JudgeVerdict;
import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface JudgeVerdictRepository extends JpaRepository<JudgeVerdict, Long> {
    List<JudgeVerdict> findByResultId(Long resultId);

    List<JudgeVerdict> findByResultIdIn(Collection<Long> resultIds);
}
