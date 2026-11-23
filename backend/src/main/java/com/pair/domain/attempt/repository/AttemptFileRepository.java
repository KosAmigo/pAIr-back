package com.pair.domain.attempt.repository;

import com.pair.domain.attempt.entity.AttemptFile;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AttemptFileRepository extends JpaRepository<AttemptFile, Long> {

    List<AttemptFile> findByAttemptIdOrderByPathAsc(Long attemptId);

    Optional<AttemptFile> findByAttemptIdAndPath(Long attemptId, String path);

    boolean existsByAttemptIdAndPath(Long attemptId, String path);
}
