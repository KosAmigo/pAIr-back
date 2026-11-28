package com.pair.domain.stats.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pair.domain.attempt.entity.Attempt;
import com.pair.domain.attempt.repository.AttemptReportRepository;
import com.pair.domain.attempt.repository.AttemptRepository;
import com.pair.domain.attempt.repository.ChatTurnRepository;
import com.pair.domain.auth.repository.AuthAccountRepository;
import com.pair.domain.company.repository.CompanyRepository;
import com.pair.domain.credit.repository.CreditPurchaseRepository;
import com.pair.domain.exam.dto.SessionAiCostResponse.ModelLine;
import com.pair.domain.exam.dto.SessionAiCostResponse.Part;
import com.pair.domain.exam.repository.ExamParticipantRepository;
import com.pair.domain.exam.repository.ExamSessionRepository;
import com.pair.domain.exam.service.SessionAiCostService;
import com.pair.domain.scoring.repository.JudgeCallRepository;
import com.pair.domain.stats.dto.AdminStatsResponse;
import com.pair.domain.stats.dto.AdminStatsResponse.Band;
import com.pair.domain.taskgen.repository.TaskGenCallRepository;
import com.pair.domain.taskgen.repository.TaskGenJobRepository;
import com.pair.global.security.CurrentUser;

/**
 * 관리자 통계: 회원, 자가진단·실전 응시와 평균 점수, AI 토큰·예상 비용, 크레딧 결제를 기간별로 모은다.
 * 모든 값은 저장된 기록을 group by 로 한 번씩 세어 만든다 (응시마다 따로 조회하지 않는다).
 * 점수는 리포트의 총점을 100점 만점으로 바꿔 평균한다(지금 루브릭은 만점이 100점이라 총점 그대로).
 */
@Service
public class AdminStatsService {

    private static final Logger log = LoggerFactory.getLogger(AdminStatsService.class);
    static final ZoneId KST = ZoneId.of("Asia/Seoul");
    static final int DEFAULT_DAYS = 30;
    static final int MAX_DAYS = 366;
    /** 점수 구간 하한 (포함). 마지막 구간은 100점까지 */
    private static final int[] BAND_FLOORS = {0, 40, 60, 80};

    private final AuthAccountRepository accounts;
    private final CompanyRepository companies;
    private final AttemptRepository attempts;
    private final AttemptReportRepository reports;
    private final ChatTurnRepository chatTurns;
    private final JudgeCallRepository judgeCalls;
    private final TaskGenCallRepository taskGenCalls;
    private final TaskGenJobRepository taskGenJobs;
    private final CreditPurchaseRepository purchases;
    private final ExamSessionRepository sessions;
    private final ExamParticipantRepository participants;
    private final SessionAiCostService pricing;
    private final ObjectMapper json;
    private final Clock clock;

    public AdminStatsService(AuthAccountRepository accounts, CompanyRepository companies, AttemptRepository attempts,
            AttemptReportRepository reports, ChatTurnRepository chatTurns, JudgeCallRepository judgeCalls,
            TaskGenCallRepository taskGenCalls, TaskGenJobRepository taskGenJobs, CreditPurchaseRepository purchases,
            ExamSessionRepository sessions, ExamParticipantRepository participants, SessionAiCostService pricing,
            ObjectMapper json, Clock clock) {
        this.accounts = accounts;
        this.companies = companies;
        this.attempts = attempts;
        this.reports = reports;
        this.chatTurns = chatTurns;
        this.judgeCalls = judgeCalls;
        this.taskGenCalls = taskGenCalls;
        this.taskGenJobs = taskGenJobs;
        this.purchases = purchases;
        this.sessions = sessions;
        this.participants = participants;
        this.pricing = pricing;
        this.json = json;
        this.clock = clock;
    }

    /** from·to 는 한국 시간 날짜(to 포함). 둘 다 없으면 오늘까지 최근 30일, 하나만 있으면 나머지를 30일 간격으로 채운다 */
    @Transactional(readOnly = true)
    public AdminStatsResponse stats(LocalDate fromDate, LocalDate toDate) {
        LocalDate to = toDate != null ? toDate
                : fromDate != null ? fromDate.plusDays(DEFAULT_DAYS - 1) : LocalDate.now(clock.withZone(KST));
        LocalDate from = fromDate != null ? fromDate : to.minusDays(DEFAULT_DAYS - 1);
        if (from.isAfter(to)) throw new IllegalArgumentException("시작일이 종료일보다 늦습니다.");
        if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_DAYS) {
            throw new IllegalArgumentException("기간은 " + MAX_DAYS + "일 이내로 골라 주세요.");
        }
        Instant start = from.atStartOfDay(KST).toInstant();
        Instant end = to.plusDays(1).atStartOfDay(KST).toInstant();

