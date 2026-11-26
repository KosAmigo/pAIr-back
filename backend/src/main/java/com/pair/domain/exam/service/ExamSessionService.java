package com.pair.domain.exam.service;

import com.pair.domain.exam.dto.InvitesCreatedDto;
import com.pair.domain.exam.dto.SessionNoticeDto;
import com.pair.global.security.InviteTokenService;
import com.pair.infra.storage.SnapshotStorage;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pair.global.config.ExamProperties;
import com.pair.domain.exam.entity.ExamParticipant;
import com.pair.domain.exam.entity.ExamSession;
import com.pair.domain.exam.repository.ExamParticipantRepository;
import com.pair.domain.exam.repository.ExamSessionRepository;
import com.pair.domain.exam.dto.AddParticipantsResponse;
import com.pair.domain.exam.dto.ParticipantInputDto;
import com.pair.domain.exam.dto.ParticipantResponse;
import com.pair.domain.exam.dto.RejectedParticipantDto;
import com.pair.domain.exam.dto.SessionDetailResponse;
import com.pair.domain.exam.dto.ExamSessionRequest;
import com.pair.domain.exam.dto.SessionSummaryResponse;
import com.pair.domain.task.service.TaskCatalogService;
import com.pair.domain.task.service.TaskConsoleService;
import com.pair.domain.auth.repository.AuthAccountRepository;
import com.pair.global.error.ForbiddenException;
import com.pair.global.error.UnauthorizedException;

/** 기업 콘솔: 회차 생성·수정·취소와 대상자 등록. */
@Service
public class ExamSessionService {

