package com.pair.company;

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
}
