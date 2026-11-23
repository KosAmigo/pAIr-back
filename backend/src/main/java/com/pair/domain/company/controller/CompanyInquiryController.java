package com.pair.domain.company.controller;

import com.pair.domain.company.dto.InquiryResponse;
import com.pair.domain.company.service.CompanyInquiryService;
import java.nio.charset.StandardCharsets;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/company-inquiries")
public class CompanyInquiryController {

    static final String CSV_TEMPLATE = "\uFEFF회원명,회원이메일,역할\r\n홍길동,manager@example.com,MANAGER\r\n";

    private final CompanyInquiryService inquiries;

    public CompanyInquiryController(CompanyInquiryService inquiries) {
        this.inquiries = inquiries;
    }

    @GetMapping("/members-template.csv")
    public ResponseEntity<byte[]> memberTemplate() {
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename("company-members-template.csv", StandardCharsets.UTF_8).build();
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(CSV_TEMPLATE.getBytes(StandardCharsets.UTF_8));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<InquiryResponse> submit(
            @RequestParam String companyName,
            @RequestParam String businessNo,
            @RequestParam String contactName,
            @RequestParam String contactEmail,
            @RequestParam(required = false) String message,
            @RequestParam("members") MultipartFile membersFile) {
        if (membersFile == null || membersFile.isEmpty()) throw new IllegalArgumentException("회원 명단 CSV 파일을 올려 주세요.");
        String filename = membersFile.getOriginalFilename() == null ? "" : membersFile.getOriginalFilename().toLowerCase();
        if (!filename.endsWith(".csv")) throw new IllegalArgumentException("회원 명단은 CSV 파일만 올릴 수 있습니다.");
        try {
            return ResponseEntity.status(201).body(inquiries.submit(companyName, businessNo, contactName, contactEmail,
                    message, membersFile.getBytes()));
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("회원 명단 파일을 읽지 못했습니다.");
        }
    }
}
