package com.pair.domain.scoring.repository;

import com.pair.domain.scoring.entity.RubricCheck;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RubricCheckRepository extends JpaRepository<RubricCheck, Long> {
    Optional<RubricCheck> findByCodeAndTrack(String code, String track);
}
