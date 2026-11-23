package com.pair.domain.company.repository;

import com.pair.domain.company.entity.CompanyInquiryMember;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface CompanyInquiryMemberRepository extends JpaRepository<CompanyInquiryMember, Long> {
    List<CompanyInquiryMember> findByInquiryIdOrderById(Long inquiryId);
    List<CompanyInquiryMember> findByInquiryIdIn(List<Long> inquiryIds);

    @Modifying
    @Transactional
    @Query("delete from CompanyInquiryMember m where m.inquiryId = :inquiryId")
    void deleteByInquiryId(@Param("inquiryId") Long inquiryId);
}
