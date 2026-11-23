package com.pair.domain.scoring.repository;

import com.pair.domain.scoring.entity.PromptCheckHint;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PromptCheckHintRepository extends JpaRepository<PromptCheckHint, PromptCheckHint.Key> {
    List<PromptCheckHint> findByVersionId(Long versionId);
}
