package com.pair.web.auth;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 해시된 로그인 토큰으로 유효 세션을 찾고 만료·로그아웃 세션을 삭제한다. */
public interface AuthSessionRepository extends JpaRepository<AuthSession, String> {

    /** 아직 만료되지 않은 세션과 회원 계정을 한 번의 조회로 가져온다. */
    @EntityGraph(attributePaths = "account")
    Optional<AuthSession> findByTokenHashAndExpiresAtAfter(String tokenHash, Instant now);

    /** 로그아웃한 단일 세션을 토큰 해시로 폐기한다. */
    @Modifying
    @Query("delete from AuthSession s where s.tokenHash = :tokenHash")
    int revoke(@Param("tokenHash") String tokenHash);

    /** 새 세션 발급 시 만료된 세션 기록을 정리한다. */
    @Modifying
    @Query("delete from AuthSession s where s.expiresAt <= :now")
    int deleteExpired(@Param("now") Instant now);
}
