package com.pair.domain.company.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.pair.domain.company.entity.Company;

import jakarta.persistence.LockModeType;

public interface CompanyRepository extends JpaRepository<Company, Long> {

    /** 관리자 회사 목록: 삭제한 회사는 빼고 */
    List<Company> findByDeletedAtIsNullOrderByIdAsc();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Company c where c.id = :id")
    Optional<Company> lockById(@Param("id") Long id);

    /** 관리자 통계: 삭제하지 않은 회사 수 */
    long countByDeletedAtIsNull();
}
