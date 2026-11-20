package com.pair.scoring.db;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PromptVersionRecordRepository extends JpaRepository<PromptVersionRecord, Long> {
    Optional<PromptVersionRecord> findFirstByNameOrderByIdAsc(String name);

    List<PromptVersionRecord> findByActiveTrue();

    boolean existsByName(String name);

    List<PromptVersionRecord> findAllByOrderByCreatedAtAscIdAsc();
}
