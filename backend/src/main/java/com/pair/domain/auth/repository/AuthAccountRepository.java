package com.pair.domain.auth.repository;

import com.pair.domain.auth.entity.AuthAccount;
import java.util.Collection;
import java.util.Optional;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

/** 회원 이메일을 대소문자 구분 없이 조회하고 중복 가입을 확인한다. */
public interface AuthAccountRepository extends JpaRepository<AuthAccount, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<AuthAccount> findByEmailIgnoreCase(String email);
    boolean existsByEmailIgnoreCase(String email);

    /** 이메일은 소문자로 넘긴다. */
    @Query("select a.email from AuthAccount a where lower(a.email) in :emails order by a.id")
    List<String> findRegisteredEmails(@Param("emails") Collection<String> emails);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from AuthAccount a where lower(a.email) in :emails order by a.id")
    List<AuthAccount> lockByEmailIn(@Param("emails") Collection<String> emails);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from AuthAccount a where a.id = :id")
    Optional<AuthAccount> lockById(@Param("id") Long id);

    @Query("select a.companyId from AuthAccount a where a.id = :id")
    Optional<Long> findCompanyIdById(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from AuthAccount a where a.companyId = :companyId order by a.id")
    List<AuthAccount> lockByCompanyId(@Param("companyId") Long companyId);

    List<AuthAccount> findByCompanyIdOrderByRoleAscNameAsc(Long companyId);

    /** 관리자 통계: 탈퇴하지 않은 계정 수를 역할별로 */
    interface RoleCount {
        com.pair.global.security.CurrentUser.Role getRole();
        long getCount();
    }

    @org.springframework.data.jpa.repository.Query("select a.role as role, count(a) as count from AuthAccount a where a.memberStatus <> 'WITHDRAWN' group by a.role")
    java.util.List<RoleCount> countActiveByRole();

    /** 관리자 통계: 기간 안에 가입한 일반 회원 수 */
    @org.springframework.data.jpa.repository.Query("""
            select count(a) from AuthAccount a
            where a.role = com.pair.global.security.CurrentUser.Role.USER and a.createdAt >= :from and a.createdAt < :to
            """)
    long countNewUsersBetween(@org.springframework.data.repository.query.Param("from") java.time.Instant from, @org.springframework.data.repository.query.Param("to") java.time.Instant to);

    /** 관리자 통계: 기간 안에 탈퇴한(회사 삭제로 정리된 멤버 포함) 계정 수 */
    @org.springframework.data.jpa.repository.Query("select count(a) from AuthAccount a where a.memberStatus = 'WITHDRAWN' and a.deletedAt >= :from and a.deletedAt < :to")
    long countWithdrawnBetween(@org.springframework.data.repository.query.Param("from") java.time.Instant from, @org.springframework.data.repository.query.Param("to") java.time.Instant to);
}
