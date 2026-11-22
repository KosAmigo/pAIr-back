package com.pair.exam.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.pair.exam.domain.EnvCheckProgress;

public interface EnvCheckProgressRepository extends JpaRepository<EnvCheckProgress, Long> {
}
