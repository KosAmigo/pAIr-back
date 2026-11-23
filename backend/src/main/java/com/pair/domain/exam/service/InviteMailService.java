package com.pair.domain.exam.service;

import com.pair.domain.exam.dto.InvitesCreatedDto;
import com.pair.domain.exam.dto.SessionNoticeDto;
import com.pair.global.security.InviteTokenService;
import com.pair.infra.mail.MailLayout;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import com.pair.global.config.ExamProperties;
import com.pair.domain.exam.entity.ExamParticipant;
import com.pair.domain.exam.entity.ExamSession;
import com.pair.domain.exam.repository.ExamParticipantRepository;

/**
 * 회차 메일: 초대(등록 즉시·다시 보내기), 변경·취소 안내, 시작 하루 전 알림.
 * 초대·안내는 트랜잭션이 커밋된 뒤(AFTER_COMMIT)에 별도 스레드(@Async)에서 보낸다.
 * 그래서 1000명을 올려도 업로드 응답은 바로 돌아오고, 메일 서버가 느리거나 죽어도 등록은 남는다.
 * 초대 결과(SENT/FAILED)는 대상자마다 짧은 트랜잭션으로 저장한다. 실패한 초대는 콘솔에서 다시 보낼 수 있다.
 * 입장 토큰 원문은 DB 에 없어서 초대 메일에만 링크가 들어간다. 안내·알림 메일은 "초대 메일의 링크"를 가리킨다.
 */
@Component
public class InviteMailService {
    private static final Logger log = LoggerFactory.getLogger(InviteMailService.class);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(KST);

    private final JavaMailSender mail;
    private final ExamParticipantRepository participants;
    private final TransactionTemplate tx;
    private final ExamProperties props;

