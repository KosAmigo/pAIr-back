package com.pair.company;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface CompanyInquiryRepository extends JpaRepository<CompanyInquiry, Long> {
    List<CompanyInquiry> findAllByOrderByCreatedAtDesc();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from CompanyInquiry i where i.id = :id")
    Optional<CompanyInquiry> lockById(@Param("id") Long id);
}
