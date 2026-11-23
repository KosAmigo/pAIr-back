package com.pair.domain.auth.repository;

import com.pair.domain.auth.entity.RefreshSession;
import java.time.Instant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshSessionRepository extends JpaRepository<RefreshSession, String> {
    @Modifying
    @Query("""
            update RefreshSession s set s.tokenHash = :newHash, s.expiresAt = :newExpiry
            where s.sessionId = :id and s.userId = :userId and s.tokenHash = :oldHash and s.expiresAt > :now
            """)
    int rotate(@Param("id") String id, @Param("userId") long userId, @Param("oldHash") String oldHash,
            @Param("newHash") String newHash, @Param("now") Instant now, @Param("newExpiry") Instant newExpiry);

    @Modifying
    @Query("""
            delete from RefreshSession s where s.sessionId = :id and s.userId = :userId
            and s.tokenHash = :hash and s.expiresAt > :now
            """)
    int revoke(@Param("id") String id, @Param("userId") long userId, @Param("hash") String hash,
            @Param("now") Instant now);

    @Modifying
    @Query("delete from RefreshSession s where s.userId = :userId")
    int revokeAll(@Param("userId") long userId);

    @Modifying
    @Query("delete from RefreshSession s where s.expiresAt <= :now")
    int deleteExpired(@Param("now") Instant now);
}