    public InviteMailService(JavaMailSender mail, ExamParticipantRepository participants, TransactionTemplate tx,
            ExamProperties props) {
        this.mail = mail;
        this.participants = participants;
        this.tx = tx;
        this.props = props;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onInvitesCreated(InvitesCreatedDto event) {
        for (InvitesCreatedDto.Invite invite : event.invites()) sendInvite(invite);
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSessionNotice(SessionNoticeDto event) {
        for (Long id : event.participantIds()) {
            Mail msg = tx.execute(status -> participants.findById(id)
                    .map(p -> notice(p, event.kind())).orElse(null));
            if (msg != null) trySend(msg, id, "안내");
        }
    }

    /** 하루 전 알림. 스케줄러 스레드에서 바로 부른다 (발송 시각은 이미 잡아 둠) */
    public void sendReminders(List<Long> participantIds) {
        for (Long id : participantIds) {
            Mail msg = tx.execute(status -> participants.findById(id).map(this::reminder).orElse(null));
            if (msg != null) trySend(msg, id, "알림");
        }
    }

    void sendInvite(InvitesCreatedDto.Invite invite) {
        Long id = invite.participantId();
        Mail msg = tx.execute(status -> participants.findById(id)
                // 그 사이 다시 보내기로 토큰이 바뀌었으면 옛 링크는 보내지 않는다
                .filter(p -> p.getInviteTokenHash().equals(InviteTokenService.hash(invite.token())))
                .map(p -> invite(p, invite.token())).orElse(null));
        if (msg == null) return;
        String error = trySend(msg, id, "초대");
        tx.executeWithoutResult(status -> participants.recordInviteResult(id, InviteTokenService.hash(invite.token()),
                error == null ? ExamParticipant.InviteStatus.SENT : ExamParticipant.InviteStatus.FAILED,
                ExamParticipant.trimInviteError(error)));
    }

    /** @return 실패하면 오류 문구, 성공하면 null */
    private String trySend(Mail msg, Long participantId, String kind) {
        try {
            mail.send(toMime(msg));
            return null;
        } catch (MailException e) {
            // 메일 주소는 개인정보라 로그에 남기지 않는다
            log.warn("{} 메일 발송 실패: participantId={} ({})", kind, participantId, e.getClass().getSimpleName());
            return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        }
    }

    /** 글자 본문과 HTML 본문을 함께 담는다. HTML 을 못 보는 클라이언트는 글자 본문을 보여 준다 */
    private MimeMessage toMime(Mail m) {
        try {
            MimeMessage mime = mail.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mime, true, "UTF-8");
            helper.setFrom(props.mailFrom());
            helper.setTo(m.to());
            helper.setSubject(m.subject());
            helper.setText(m.text(), m.html());
            helper.addInline(MailLayout.LOGO_CID, MailLayout.LOGO, "image/png");
            return mime;
        } catch (MessagingException e) {
            throw new MailPreparationException(e);
        }
    }

    /** 보낼 메일 한 통: 받는 주소, 제목, 글자 본문, HTML 본문 */
    record Mail(String to, String subject, String text, String html) {
    }

    Mail invite(ExamParticipant p, String token) {
        ExamSession s = p.getSession();
        String url = entryUrl(token);
        String when = WHEN.format(s.getStartsAt()) + " ~ " + WHEN.format(s.getEndsAt()) + " (한국 시간)";
        String text = """
                %s님, 안녕하세요.

                %s 시험에 초대되었습니다.
                시험 시간: %s

                아래 링크로 입장하세요. 링크는 본인에게만 발급되었으니 다른 사람과 공유하지 마세요.
                이 메일을 다시 받았다면 이전 메일의 링크는 더 이상 쓸 수 없습니다.
                %s

                시작 시각 전에 미리 입장해 카메라 점검을 마쳐 주세요.
                """.formatted(p.getName(), s.getTitle(), when, url);
        String html = MailLayout.html(s.getTitle() + " 시험에 초대되었습니다", p.getName() + "님, 안녕하세요.",
                List.of("아래 단추로 시험에 입장하세요."),
                List.of(new MailLayout.Fact("시험 시간", when)),
                new MailLayout.Button("시험 입장하기", url),
                List.of("입장 링크는 본인에게만 발급되었습니다. 다른 사람과 공유하지 마세요.",
                        "이 메일을 다시 받았다면 이전 메일의 링크는 더 이상 쓸 수 없습니다.",
                        "시작 시각 전에 미리 입장해 카메라 점검을 마쳐 주세요."));
        return message(p, "[pAIr] " + s.getTitle() + " 시험 안내", text, html);
    }

    Mail notice(ExamParticipant p, SessionNoticeDto.Kind kind) {
        ExamSession s = p.getSession();
        String when = WHEN.format(s.getStartsAt()) + " ~ " + WHEN.format(s.getEndsAt()) + " (한국 시간)";
        return switch (kind) {
            case CHANGED -> message(p, "[pAIr] " + s.getTitle() + " 시험 일정 변경 안내", """
                    %s님, 안녕하세요.

                    %s 시험 내용이 바뀌었습니다.
                    바뀐 시험 시간: %s

                    입장은 처음 받은 초대 메일의 링크를 그대로 쓰면 됩니다.
                    """.formatted(p.getName(), s.getTitle(), when),
                    MailLayout.html(s.getTitle() + " 시험 일정이 변경되었습니다", p.getName() + "님, 안녕하세요.",
                            List.of("시험 내용이 바뀌었습니다."),
                            List.of(new MailLayout.Fact("바뀐 시험 시간", when)), null,
                            List.of("입장은 처음 받은 초대 메일의 링크를 그대로 쓰면 됩니다.")));
            case CANCELED -> message(p, "[pAIr] " + s.getTitle() + " 시험 취소 안내", """
                    %s님, 안녕하세요.

                    %s 시험(%s 시작 예정)이 취소되었습니다.
                    초대 메일의 입장 링크는 더 이상 쓸 수 없습니다.
                    """.formatted(p.getName(), s.getTitle(), WHEN.format(s.getStartsAt())),
                    MailLayout.html(s.getTitle() + " 시험이 취소되었습니다", p.getName() + "님, 안녕하세요.",
                            List.of(s.getTitle() + " 시험(" + WHEN.format(s.getStartsAt()) + " 시작 예정)이 취소되었습니다."),
                            List.of(), null, List.of("초대 메일의 입장 링크는 더 이상 쓸 수 없습니다.")));
        };
    }

    Mail reminder(ExamParticipant p) {
        ExamSession s = p.getSession();
        String when = WHEN.format(s.getStartsAt()) + " ~ " + WHEN.format(s.getEndsAt()) + " (한국 시간)";
        return message(p, "[pAIr] 내일 " + s.getTitle() + " 시험이 있습니다", """
                %s님, 안녕하세요.

                %s 시험이 곧 시작합니다.
                시험 시간: %s

                초대 메일의 입장 링크로 들어오세요. 링크를 찾을 수 없으면 시험 담당자에게 다시 보내 달라고 요청하세요.
                시작 시각 전에 미리 입장해 카메라 점검을 마쳐 주세요.
                """.formatted(p.getName(), s.getTitle(), when),
                MailLayout.html("내일 " + s.getTitle() + " 시험이 있습니다", p.getName() + "님, 안녕하세요.",
                        List.of(s.getTitle() + " 시험이 곧 시작합니다."),
                        List.of(new MailLayout.Fact("시험 시간", when)), null,
                        List.of("초대 메일의 입장 링크로 들어오세요. 링크를 찾을 수 없으면 시험 담당자에게 다시 보내 달라고 요청하세요.",
                                "시작 시각 전에 미리 입장해 카메라 점검을 마쳐 주세요.")));
    }

    private Mail message(ExamParticipant p, String subject, String text, String html) {
        return new Mail(p.getEmail(), subject, text, html);
    }

    String entryUrl(String token) {
        String base = props.entryBaseUrl().endsWith("/") ? props.entryBaseUrl().substring(0, props.entryBaseUrl().length() - 1)
                : props.entryBaseUrl();
        // 프론트 라우트는 /entry (App.tsx). 토큰은 쿼리로 넘긴다
        return base + "/entry?invite=" + token;
    }
}
