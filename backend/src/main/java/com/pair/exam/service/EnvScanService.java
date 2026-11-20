package com.pair.exam.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pair.exam.ExamProperties;
import com.pair.exam.domain.EnvScanPhoto;
import com.pair.exam.domain.ExamParticipant;
import com.pair.exam.domain.ExamSession;
import com.pair.exam.repository.EnvScanPhotoRepository;
import com.pair.exam.repository.ExamParticipantRepository;
import com.pair.exam.web.ExamDtos.EnvScanPhotoView;
import com.pair.exam.web.ExamDtos.EnvScanRequest;

/**
 * 환경 점검 사진: 휴대폰으로 방을 한 바퀴(360도) 돌며 방향별로 찍은 사진과 책상 위 사진을 파일 저장소에 올린다.
 * 시험 시작 전(입장 단계)에 올리므로 감독 이벤트와 달리 시작 여부는 묻지 않는다. 시험이 끝난 뒤(잠깐의 여유 제외)에는 받지 않는다.
 * 보관 기간은 감독 사진과 같다 (회차 종료 후 기본 30일, 지나면 파일만 지우고 기록은 남긴다).
 */
@Service
public class EnvScanService {
    /** 한 대상자가 올릴 수 있는 점검 사진 수. 다시 스캔을 여러 번 해도 넉넉하게 (한 번에 방 12장 + 책상 몇 장) */
    public static final int MAX_PHOTOS = 100;
    private static final Duration LATE_GRACE = Duration.ofMinutes(5);
    private static final String PHONE_LABEL = "cell phone";
    private static final int PURGE_BATCH = 500;

    private final ExamParticipantRepository participants;
    private final EnvScanPhotoRepository photos;
    private final SnapshotValidator validator;
    private final SnapshotStorage storage;
    private final ExamProperties props;
    private final Clock clock;

    public EnvScanService(ExamParticipantRepository participants, EnvScanPhotoRepository photos, SnapshotValidator validator,
            SnapshotStorage storage, ExamProperties props, Clock clock) {
        this.participants = participants;
        this.photos = photos;
        this.validator = validator;
        this.storage = storage;
        this.props = props;
        this.clock = clock;
    }

    @Transactional
    public EnvScanPhotoView upload(String token, EnvScanRequest req) {
        Instant now = clock.instant();
        ExamParticipant p = findParticipant(token);
        ExamSession s = p.getSession();
        if (!s.isProctoring()) throw new IllegalStateException("감독을 쓰지 않는 회차입니다.");
        if (s.getCanceledAt() != null) throw new IllegalStateException("취소된 회차입니다.");
        if (now.isAfter(s.getEndsAt().plus(LATE_GRACE))) throw new IllegalStateException("끝난 시험의 점검 사진은 받지 않습니다.");
        if (photos.countByParticipantId(p.getId()) >= MAX_PHOTOS) throw new IllegalStateException("점검 사진이 너무 많습니다.");

        byte[] jpeg = validator.decode(req.image());
        List<String> labels = req.objects() == null ? List.of() : req.objects().stream().map(String::trim).filter(x -> !x.isEmpty()).toList();
        EnvScanPhoto f = photos.save(new EnvScanPhoto(p.getId(), req.stage(), req.angle() == null ? null : req.angle().shortValue(),
                labels.isEmpty() ? null : String.join(",", labels), labels.contains(PHONE_LABEL), now));
        // 파일 올리기가 실패하면 예외 → 기록도 롤백되어 주인 없는 기록이 남지 않는다
        String key = SnapshotStorage.envScanKeyOf(s.getId(), f.getId());
        storage.put(key, jpeg);
        f.attachFile(key, jpeg.length);
        return EnvScanPhotoView.from(f);
    }

    /** 콘솔: 대상자의 점검 사진 목록 (찍은 순서) */
    @Transactional(readOnly = true)
    public List<EnvScanPhotoView> photosOf(Long sessionId, Long participantId) {
        ExamParticipant p = participants.findById(participantId)
                .filter(x -> x.getSession().getId().equals(sessionId))
                .orElseThrow(() -> new NoSuchElementException("없는 대상자입니다."));
        return photos.findByParticipantIdOrderByIdAsc(p.getId()).stream().map(EnvScanPhotoView::from).toList();
    }

    /** 콘솔: 사진 보기. 다른 회차 번호로는 볼 수 없고, 보관 기간이 지나 지운 사진은 IllegalStateException */
    @Transactional(readOnly = true)
    public byte[] image(Long sessionId, Long photoId) {
        EnvScanPhoto f = photos.findById(photoId).orElseThrow(() -> new NoSuchElementException("없는 사진입니다."));
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
        List<EnvScanPhoto> batch;
        do {
            batch = photos.findExpired(before, PageRequest.of(0, PURGE_BATCH));
            for (EnvScanPhoto f : batch) {
                storage.delete(f.getStorageKey());
                f.markDeleted(now);
            }
            photos.flush();
            total += batch.size();
        } while (batch.size() == PURGE_BATCH);
        return total;
    }

    private ExamParticipant findParticipant(String token) {
        if (!InviteTokens.looksValid(token)) throw new NoSuchElementException("입장 링크가 올바르지 않습니다.");
        return participants.findByInviteTokenHash(InviteTokens.hash(token)).orElseThrow(() -> new NoSuchElementException("입장 링크가 올바르지 않습니다."));
    }
}
