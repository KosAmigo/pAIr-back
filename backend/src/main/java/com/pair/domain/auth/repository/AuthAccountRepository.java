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
}
