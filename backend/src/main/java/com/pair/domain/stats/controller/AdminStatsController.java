package com.pair.domain.stats.controller;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.pair.domain.stats.dto.AdminStatsResponse;
import com.pair.domain.stats.service.AdminStatsService;
import com.pair.global.error.ForbiddenException;
import com.pair.global.security.CurrentUser;

/** 관리자 통계. 기간은 한국 시간 날짜(YYYY-MM-DD), 종료일 포함. 없으면 최근 30일 */
@RestController
@RequestMapping("/api/admin")
public class AdminStatsController {

    private final AdminStatsService stats;

    public AdminStatsController(AdminStatsService stats) {
        this.stats = stats;
    }

    @GetMapping("/stats")
    public AdminStatsResponse stats(CurrentUser user,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        if (!user.isAdmin()) throw new ForbiddenException("관리자 권한이 필요합니다.");
        return stats.stats(from, to);
    }
}
