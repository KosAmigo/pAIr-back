package com.pair.scoring.db;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RubricCheckRecordRepository extends JpaRepository<RubricCheckRecord, Long> {
    Optional<RubricCheckRecord> findByCodeAndTrack(String code, String track);
}
