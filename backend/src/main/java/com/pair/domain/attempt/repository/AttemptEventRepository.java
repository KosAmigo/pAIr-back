package com.pair.domain.attempt.repository;

import com.pair.domain.attempt.entity.AttemptEvent;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AttemptEventRepository extends JpaRepository<AttemptEvent, Long> {

    List<AttemptEvent> findByAttemptIdOrderByIdAsc(Long attemptId);
}