        return new AdminStatsResponse(from, to, members(start, end), selfDiagnosis(start, end), live(start, end),
                ai(start, end), credits(start, end));
    }

    private AdminStatsResponse.Members members(Instant start, Instant end) {
        long users = 0, companyMembers = 0;
        for (AuthAccountRepository.RoleCount c : accounts.countActiveByRole()) {
            if (c.getRole() == CurrentUser.Role.USER) users += c.getCount();
            else if (c.getRole() == CurrentUser.Role.MANAGER || c.getRole() == CurrentUser.Role.PROCTOR) companyMembers += c.getCount();
        }
        return new AdminStatsResponse.Members(users, companyMembers, companies.countByDeletedAtIsNull(),
                accounts.countNewUsersBetween(start, end), accounts.countWithdrawnBetween(start, end));
    }

    private AdminStatsResponse.Exams selfDiagnosis(Instant start, Instant end) {
        Counts c = counts(Attempt.Mode.SELF, start, end);
        Scores s = scores(Attempt.Mode.SELF, start, end);
        return new AdminStatsResponse.Exams(c.started, c.submitted, s.scored(), s.average(), s.bands());
    }

    private AdminStatsResponse.LiveExams live(Instant start, Instant end) {
        Counts c = counts(Attempt.Mode.LIVE, start, end);
        Scores s = scores(Attempt.Mode.LIVE, start, end);
        return new AdminStatsResponse.LiveExams(sessions.countStartingBetween(start, end),
                participants.countInSessionsStartingBetween(start, end), c.started, c.submitted, s.scored(), s.average(), s.bands());
    }

    private record Counts(long started, long submitted) {}

    private Counts counts(Attempt.Mode mode, Instant start, Instant end) {
        return attempts.countByModeBetween(start, end).stream().filter(r -> r.getMode() == mode).findFirst()
                .map(r -> new Counts(r.getStarted(), r.getSubmitted())).orElse(new Counts(0, 0));
    }

    record Scores(long scored, Double average, List<Band> bands) {}

    private Scores scores(Attempt.Mode mode, Instant start, Instant end) {
        List<Double> values = new ArrayList<>();
        for (AttemptReportRepository.ModeReport r : reports.findReportsBetween(start, end)) {
            if (r.getMode() != mode) continue;
            Double score = scoreOf(r.getReport());
            if (score != null) values.add(score);
        }
        return summarize(values);
    }

    /** 리포트 총점을 100점 만점으로. 채점이 끝나지 않았거나(버전 2 미만, total 없음) 읽을 수 없으면 null */
    Double scoreOf(String report) {
        if (report == null || report.isBlank()) return null;
        try {
            JsonNode node = json.readTree(report);
            if (node.path("version").asInt() < 2 || !node.has("total")) return null;
            int max = node.path("max").asInt(100);
            if (max <= 0) return null;
            return node.path("total").asDouble() * 100.0 / max;
        } catch (Exception e) {
            log.warn("통계: 채점 리포트를 읽지 못함", e);
            return null;
        }
    }

    static Scores summarize(List<Double> values) {
        long[] counts = new long[BAND_FLOORS.length];
        double sum = 0;
        for (double v : values) {
            sum += v;
            int i = BAND_FLOORS.length - 1;
            while (i > 0 && v < BAND_FLOORS[i]) i--;
            counts[i]++;
        }
        List<Band> bands = new ArrayList<>();
        for (int i = 0; i < BAND_FLOORS.length; i++) {
            int hi = i + 1 < BAND_FLOORS.length ? BAND_FLOORS[i + 1] - 1 : 100;
            bands.add(new Band(BAND_FLOORS[i] + "-" + hi, counts[i]));
        }
        Double average = values.isEmpty() ? null : Math.round(sum / values.size() * 10) / 10.0;
        return new Scores(values.size(), average, List.copyOf(bands));
    }

    private AdminStatsResponse.Ai ai(Instant start, Instant end) {
        TreeSet<String> unpriced = new TreeSet<>();
        Map<Attempt.Mode, List<ModelLine>> chat = Map.of(Attempt.Mode.SELF, new ArrayList<>(), Attempt.Mode.LIVE, new ArrayList<>());
        for (ChatTurnRepository.ModeModelUsage u : chatTurns.sumByModeAndModelBetween(start, end)) {
            chat.get(u.getMode()).add(pricing.priced(u.getModel(), u.getCalls(), u.getInputTokens(), u.getOutputTokens(), 0, unpriced));
        }
        List<ModelLine> scoringLines = new ArrayList<>();
        for (JudgeCallRepository.ModelUsage u : judgeCalls.sumBetween(start, end)) {
            scoringLines.add(pricing.priced(u.getModel(), u.getCalls(), u.getInputTokens(), u.getOutputTokens(), u.getCachedTokens(), unpriced));
        }
        Part selfChat = SessionAiCostService.sum(chat.get(Attempt.Mode.SELF));
        Part liveChat = SessionAiCostService.sum(chat.get(Attempt.Mode.LIVE));
        Part scoring = SessionAiCostService.sum(scoringLines);

        TaskGenCallRepository.TokenSum calls = taskGenCalls.sumBetween(start, end);
        TaskGenJobRepository.JobCost jobs = taskGenJobs.sumCostBetween(start, end);
        double taskGenUsd = SessionAiCostService.round(jobs.getUsd() == null ? 0 : jobs.getUsd());
        AdminStatsResponse.TaskGen taskGen = new AdminStatsResponse.TaskGen(jobs.getJobs(), calls.getCalls(),
                calls.getInputTokens(), calls.getOutputTokens(), calls.getCachedTokens(), taskGenUsd);

        double total = SessionAiCostService.round(selfChat.usd() + liveChat.usd() + scoring.usd() + taskGenUsd);
        return new AdminStatsResponse.Ai(total, selfChat, liveChat, scoring, taskGen, List.copyOf(unpriced));
    }

    private AdminStatsResponse.Credits credits(Instant start, Instant end) {
        CreditPurchaseRepository.AmountSum paid = purchases.sumPaidBetween(start, end);
        CreditPurchaseRepository.AmountSum refunded = purchases.sumRefundedBetween(start, end);
        return new AdminStatsResponse.Credits(paid.getCount(), paid.getAmountKrw(), refunded.getCount(), refunded.getAmountKrw());
    }
}
