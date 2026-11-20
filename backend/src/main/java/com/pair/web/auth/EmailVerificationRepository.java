package com.pair.web.auth;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface EmailVerificationRepository extends JpaRepository<EmailVerification, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from EmailVerification v where v.email = :email")
    Optional<EmailVerification> findByEmailForUpdate(@Param("email") String email);
}
