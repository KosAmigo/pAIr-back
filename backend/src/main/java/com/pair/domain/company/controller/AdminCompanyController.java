package com.pair.domain.company.controller;

import com.pair.domain.company.dto.CompanyDetailResponse;
import com.pair.domain.company.dto.CompanyMemberResponse;
import com.pair.domain.company.dto.CompanyResponse;
import com.pair.domain.company.dto.InquiryResponse;
import com.pair.domain.company.dto.ManagerReplacementResponse;
import com.pair.domain.company.dto.ReplaceManagerRequest;
import com.pair.domain.company.service.CompanyInquiryService;
import com.pair.domain.company.service.CompanyMemberService;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.bind.annotation.RestController;

import com.pair.global.security.CurrentUser;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/admin")
@Validated
public class AdminCompanyController {

    private final CompanyInquiryService inquiries;
    private final CompanyMemberService members;

    public AdminCompanyController(CompanyInquiryService inquiries, CompanyMemberService members) {
        this.inquiries = inquiries;
        this.members = members;
    }

    @GetMapping("/company-inquiries")
    public List<InquiryResponse> inquiries(CurrentUser user) {
        requireAdmin(user);
        return inquiries.list();
    }

    @PostMapping("/company-inquiries/{inquiryId}/approve")
    public CompanyResponse approve(CurrentUser user, @PathVariable Long inquiryId) {
        requireAdmin(user);
        return inquiries.approve(inquiryId, user.userId());
    }

    @PostMapping(value = "/company-inquiries/{inquiryId}/members", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public InquiryResponse replaceMembers(CurrentUser user, @PathVariable Long inquiryId,
            @org.springframework.web.bind.annotation.RequestParam("file") MultipartFile file) throws java.io.IOException {
        requireAdmin(user);
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("회원 명단 파일(엑셀 또는 CSV)을 올려 주세요.");
        String filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
        if (!filename.endsWith(".csv") && !filename.endsWith(".xlsx")) throw new IllegalArgumentException("회원 명단은 엑셀(.xlsx) 또는 CSV 파일만 올릴 수 있습니다.");
        return inquiries.replaceRoster(inquiryId, file.getBytes());
    }

    @PostMapping("/company-inquiries/{inquiryId}/reject")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reject(CurrentUser user, @PathVariable Long inquiryId) {
        requireAdmin(user);
        inquiries.reject(inquiryId, user.userId());
    }

    @GetMapping("/companies")
    public List<CompanyResponse> companies(CurrentUser user) {
        requireAdmin(user);
        return members.allCompanies();
    }

    @GetMapping("/companies/{companyId}")
    public CompanyDetailResponse company(CurrentUser user, @PathVariable Long companyId) {
        requireAdmin(user);
        return members.company(companyId);
    }

    @PostMapping("/companies/{companyId}/manager")
    public ManagerReplacementResponse replaceManager(CurrentUser user, @PathVariable long companyId,
            @Valid @RequestBody ReplaceManagerRequest request) {
        return members.replaceManager(companyId, user, request);
    }

    @PostMapping("/members/{userId}/onboarding/reissue")
    public CompanyMemberResponse reissueOnboarding(CurrentUser user, @PathVariable long userId) {
        return CompanyMemberResponse.from(members.reissue(userId, user));
    }

    private static void requireAdmin(CurrentUser user) {
        if (!user.isAdmin()) throw new com.pair.global.error.ForbiddenException("관리자 권한이 필요합니다.");
    }
}
