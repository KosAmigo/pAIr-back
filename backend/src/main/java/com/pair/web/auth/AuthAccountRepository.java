package com.pair.web.auth;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

/** 회원 이메일을 대소문자 구분 없이 조회하고 중복 가입을 확인한다. */
public interface AuthAccountRepository extends JpaRepository<AuthAccount, Long> {
    Optional<AuthAccount> findByEmailIgnoreCase(String email);
    boolean existsByEmailIgnoreCase(String email);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from AuthAccount a where a.id = :id")
    Optional<AuthAccount> lockById(@Param("id") Long id);
}
