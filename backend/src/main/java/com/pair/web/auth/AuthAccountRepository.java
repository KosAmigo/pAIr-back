package com.pair.web.auth;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

/** 회원 이메일을 대소문자 구분 없이 조회하고 중복 가입을 확인한다. */
public interface AuthAccountRepository extends JpaRepository<AuthAccount, Long> {
    Optional<AuthAccount> findByEmailIgnoreCase(String email);
    boolean existsByEmailIgnoreCase(String email);
}