    /** CSV 는 Bean Validation 을 거치지 않으므로 같은 기준으로 한 번 더 본다 */
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]{1,64}@[^@\\s]+\\.[^@\\s]+$");
    private static final int MAX_NAME = 50;
    private static final int MAX_EMAIL = 255;

    private final ExamSessionRepository sessions;
    private final ExamParticipantRepository participants;
    private final TaskCatalogService tasks;
    private final ApplicationEventPublisher events;
    private final SnapshotStorage storage;
    private final TaskConsoleService publications;
    private final com.pair.domain.exam.repository.ProctorEventRepository proctorEvents;
    private final com.pair.domain.attempt.repository.AttemptRepository attemptRecords;
    private final ParticipantProgressService progress;
    private final ExamProperties props;
    private final Clock clock;
    private final AuthAccountRepository accounts;

    public ExamSessionService(ExamSessionRepository sessions, ExamParticipantRepository participants, TaskCatalogService tasks,
            ApplicationEventPublisher events, SnapshotStorage storage, TaskConsoleService publications,
            com.pair.domain.exam.repository.ProctorEventRepository proctorEvents, com.pair.domain.attempt.repository.AttemptRepository attemptRecords,
            ParticipantProgressService progress, ExamProperties props, Clock clock, AuthAccountRepository accounts) {
        this.sessions = sessions;
        this.participants = participants;
        this.tasks = tasks;
        this.attemptRecords = attemptRecords;
        this.progress = progress;
        this.events = events;
        this.storage = storage;
        this.publications = publications;
        this.proctorEvents = proctorEvents;
        this.props = props;
        this.clock = clock;
        this.accounts = accounts;
    }

    /** createdBy: 만든 회원 (같은 회사 MANAGER 또는 관리자) */
    @Transactional
    public SessionSummaryResponse create(ExamSessionRequest req, Long createdBy) {
        return create(req, createdBy, false);
    }

    /** admin: 관리자만 서버 추론 감독(serverVision)을 켤 수 있다 */
    @Transactional
    public SessionSummaryResponse create(ExamSessionRequest req, Long createdBy, boolean admin) {
        if (Boolean.TRUE.equals(req.serverVision()) && !admin) throw new ForbiddenException("서버 추론 감독 설정은 관리자만 바꿀 수 있습니다.");
        Instant now = clock.instant();
        if (req.companyId() == null) throw new IllegalArgumentException("회사를 알 수 없습니다.");
        validateSchedule(req, now);
        // 탈퇴의 회차 존재 확인과 회차 생성을 같은 회사 회원 잠금으로 직렬화한다.
        var companyAccounts = accounts.lockByCompanyId(req.companyId());
        if (companyAccounts.stream().anyMatch(a -> a.getId().equals(createdBy) && !a.isActive())) {
            throw new UnauthorizedException("로그인이 필요합니다.");
        }
        List<Long> taskIds = liveTaskIds(req.taskIds()); // 없는 과제면 404, 공개한 실전 과제만
        ExamSession s;
        try {
            s = sessions.save(new ExamSession(req.companyId(), req.title().trim(), req.startsAt(), req.endsAt(),
                    req.proctoring(), taskIds, createdBy, now));
            if (Boolean.TRUE.equals(req.serverVision())) s.changeServerVision(true);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // companies 외래 키 위반: 관리자가 없는 회사 번호를 넣은 경우
            throw new IllegalArgumentException("없는 회사 번호입니다: " + req.companyId());
        }
        return summary(s, null, now);
    }

    private List<Long> liveTaskIds(List<String> codes) {
        return publications.requireOpenLive(codes).stream().map(com.pair.domain.task.entity.Task::getId).toList();
    }

    @Transactional(readOnly = true)
    public List<SessionSummaryResponse> list(Long companyId) {
        Instant now = clock.instant();
        return summaries(sessions.findByCompanyIdOrderByStartsAtDesc(companyId), now);
    }

    private List<SessionSummaryResponse> summaries(List<ExamSession> list, Instant now) {
        if (list.isEmpty()) return List.of();
        List<Long> ids = list.stream().map(ExamSession::getId).toList();
        Map<Long, ExamParticipantRepository.SessionCounts> counts = participants.countBySessionIds(ids).stream()
                .collect(Collectors.toMap(ExamParticipantRepository.SessionCounts::getSessionId, Function.identity()));
        Map<Long, Long> submitted = attemptRecords.countSubmittedBySessionIds(ids).stream()
                .collect(Collectors.toMap(com.pair.domain.attempt.repository.AttemptRepository.SessionSubmitted::getSessionId,
                        com.pair.domain.attempt.repository.AttemptRepository.SessionSubmitted::getSubmitted));
        Map<Long, Long> open = proctorEvents.countUnreviewedBySessionIds(ids).stream()
                .collect(Collectors.toMap(com.pair.domain.exam.repository.ProctorEventRepository.SessionOpenCount::getSessionId,
                        com.pair.domain.exam.repository.ProctorEventRepository.SessionOpenCount::getOpen));
        return list.stream().map(s -> summary(s, counts.get(s.getId()), submitted.getOrDefault(s.getId(), 0L),
                open.getOrDefault(s.getId(), 0L), now)).toList();
    }

    /** 관리자: 모든 회사의 회차 */
    @Transactional(readOnly = true)
    public List<SessionSummaryResponse> listAll() {
        return summaries(sessions.findAllByOrderByStartsAtDesc(), clock.instant());
    }

    @Transactional(readOnly = true)
    public SessionDetailResponse detail(Long sessionId) {
        Instant now = clock.instant();
        ExamSession s = require(sessionId);
        List<ExamParticipant> ps = participants.findBySessionIdOrderByIdAsc(sessionId);
        Map<Long, ParticipantProgressService.Progress> states = progress.of(ps);
        long entered = ps.stream().filter(p -> p.getEnteredAt() != null).count();
        long submitted = states.values().stream().filter(ParticipantProgressService.Progress::finished).count();
        SessionSummaryResponse sum = new SessionSummaryResponse(s.getId(), s.getCompanyId(), s.getTitle(), s.getStartsAt(), s.getEndsAt(), s.isProctoring(),
                s.phase(now), tasks.codesInOrder(s.getTaskIds()), ps.size(), entered, submitted, s.getCanceledAt(), 0, s.getCreatedAt(), s.isServerVision());
        return new SessionDetailResponse(sum, now, s.getClosedAt(), ps.stream().map(p -> ParticipantResponse.from(p, states.get(p.getId()), now)).toList());
    }

    /** 시작 전에만 고칠 수 있다 (진행 중에 시각을 바꾸면 응시자마다 조건이 달라진다). 바뀌면 대상자에게 안내 메일 */
    @Transactional
    public SessionSummaryResponse update(Long sessionId, ExamSessionRequest req) {
        return update(sessionId, req, false);
    }

    /** admin: 관리자만 서버 추론 감독(serverVision) 값을 바꿀 수 있다. 시작 전 회차만 고칠 수 있으므로 시험 중에는 바뀌지 않는다 */
    @Transactional
    public SessionSummaryResponse update(Long sessionId, ExamSessionRequest req, boolean admin) {
        Instant now = clock.instant();
        ExamSession s = requireUpcoming(sessionId, now, "고칠");
        if (req.serverVision() != null && req.serverVision() != s.isServerVision()) {
            if (!admin) throw new ForbiddenException("서버 추론 감독 설정은 관리자만 바꿀 수 있습니다.");
            s.changeServerVision(req.serverVision());
        }
        if (!s.getCompanyId().equals(req.companyId())) throw new IllegalArgumentException("다른 회사의 회차로 옮길 수 없습니다.");
        validateSchedule(req, now);
        List<Long> taskIds = liveTaskIds(req.taskIds());
        boolean changed = !s.getTitle().equals(req.title().trim()) || !s.getStartsAt().equals(req.startsAt())
                || !s.getEndsAt().equals(req.endsAt()) || !s.getTaskIds().equals(taskIds);
        boolean rescheduled = !s.getStartsAt().equals(req.startsAt()) || !s.getEndsAt().equals(req.endsAt());
        s.update(req.title().trim(), req.startsAt(), req.endsAt(), req.proctoring(), taskIds);
        if (rescheduled) reinviteAll(s, now);
        else if (changed) notifyParticipants(s, SessionNoticeDto.Kind.CHANGED);
        return summary(s, null, now);
    }

    /**
     * 일시가 바뀌면 초대 메일을 바뀐 시각으로 다시 보낸다 (새 입장 링크, 이전 링크는 못 쓴다).
     * 이미 입장한 대상자는 링크를 바꾸면 곤란하므로 변경 안내 메일만 보낸다.
     */
    private void reinviteAll(ExamSession s, Instant now) {
        List<ExamParticipant> all = participants.findBySessionIdOrderByIdAsc(s.getId());
        List<InvitesCreatedDto.Invite> invites = new ArrayList<>();
        List<Long> entered = new ArrayList<>();
        for (ExamParticipant p : all) {
            if (p.getEnteredAt() != null) {
                entered.add(p.getId());
                continue;
            }
            String token = InviteTokenService.next();
            p.reissueInvite(InviteTokenService.hash(token), now);
            invites.add(new InvitesCreatedDto.Invite(p.getId(), token));
        }
        if (!invites.isEmpty()) events.publishEvent(new InvitesCreatedDto(invites));
        if (!entered.isEmpty()) events.publishEvent(new SessionNoticeDto(s.getId(), SessionNoticeDto.Kind.CHANGED, entered));
    }

    /** 시작 전에만 취소한다. 회차는 지우지 않고 canceled_at 을 남기고, 대상자에게 취소 안내 메일을 보낸다 */
    @Transactional
    public SessionSummaryResponse cancel(Long sessionId) {
        Instant now = clock.instant();
        ExamSession s = requireUpcoming(sessionId, now, "취소할");
        s.cancel(now);
        notifyParticipants(s, SessionNoticeDto.Kind.CANCELED);
        return summary(s, null, now);
    }

    /** 대상자 추가. 형식이 틀리거나 이미 있는 이메일은 건너뛰고 이유를 돌려준다. 초대 메일은 커밋 뒤에 보낸다 */
    @Transactional
    public AddParticipantsResponse addParticipants(Long sessionId, List<ParticipantInputDto> inputs) {
        Instant now = clock.instant();
        ExamSession s = requireOpenForParticipants(sessionId, now);
        Checked c = check(sessionId, inputs);

        List<ExamParticipant> toSave = new ArrayList<>();
        List<String> tokens = new ArrayList<>();
        for (ParticipantInputDto in : c.valid()) {
            String token = InviteTokenService.next();
            tokens.add(token);
            toSave.add(new ExamParticipant(s, in.email(), in.name(), InviteTokenService.hash(token), now));
        }
        List<ExamParticipant> saved = participants.saveAll(toSave);
        if (!saved.isEmpty()) {
            List<InvitesCreatedDto.Invite> invites = new ArrayList<>();
            for (int i = 0; i < saved.size(); i++) invites.add(new InvitesCreatedDto.Invite(saved.get(i).getId(), tokens.get(i)));
            events.publishEvent(new InvitesCreatedDto(invites));
        }
        return new AddParticipantsResponse(saved.size(), c.rejected());
    }

    /**
     * 대상자 CSV 업로드. dryRun 이면 저장하지 않고 들어갈 인원과 거절 사유만 미리 보여 준다.
     * 저장할 때는 원본을 파일 저장소에 남기고(participants_csv_key), 행 번호는 CSV 기준으로 바꿔 돌려준다.
     */
    @Transactional
    public AddParticipantsResponse importCsv(Long sessionId, byte[] csv, boolean dryRun) {
        Instant now = clock.instant();
        ExamSession s = requireOpenForParticipants(sessionId, now);
        List<ParticipantCsvService.CsvRow> rows = ParticipantCsvService.parse(csv, props.maxParticipantsPerUpload());
        if (rows.isEmpty()) throw new IllegalArgumentException("CSV 에 대상자가 없습니다.");
        List<ParticipantInputDto> inputs = rows.stream().map(row -> new ParticipantInputDto(row.name(), row.email())).toList();

        AddParticipantsResponse r;
        if (dryRun) {
            Checked c = check(sessionId, inputs);
            r = new AddParticipantsResponse(c.valid().size(), c.rejected());
        } else {
            // 원본을 먼저 올린다. 올리기가 실패하면 등록도 하지 않는다 (원본 없는 등록이 남지 않게)
            String key = SnapshotStorage.csvKeyOf(s.getId(), now.toEpochMilli());
            storage.put(key, csv);
            s.recordParticipantsCsv(key);
            r = addParticipants(sessionId, inputs);
        }
        List<RejectedParticipantDto> mapped = r.rejected().stream()
                .map(x -> new RejectedParticipantDto(rows.get(x.line() - 1).line(), x.name(), x.email(), x.reason())).toList();
        return new AddParticipantsResponse(r.added(), mapped);
    }

    /** 명단에서 빼기. 시작 전 회차에서, 아직 입장하지 않은 대상자만 (입장·동의·감독 기록이 생긴 뒤에는 지우지 않는다) */
    @Transactional
    public void removeParticipant(Long sessionId, Long participantId) {
        ExamParticipant p = participants.findById(participantId)
                .filter(x -> x.getSession().getId().equals(sessionId))
                .orElseThrow(() -> new NoSuchElementException("없는 대상자입니다."));
        if (p.getSession().phase(clock.instant()) != ExamSession.Phase.UPCOMING) {
            throw new IllegalStateException("시작 전 회차에서만 명단을 고칠 수 있습니다.");
        }
        if (p.getEnteredAt() != null) throw new IllegalStateException("이미 입장한 대상자는 뺄 수 없습니다.");
        participants.delete(p);
    }

    /** 초대 메일 다시 보내기. 새 토큰을 만들어 이전 링크는 쓸 수 없게 한다 */
    @Transactional
    public void resendInvite(Long sessionId, Long participantId) {
        ExamParticipant p = participants.findById(participantId)
                .filter(x -> x.getSession().getId().equals(sessionId))
                .orElseThrow(() -> new NoSuchElementException("없는 대상자입니다."));
        Instant now = clock.instant();
        ExamSession.Phase phase = p.getSession().phase(now);
        if (phase == ExamSession.Phase.ENDED || phase == ExamSession.Phase.CANCELED) {
            throw new IllegalStateException("끝났거나 취소한 회차의 초대 메일은 보낼 수 없습니다.");
        }
        String token = InviteTokenService.next();
        p.reissueInvite(InviteTokenService.hash(token), now);
        events.publishEvent(new InvitesCreatedDto(List.of(new InvitesCreatedDto.Invite(p.getId(), token))));
    }

    /** 형식·목록 안 중복·이미 등록된 이메일을 거른다. 저장은 하지 않는다 */
    private Checked check(Long sessionId, List<ParticipantInputDto> inputs) {
        if (inputs.size() > props.maxParticipantsPerUpload()) {
            throw new IllegalArgumentException("한 번에 " + props.maxParticipantsPerUpload() + "명까지 추가할 수 있습니다.");
        }
        List<RejectedParticipantDto> rejected = new ArrayList<>();
        List<ParticipantInputDto> candidates = new ArrayList<>();
        List<Integer> lines = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < inputs.size(); i++) {
            ParticipantInputDto in = inputs.get(i);
            int line = i + 1;
            String name = in.name() == null ? "" : in.name().trim();
            String email = in.email() == null ? "" : in.email().trim().toLowerCase();
            String reason = validate(name, email);
            if (reason == null && !seen.add(email)) reason = "목록 안에서 이메일이 중복됩니다.";
            if (reason != null) {
                rejected.add(new RejectedParticipantDto(line, name, email, reason));
                continue;
            }
            candidates.add(new ParticipantInputDto(name, email));
            lines.add(line);
        }

        Set<String> existing = candidates.isEmpty() ? Set.of()
                : new HashSet<>(participants.findExistingEmails(sessionId, candidates.stream().map(ParticipantInputDto::email).toList()));
        List<ParticipantInputDto> valid = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            ParticipantInputDto in = candidates.get(i);
            if (existing.contains(in.email())) rejected.add(new RejectedParticipantDto(lines.get(i), in.name(), in.email(), "이미 등록된 대상자입니다."));
            else valid.add(in);
        }
        rejected.sort((a, b) -> Integer.compare(a.line(), b.line()));
        return new Checked(valid, rejected);
    }

    private record Checked(List<ParticipantInputDto> valid, List<RejectedParticipantDto> rejected) {}

    private void notifyParticipants(ExamSession s, SessionNoticeDto.Kind kind) {
        List<Long> ids = participants.findIdsBySessionId(s.getId());
        if (!ids.isEmpty()) events.publishEvent(new SessionNoticeDto(s.getId(), kind, ids));
    }

    private ExamSession requireOpenForParticipants(Long sessionId, Instant now) {
        ExamSession s = require(sessionId);
        switch (s.phase(now)) {
            case ENDED -> throw new IllegalStateException("끝난 회차에는 대상자를 추가할 수 없습니다.");
            case CANCELED -> throw new IllegalStateException("취소한 회차에는 대상자를 추가할 수 없습니다.");
            case UPCOMING, LIVE -> { }
        }
        return s;
    }

    ExamSession require(Long sessionId) {
        return sessions.findById(sessionId).orElseThrow(() -> new NoSuchElementException("없는 회차입니다: " + sessionId));
    }

    private ExamSession requireUpcoming(Long sessionId, Instant now, String verb) {
        ExamSession s = require(sessionId);
        if (s.phase(now) == ExamSession.Phase.CANCELED) throw new IllegalStateException("취소한 회차는 " + verb + " 수 없습니다.");
        if (s.phase(now) != ExamSession.Phase.UPCOMING) throw new IllegalStateException("시작한 회차는 " + verb + " 수 없습니다.");
        return s;
    }

    private static void validateSchedule(ExamSessionRequest req, Instant now) {
        if (!req.endsAt().isAfter(req.startsAt())) throw new IllegalArgumentException("종료 시각은 시작 시각보다 뒤여야 합니다.");
        if (!req.startsAt().isAfter(now)) throw new IllegalArgumentException("시작 시각은 지금보다 뒤여야 합니다.");
    }

    private static String validate(String name, String email) {
        if (name.isEmpty()) return "이름이 비어 있습니다.";
        if (name.length() > MAX_NAME) return "이름은 " + MAX_NAME + "자까지입니다.";
        if (email.isEmpty()) return "이메일이 비어 있습니다.";
        if (email.length() > MAX_EMAIL || !EMAIL.matcher(email).matches()) return "이메일 형식이 아닙니다.";
        return null;
    }

    private SessionSummaryResponse summary(ExamSession s, ExamParticipantRepository.SessionCounts c, Instant now) {
        return summary(s, c, 0, 0, now);
    }

    private SessionSummaryResponse summary(ExamSession s, ExamParticipantRepository.SessionCounts c, long submitted, long open, Instant now) {
        return new SessionSummaryResponse(s.getId(), s.getCompanyId(), s.getTitle(), s.getStartsAt(), s.getEndsAt(), s.isProctoring(), s.phase(now),
                tasks.codesInOrder(s.getTaskIds()), c == null ? 0 : c.getTotal(), c == null ? 0 : c.getEntered(), submitted,
                s.getCanceledAt(), open, s.getCreatedAt(), s.isServerVision());
    }
}
