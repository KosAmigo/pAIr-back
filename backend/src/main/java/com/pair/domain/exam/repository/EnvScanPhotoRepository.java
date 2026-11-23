package com.pair.domain.exam.repository;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.pair.domain.exam.entity.EnvScanPhoto;

public interface EnvScanPhotoRepository extends JpaRepository<EnvScanPhoto, Long> {

    List<EnvScanPhoto> findByParticipantIdOrderByIdAsc(Long participantId);

    long countByParticipantId(Long participantId);

    /** 회차가 endedBefore 이전에 끝났고 아직 파일을 지우지 않은 사진 */
    @Query("""
            select f from EnvScanPhoto f, ExamParticipant p
            where p.id = f.participantId and p.session.endsAt < :endedBefore
              and f.storageKey is not null and f.deletedAt is null
            order by f.id
            """)
    List<EnvScanPhoto> findExpired(@Param("endedBefore") Instant endedBefore, Pageable page);
}
