package com.pair.exam.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.pair.exam.domain.ExamSession;

public interface ExamSessionRepository extends JpaRepository<ExamSession, Long> {

    @EntityGraph(attributePaths = "taskIds")
    List<ExamSession> findByCompanyIdOrderByStartsAtDesc(Long companyId);

    /** 관리자용 전체 목록 */
    @EntityGraph(attributePaths = "taskIds")
    List<ExamSession> findAllByOrderByStartsAtDesc();

    /** 권한 확인용: 회차의 회사만 (엔티티를 다 읽지 않는다) */
    @Query("select s.companyId from ExamSession s where s.id = :id")
    Optional<Long> findCompanyIdById(@Param("id") Long id);

    /** 종료 시각이 지났는데 아직 자동 제출 처리가 안 된 회차 (취소한 회차는 닫을 것이 없다) */
    List<ExamSession> findByEndsAtLessThanEqualAndClosedAtIsNullAndCanceledAtIsNull(Instant now);
}
