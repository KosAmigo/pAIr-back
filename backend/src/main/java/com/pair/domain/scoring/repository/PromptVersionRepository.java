package com.pair.domain.scoring.repository;

import com.pair.domain.scoring.entity.PromptVersion;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PromptVersionRepository extends JpaRepository<PromptVersion, Long> {
    Optional<PromptVersion> findFirstByNameOrderByIdAsc(String name);

    List<PromptVersion> findByActiveTrue();

    boolean existsByName(String name);

    List<PromptVersion> findAllByOrderByCreatedAtAscIdAsc();
}
