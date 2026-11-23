package com.pair.domain.scoring.repository;

import com.pair.domain.scoring.entity.RubricItem;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RubricItemRepository extends JpaRepository<RubricItem, String> {}
