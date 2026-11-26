package com.pair.domain.exam.service;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 주기 작업: 종료된 회차 자동 제출, 시작 하루 전 알림 메일, 보관 기간 지난 감독 사진 삭제.
 * 서버가 여러 대가 되면 ShedLock 같은 분산 잠금이 필요하다 (지금은 한 대 기준).
 * 테스트에서는 pair.exam.scheduler-enabled=false 로 끄고 서비스를 직접 부른다.
 */
@Component
@ConditionalOnProperty(name = "pair.exam.scheduler-enabled", havingValue = "true", matchIfMissing = true)
public class ExamSchedulerService {
    private static final Logger log = LoggerFactory.getLogger(ExamSchedulerService.class);
    private static final long ONE_HOUR_MS = 3_600_000;
    private static final long FIVE_MINUTES_MS = 300_000;

    private final AutoSubmitService autoSubmit;
    private final ProctorService proctor;
    private final ReminderService reminders;
    private final InviteMailService mailer;
    private final ConnectionGraceService grace;
    private final EnvCheckPhotoService envPhotos;

    public ExamSchedulerService(AutoSubmitService autoSubmit, ProctorService proctor, ReminderService reminders,
            InviteMailService mailer, ConnectionGraceService grace, EnvCheckPhotoService envPhotos) {
        this.envPhotos = envPhotos;
        this.grace = grace;
        this.autoSubmit = autoSubmit;
        this.proctor = proctor;
        this.reminders = reminders;
        this.mailer = mailer;
    }

    @Scheduled(fixedDelayString = "${pair.exam.auto-submit-interval-ms:2000}")
    public void closeEndedSessions() {
        for (Long id : autoSubmit.dueSessionIds()) {
            try {
                autoSubmit.close(id);
            } catch (RuntimeException e) {
                log.error("회차 {} 자동 제출 실패. 다음 주기에 다시 시도합니다.", id, e);
            }
        }
    }

    /** 진행 중 회차에서 유예(5분) 안에 돌아오지 못한 응시자의 링크를 만료시키고 기록을 남긴다 */
    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    public void expireAbsentLinks() {
        try {
            int n = grace.expireAbsent();
            if (n > 0) log.info("연결 유예를 넘긴 응시자 {}명의 링크를 만료시켰습니다", n);
        } catch (RuntimeException e) {
            log.error("연결 유예 점검 실패", e);
        }
    }

    @Scheduled(fixedDelay = FIVE_MINUTES_MS, initialDelay = 30_000)
    public void sendReminders() {
        try {
            List<Long> due = reminders.claimDue();
            if (due.isEmpty()) return;
            mailer.sendReminders(due);
            log.info("시작 하루 전 알림 메일 {}건", due.size());
        } catch (RuntimeException e) {
            log.error("알림 메일 처리 실패", e);
        }
    }

    @Scheduled(fixedDelay = ONE_HOUR_MS, initialDelay = 60_000)
    public void purgeSnapshots() {
        try {
            int n = proctor.purgeExpiredSnapshots();
            if (n > 0) log.info("보관 기간이 지난 감독 사진 {}장 삭제", n);
        } catch (RuntimeException e) {
            log.error("감독 사진 정리 실패", e);
        }
        try {
            int n = envPhotos.purgeExpired();
            if (n > 0) log.info("보관 기간이 지난 환경 점검 사진 {}장 삭제", n);
        } catch (RuntimeException e) {
            log.error("환경 점검 사진 정리 실패", e);
        }
    }
}
