package com.pair.domain.company.repository;

import com.pair.domain.company.entity.CompanyInvite;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface CompanyInviteRepository extends JpaRepository<CompanyInvite, Long> {
    Optional<CompanyInvite> findByTokenHash(String tokenHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from CompanyInvite i where i.tokenHash = :tokenHash")
    Optional<CompanyInvite> lockByTokenHash(@Param("tokenHash") String tokenHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from CompanyInvite i where lower(i.email) = lower(:email) and i.acceptedAt is null")
    List<CompanyInvite> lockPendingByEmail(@Param("email") String email);

    /** 회사 삭제 때 아직 수락·취소되지 않은 초대를 모두 무효로 한다 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from CompanyInvite i where i.companyId = :companyId and i.acceptedAt is null and i.revokedAt is null")
    List<CompanyInvite> lockPendingByCompanyId(@Param("companyId") Long companyId);
}
