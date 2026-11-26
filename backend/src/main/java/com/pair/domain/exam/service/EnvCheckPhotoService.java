package com.pair.domain.exam.service;

import com.pair.global.security.InviteTokenService;
import com.pair.infra.storage.SnapshotStorage;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pair.domain.exam.dto.EnvCheckPhotoRequest;
import com.pair.domain.exam.dto.EnvCheckPhotoResponse;
import com.pair.domain.exam.entity.EnvCheckPhoto;
import com.pair.domain.exam.entity.ExamParticipant;
import com.pair.domain.exam.entity.ExamSession;
import com.pair.domain.exam.repository.EnvCheckPhotoRepository;
import com.pair.domain.exam.repository.ExamParticipantRepository;
import com.pair.global.config.ExamProperties;

/**
 * 환경 점검 사진(귀 왼쪽·오른쪽, 손목): 이어폰·스마트워치는 자동으로 분석하지 않고 감독관이 사진으로 확인한다.
 * 점검 화면이 찍은 사진을 올리면 대상자·단계마다 한 장을 두고, 다시 찍으면 덮어쓴다.
 * 시험 시작 전(입장 단계)에 올리므로 시작 여부는 묻지 않는다. 시험이 끝난 뒤(잠깐의 여유 제외)에는 받지 않는다.
 * 보관 기간은 감독 사진과 같다 (회차 종료 후 기본 7일, 지나면 파일만 지우고 기록은 남긴다).
 */
@Service
public class EnvCheckPhotoService {
    private static final Duration LATE_GRACE = Duration.ofMinutes(5);
    private static final int PURGE_BATCH = 500;

    private final ExamParticipantRepository participants;
    private final EnvCheckPhotoRepository photos;
    private final SnapshotValidationService validator;
    private final SnapshotStorage storage;
    private final ExamProperties props;
    private final Clock clock;

    public EnvCheckPhotoService(ExamParticipantRepository participants, EnvCheckPhotoRepository photos, SnapshotValidationService validator,
            SnapshotStorage storage, ExamProperties props, Clock clock) {
        this.participants = participants;
        this.photos = photos;
        this.validator = validator;
        this.storage = storage;
        this.props = props;
        this.clock = clock;
    }

    @Transactional
    public EnvCheckPhotoResponse upload(String token, EnvCheckPhotoRequest req) {
        Instant now = clock.instant();
        ExamParticipant p = findParticipant(token);
        ExamSession s = p.getSession();
        if (!s.isProctoring()) throw new IllegalStateException("감독을 쓰지 않는 회차입니다.");
        if (s.getCanceledAt() != null) throw new IllegalStateException("취소된 회차입니다.");
        if (now.isAfter(s.getEndsAt().plus(LATE_GRACE))) throw new IllegalStateException("끝난 시험의 점검 사진은 받지 않습니다.");

        byte[] jpeg = validator.decode(req.image());
        EnvCheckPhoto f = photos.findByParticipantIdAndStage(p.getId(), req.stage()).orElse(null);
        try {
            if (f == null) f = photos.saveAndFlush(new EnvCheckPhoto(p.getId(), req.stage(), now));
            else f.replace(jpeg.length, now);
        } catch (org.springframework.dao.DataIntegrityViolationException raced) {
            throw new IllegalStateException("같은 점검 사진을 다른 요청이 올리는 중입니다. 잠시 뒤 다시 시도해 주세요.");
        }
        // 파일 올리기가 실패하면 예외 → 기록도 롤백되어 주인 없는 기록이 남지 않는다
        String key = SnapshotStorage.envCheckKeyOf(s.getId(), f.getId());
        storage.put(key, jpeg);
        f.attachFile(key, jpeg.length);
        return EnvCheckPhotoResponse.from(f);
    }

    /** 콘솔: 대상자의 점검 사진 목록 (찍은 순서) */
    @Transactional(readOnly = true)
    public List<EnvCheckPhotoResponse> photosOf(Long sessionId, Long participantId) {
        ExamParticipant p = participants.findById(participantId)
                .filter(x -> x.getSession().getId().equals(sessionId))
                .orElseThrow(() -> new NoSuchElementException("없는 대상자입니다."));
        return photos.findByParticipantIdOrderByIdAsc(p.getId()).stream().map(EnvCheckPhotoResponse::from).toList();
    }

    /** 콘솔: 사진 보기. 다른 회차 번호로는 볼 수 없고, 보관 기간이 지나 지운 사진은 IllegalStateException */
    @Transactional(readOnly = true)
    public byte[] image(Long sessionId, Long photoId) {
        EnvCheckPhoto f = photos.findById(photoId).orElseThrow(() -> new NoSuchElementException("없는 사진입니다."));
        ExamParticipant p = participants.findById(f.getParticipantId()).orElseThrow();
        if (!p.getSession().getId().equals(sessionId)) throw new NoSuchElementException("없는 사진입니다.");
        if (f.getDeletedAt() != null) throw new IllegalStateException("보관 기간이 지나 삭제된 사진입니다.");
        return storage.get(f.getStorageKey()).orElseThrow(() -> new NoSuchElementException("사진 파일이 없습니다."));
    }

    /** 회차 종료 뒤 보관 기간이 지난 점검 사진 파일을 지우고 기록만 남긴다. @return 지운 장수 */
    @Transactional
    public int purgeExpired() {
        Instant now = clock.instant();
        Instant before = now.minus(Duration.ofDays(props.snapshotRetentionDays()));
        int total = 0;
        List<EnvCheckPhoto> batch;
        do {
            batch = photos.findExpired(before, PageRequest.of(0, PURGE_BATCH));
            for (EnvCheckPhoto f : batch) {
                storage.delete(f.getStorageKey());
                f.markDeleted(now);
            }
            photos.flush();
            total += batch.size();
        } while (batch.size() == PURGE_BATCH);
        return total;
    }

    private ExamParticipant findParticipant(String token) {
        if (!InviteTokenService.looksValid(token)) throw new NoSuchElementException("입장 링크가 올바르지 않습니다.");
        return participants.findByInviteTokenHash(InviteTokenService.hash(token)).orElseThrow(() -> new NoSuchElementException("입장 링크가 올바르지 않습니다."));
    }
}
