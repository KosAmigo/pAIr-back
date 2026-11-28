package com.pair.domain.stats.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.pair.domain.stats.dto.AdminMemberPageResponse;
import com.pair.domain.stats.service.AdminMemberService;
import com.pair.global.error.ForbiddenException;
import com.pair.global.security.CurrentUser;

/** 관리자 회원 목록. role = ALL | USER | COMPANY | ADMIN (기본 ALL), q = 이름·이메일 일부, page 는 0부터 */
@RestController
@RequestMapping("/api/admin")
public class AdminMemberController {

    private final AdminMemberService members;

    public AdminMemberController(AdminMemberService members) {
        this.members = members;
    }

    @GetMapping("/members")
    public AdminMemberPageResponse members(CurrentUser user,
            @RequestParam(defaultValue = "ALL") AdminMemberService.RoleFilter role,
            @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        if (!user.isAdmin()) throw new ForbiddenException("관리자 권한이 필요합니다.");
        return members.members(role, q, page, size);
    }
}
