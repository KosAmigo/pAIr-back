package com.pair.exam.service;

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

import com.pair.exam.ExamProperties;
import com.pair.exam.domain.ExamParticipant;
import com.pair.exam.domain.ExamSession;
import com.pair.exam.repository.ExamParticipantRepository;
import com.pair.exam.repository.ExamSessionRepository;
import com.pair.exam.web.ExamDtos.AddParticipantsResult;
import com.pair.exam.web.ExamDtos.ParticipantInput;
import com.pair.exam.web.ExamDtos.ParticipantView;
import com.pair.exam.web.ExamDtos.Rejected;
import com.pair.exam.web.ExamDtos.SessionDetail;
import com.pair.exam.web.ExamDtos.SessionRequest;
import com.pair.exam.web.ExamDtos.SessionSummary;
import com.pair.task.TaskRepository;
import com.pair.taskconsole.TaskConsoleService;

/** 기업 콘솔: 회차 생성·수정·취소와 대상자 등록. */
@Service
public class ExamSessionService {

    /** CSV 는 Bean Validation 을 거치지 않으므로 같은 기준으로 한 번 더 본다 */
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]{1,64}@[^@\\s]+\\.[^@\\s]+$");
    private static final int MAX_NAME = 50;
    private static final int MAX_EMAIL = 255;

    private final ExamSessionRepository sessions;
    private final ExamParticipantRepository participants;
    private final TaskRepository tasks;
    private final ApplicationEventPublisher events;
    private final SnapshotStorage storage;
    private final TaskConsoleService publications;
    private final com.pair.exam.repository.ProctorEventRepository proctorEvents;
    private final ExamProperties props;
    private final Clock clock;

    public ExamSessionService(ExamSessionRepository sessions, ExamParticipantRepository participants, TaskRepository tasks,
            ApplicationEventPublisher events, SnapshotStorage storage, TaskConsoleService publications,
            com.pair.exam.repository.ProctorEventRepository proctorEvents, ExamProperties props, Clock clock) {
        this.sessions = sessions;
        this.participants = participants;
        this.tasks = tasks;
        this.events = events;
        this.storage = storage;
        this.publications = publications;
        this.proctorEvents = proctorEvents;
        this.props = props;
        this.clock = clock;
    }

    @Transactional
    public SessionSummary create(SessionRequest req) {
        Instant now = clock.instant();
        if (req.companyId() == null) throw new IllegalArgumentException("회사를 알 수 없습니다.");
        validateSchedule(req, now);
        req.taskIds().forEach(tasks::get); // 없는 과제면 404
        publications.requireOpen(req.taskIds()); // 검수해 공개한 과제만
        ExamSession s = sessions.save(new ExamSession(req.companyId(), req.title().trim(), req.startsAt(), req.endsAt(),
                req.proctoring(), req.taskIds(), now));
        return summary(s, null, now);
    }

    @Transactional(readOnly = true)
    public List<SessionSummary> list(Long companyId) {
        Instant now = clock.instant();
        return summaries(sessions.findByCompanyIdOrderByStartsAtDesc(companyId), now);
    }

    private List<SessionSummary> summaries(List<ExamSession> list, Instant now) {
        if (list.isEmpty()) return List.of();
        List<Long> ids = list.stream().map(ExamSession::getId).toList();
        Map<Long, ExamParticipantRepository.SessionCounts> counts = participants.countBySessionIds(ids).stream()
                .collect(Collectors.toMap(ExamParticipantRepository.SessionCounts::getSessionId, Function.identity()));
        Map<Long, Long> open = proctorEvents.countUnreviewedBySessionIds(ids).stream()
                .collect(Collectors.toMap(com.pair.exam.repository.ProctorEventRepository.SessionOpenCount::getSessionId,
                        com.pair.exam.repository.ProctorEventRepository.SessionOpenCount::getOpen));
        return list.stream().map(s -> summary(s, counts.get(s.getId()), open.getOrDefault(s.getId(), 0L), now)).toList();
    }

    /** 관리자: 모든 회사의 회차 */
    @Transactional(readOnly = true)
    public List<SessionSummary> listAll() {
        return summaries(sessions.findAllByOrderByStartsAtDesc(), clock.instant());
    }

    @Transactional(readOnly = true)
    public SessionDetail detail(Long sessionId) {
        Instant now = clock.instant();
        ExamSession s = require(sessionId);
        List<ExamParticipant> ps = participants.findBySessionIdOrderByIdAsc(sessionId);
        long entered = ps.stream().filter(p -> p.getEnteredAt() != null).count();
        long submitted = ps.stream().filter(p -> p.getSubmittedAt() != null).count();
        SessionSummary sum = new SessionSummary(s.getId(), s.getCompanyId(), s.getTitle(), s.getStartsAt(), s.getEndsAt(), s.isProctoring(),
                s.phase(now), s.getTaskIds(), ps.size(), entered, submitted, s.getCanceledAt(), 0, s.getCreatedAt());
        return new SessionDetail(sum, now, s.getClosedAt(), ps.stream().map(ParticipantView::from).toList());
    }

    /** 시작 전에만 고칠 수 있다 (진행 중에 시각을 바꾸면 응시자마다 조건이 달라진다). 바뀌면 대상자에게 안내 메일 */
    @Transactional
    public SessionSummary update(Long sessionId, SessionRequest req) {
        Instant now = clock.instant();
        ExamSession s = requireUpcoming(sessionId, now, "고칠");
        if (!s.getCompanyId().equals(req.companyId())) throw new IllegalArgumentException("다른 회사의 회차로 옮길 수 없습니다.");
        validateSchedule(req, now);
        req.taskIds().forEach(tasks::get);
        publications.requireOpen(req.taskIds());
        boolean changed = !s.getTitle().equals(req.title().trim()) || !s.getStartsAt().equals(req.startsAt())
                || !s.getEndsAt().equals(req.endsAt()) || !s.getTaskIds().equals(req.taskIds());
        s.update(req.title().trim(), req.startsAt(), req.endsAt(), req.proctoring(), req.taskIds());
        if (changed) notifyParticipants(s, SessionNotice.Kind.CHANGED);
        return summary(s, null, now);
    }

    /** 시작 전에만 취소한다. 회차는 지우지 않고 canceled_at 을 남기고, 대상자에게 취소 안내 메일을 보낸다 */
    @Transactional
    public SessionSummary cancel(Long sessionId) {
        Instant now = clock.instant();
        ExamSession s = requireUpcoming(sessionId, now, "취소할");
        s.cancel(now);
        notifyParticipants(s, SessionNotice.Kind.CANCELED);
        return summary(s, null, now);
    }

    /** 대상자 추가. 형식이 틀리거나 이미 있는 이메일은 건너뛰고 이유를 돌려준다. 초대 메일은 커밋 뒤에 보낸다 */
    @Transactional
    public AddParticipantsResult addParticipants(Long sessionId, List<ParticipantInput> inputs) {
        Instant now = clock.instant();
        ExamSession s = requireOpenForParticipants(sessionId, now);
        Checked c = check(sessionId, inputs);

        List<ExamParticipant> toSave = new ArrayList<>();
        List<String> tokens = new ArrayList<>();
        for (ParticipantInput in : c.valid()) {
            String token = InviteTokens.next();
            tokens.add(token);
            toSave.add(new ExamParticipant(s, in.email(), in.name(), InviteTokens.hash(token), now));
        }
        List<ExamParticipant> saved = participants.saveAll(toSave);
        if (!saved.isEmpty()) {
            List<InvitesCreated.Invite> invites = new ArrayList<>();
            for (int i = 0; i < saved.size(); i++) invites.add(new InvitesCreated.Invite(saved.get(i).getId(), tokens.get(i)));
            events.publishEvent(new InvitesCreated(invites));
        }
        return new AddParticipantsResult(saved.size(), c.rejected());
    }

    /**
     * 대상자 CSV 업로드. dryRun 이면 저장하지 않고 들어갈 인원과 거절 사유만 미리 보여 준다.
     * 저장할 때는 원본을 파일 저장소에 남기고(participants_csv_key), 행 번호는 CSV 기준으로 바꿔 돌려준다.
     */
    @Transactional
    public AddParticipantsResult importCsv(Long sessionId, byte[] csv, boolean dryRun) {
        Instant now = clock.instant();
        ExamSession s = requireOpenForParticipants(sessionId, now);
        List<ParticipantCsvParser.CsvRow> rows = ParticipantCsvParser.parse(csv, props.maxParticipantsPerUpload());
        if (rows.isEmpty()) throw new IllegalArgumentException("CSV 에 대상자가 없습니다.");
        List<ParticipantInput> inputs = rows.stream().map(row -> new ParticipantInput(row.name(), row.email())).toList();

        AddParticipantsResult r;
        if (dryRun) {
            Checked c = check(sessionId, inputs);
            r = new AddParticipantsResult(c.valid().size(), c.rejected());
        } else {
            // 원본을 먼저 올린다. 올리기가 실패하면 등록도 하지 않는다 (원본 없는 등록이 남지 않게)
            String key = SnapshotStorage.csvKeyOf(s.getId(), now.toEpochMilli());
            storage.put(key, csv);
            s.recordParticipantsCsv(key);
            r = addParticipants(sessionId, inputs);
        }
        List<Rejected> mapped = r.rejected().stream()
                .map(x -> new Rejected(rows.get(x.line() - 1).line(), x.name(), x.email(), x.reason())).toList();
        return new AddParticipantsResult(r.added(), mapped);
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
        String token = InviteTokens.next();
        p.reissueInvite(InviteTokens.hash(token), now);
        events.publishEvent(new InvitesCreated(List.of(new InvitesCreated.Invite(p.getId(), token))));
    }

    /** 형식·목록 안 중복·이미 등록된 이메일을 거른다. 저장은 하지 않는다 */
    private Checked check(Long sessionId, List<ParticipantInput> inputs) {
        if (inputs.size() > props.maxParticipantsPerUpload()) {
            throw new IllegalArgumentException("한 번에 " + props.maxParticipantsPerUpload() + "명까지 추가할 수 있습니다.");
        }
        List<Rejected> rejected = new ArrayList<>();
        List<ParticipantInput> candidates = new ArrayList<>();
        List<Integer> lines = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < inputs.size(); i++) {
            ParticipantInput in = inputs.get(i);
            int line = i + 1;
            String name = in.name() == null ? "" : in.name().trim();
            String email = in.email() == null ? "" : in.email().trim().toLowerCase();
            String reason = validate(name, email);
            if (reason == null && !seen.add(email)) reason = "목록 안에서 이메일이 중복됩니다.";
            if (reason != null) {
                rejected.add(new Rejected(line, name, email, reason));
                continue;
            }
            candidates.add(new ParticipantInput(name, email));
            lines.add(line);
        }

        Set<String> existing = candidates.isEmpty() ? Set.of()
                : new HashSet<>(participants.findExistingEmails(sessionId, candidates.stream().map(ParticipantInput::email).toList()));
        List<ParticipantInput> valid = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            ParticipantInput in = candidates.get(i);
            if (existing.contains(in.email())) rejected.add(new Rejected(lines.get(i), in.name(), in.email(), "이미 등록된 대상자입니다."));
            else valid.add(in);
        }
        rejected.sort((a, b) -> Integer.compare(a.line(), b.line()));
        return new Checked(valid, rejected);
    }

    private record Checked(List<ParticipantInput> valid, List<Rejected> rejected) {}

    private void notifyParticipants(ExamSession s, SessionNotice.Kind kind) {
        List<Long> ids = participants.findIdsBySessionId(s.getId());
        if (!ids.isEmpty()) events.publishEvent(new SessionNotice(s.getId(), kind, ids));
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

    private static void validateSchedule(SessionRequest req, Instant now) {
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

    private static SessionSummary summary(ExamSession s, ExamParticipantRepository.SessionCounts c, Instant now) {
        return summary(s, c, 0, now);
    }

    private static SessionSummary summary(ExamSession s, ExamParticipantRepository.SessionCounts c, long open, Instant now) {
        return new SessionSummary(s.getId(), s.getCompanyId(), s.getTitle(), s.getStartsAt(), s.getEndsAt(), s.isProctoring(), s.phase(now),
                s.getTaskIds(), c == null ? 0 : c.getTotal(), c == null ? 0 : c.getEntered(), c == null ? 0 : c.getSubmitted(),
                s.getCanceledAt(), open, s.getCreatedAt());
    }
}
