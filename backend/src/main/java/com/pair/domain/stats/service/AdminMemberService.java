package com.pair.domain.stats.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pair.domain.attempt.repository.AttemptReportRepository;
import com.pair.domain.attempt.repository.AttemptRepository;
import com.pair.domain.auth.entity.AuthAccount;
import com.pair.domain.auth.repository.AuthAccountRepository;
import com.pair.domain.company.repository.CompanyRepository;
import com.pair.domain.credit.repository.CreditLedgerRepository;
import com.pair.domain.stats.dto.AdminMemberPageResponse;
import com.pair.domain.stats.dto.AdminMemberResponse;
import com.pair.global.security.CurrentUser.Role;

/**
 * 관리자 회원 목록: 탈퇴하지 않은 계정을 역할·검색어로 거르고, 한 페이지의 회원마다 자가진단 응시 수·평균 점수·크레딧 잔액을 붙인다.
 * 회원별 값은 페이지의 회원 ID 로 한 번씩만 모아 읽는다 (회원마다 따로 조회하지 않는다).
 */
@Service
public class AdminMemberService {

    static final int DEFAULT_SIZE = 20;
    static final int MAX_SIZE = 100;

    /** 화면의 역할 필터 → 실제 역할 */
    public enum RoleFilter {
        ALL(List.of(Role.USER, Role.MANAGER, Role.PROCTOR, Role.ADMIN)),
        USER(List.of(Role.USER)),
        COMPANY(List.of(Role.MANAGER, Role.PROCTOR)),
        ADMIN(List.of(Role.ADMIN));

        final List<Role> roles;

        RoleFilter(List<Role> roles) {
            this.roles = roles;
        }
    }

    private final AuthAccountRepository accounts;
    private final AttemptRepository attempts;
    private final AttemptReportRepository reports;
    private final CreditLedgerRepository ledger;
    private final CompanyRepository companies;
    private final ObjectMapper json;

    public AdminMemberService(AuthAccountRepository accounts, AttemptRepository attempts, AttemptReportRepository reports,
            CreditLedgerRepository ledger, CompanyRepository companies, ObjectMapper json) {
        this.accounts = accounts;
        this.attempts = attempts;
        this.reports = reports;
        this.ledger = ledger;
        this.companies = companies;
        this.json = json;
    }

    @Transactional(readOnly = true)
    public AdminMemberPageResponse members(RoleFilter role, String query, int page, int size) {
        if (page < 0) throw new IllegalArgumentException("page 는 0 이상이어야 합니다.");
        if (size < 1 || size > MAX_SIZE) throw new IllegalArgumentException("size 는 1~" + MAX_SIZE + " 사이여야 합니다.");
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        String pattern = "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        Page<AuthAccount> found = accounts.searchMembers((role == null ? RoleFilter.ALL : role).roles, pattern, PageRequest.of(page, size));

        List<Long> ids = found.getContent().stream().map(AuthAccount::getId).toList();
        Map<Long, AttemptRepository.UserAttemptSummary> summaries = new HashMap<>();
        Map<Long, List<Double>> scores = new HashMap<>();
        Map<Long, Integer> balances = new HashMap<>();
        Map<Long, String> companyNames = new HashMap<>();
        if (!ids.isEmpty()) {
            attempts.summarizeSelfByUsers(ids).forEach(s -> summaries.put(s.getUserId(), s));
            for (AttemptReportRepository.UserReport r : reports.findSelfReportsOfUsers(ids)) {
                Double score = AdminStatsService.scoreOf(json, r.getReport());
                if (score != null) scores.computeIfAbsent(r.getUserId(), k -> new ArrayList<>()).add(score);
            }
            ledger.findBalancesOfUsers(ids).forEach(b -> balances.put(b.getUserId(), b.getBalance()));
            List<Long> companyIds = found.getContent().stream().map(AuthAccount::getCompanyId).filter(id -> id != null).distinct().toList();
            companies.findAllById(companyIds).forEach(c -> companyNames.put(c.getId(), c.getName()));
        }

        List<AdminMemberResponse> rows = found.getContent().stream().map(a -> {
            AttemptRepository.UserAttemptSummary s = summaries.get(a.getId());
            boolean usesCredits = a.getRole() == Role.USER;
            return new AdminMemberResponse(a.getId(), a.getName(), a.getEmail(), a.getRole().name(), a.getMemberStatus(),
                    a.getCompanyId() == null ? null : companyNames.get(a.getCompanyId()), a.getCreatedAt(),
                    s == null ? 0 : s.getStarted(), s == null ? 0 : s.getSubmitted(), average(scores.get(a.getId())),
                    usesCredits ? balances.getOrDefault(a.getId(), 0) : null, s == null ? null : s.getLastStartedAt());
        }).toList();
        return new AdminMemberPageResponse(rows, found.getNumber(), found.getSize(), found.getTotalElements(), found.getTotalPages());
    }

    private static Double average(List<Double> values) {
        if (values == null || values.isEmpty()) return null;
        double sum = 0;
        for (double v : values) sum += v;
        return Math.round(sum / values.size() * 10) / 10.0;
    }
}
