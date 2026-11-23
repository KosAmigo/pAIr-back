package com.pair.domain.company.repository;

import com.pair.domain.company.entity.CompanyAccountEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CompanyAccountEventRepository extends JpaRepository<CompanyAccountEvent, Long> {}
