package com.pair.domain.exam.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.pair.domain.exam.entity.EnvCheckProgress;

public interface EnvCheckProgressRepository extends JpaRepository<EnvCheckProgress, Long> {
}
