package com.pair.domain.company.service;

import com.pair.domain.company.dto.CompanyResponse;
import com.pair.domain.company.dto.InquiryMemberDto;
import com.pair.domain.company.dto.InquiryMemberInputDto;
import com.pair.domain.company.dto.InquiryResponse;
import com.pair.domain.company.entity.Company;
import com.pair.domain.company.entity.CompanyInquiry;
import com.pair.domain.company.entity.CompanyInquiryMember;
import com.pair.domain.company.repository.CompanyInquiryMemberRepository;
import com.pair.domain.company.repository.CompanyInquiryRepository;
import com.pair.domain.company.repository.CompanyRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pair.domain.auth.repository.AuthAccountRepository;
import com.pair.global.error.ConflictException;
import com.pair.global.security.CurrentUser;

@Service
public class CompanyInquiryService {

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]{1,64}@[^@\\s]+\\.[^@\\s]+$");
    private static final int MAX_MEMBERS = 1000;

    private final CompanyInquiryRepository inquiries;
    private final CompanyInquiryMemberRepository members;
    private final CompanyRepository companies;
    private final AuthAccountRepository accounts;
    private final CorporateAccountService corporateAccounts;

    public CompanyInquiryService(CompanyInquiryRepository inquiries, CompanyInquiryMemberRepository members,
            CompanyRepository companies, AuthAccountRepository accounts, CorporateAccountService corporateAccounts) {
        this.inquiries = inquiries;
        this.members = members;
        this.companies = companies;
        this.accounts = accounts;
        this.corporateAccounts = corporateAccounts;
    }

    @Transactional
    public InquiryResponse submit(String companyName, String businessNo, String contactName,
            String contactEmail, String message, byte[] csv) {
        if (csv == null || csv.length == 0) throw new IllegalArgumentException("회원 명단 CSV 파일을 올려 주세요.");
        String company = required(companyName, 100, "회사명");
        String business = required(businessNo, 20, "사업자 번호");
        String contact = required(contactName, 50, "담당자 이름");
        String email = normalizeEmail(contactEmail);
        if (!EMAIL.matcher(email).matches()) throw new IllegalArgumentException("담당자 이메일 형식이 올바르지 않습니다.");
        List<InquiryMemberInputDto> roster = parseCsv(csv);
        validateRoster(roster);

        CompanyInquiry inquiry = inquiries.saveAndFlush(new CompanyInquiry(company, business, contact, email,
                message == null || message.isBlank() ? null : message.trim(), Instant.now()));
        List<CompanyInquiryMember> records = roster.stream()
                .map(m -> new CompanyInquiryMember(inquiry.getId(), m.name(), normalizeEmail(m.email()), m.role()))
                .toList();
        members.saveAll(records);
        return view(inquiry, roster.stream().map(m -> new InquiryMemberDto(m.name(), normalizeEmail(m.email()), m.role())).toList());
    }

    @Transactional(readOnly = true)
    public List<InquiryResponse> list() {
        List<CompanyInquiry> all = inquiries.findAllByOrderByCreatedAtDesc();
        if (all.isEmpty()) return List.of();
        Map<Long, List<InquiryMemberDto>> roster = members.findByInquiryIdIn(
                all.stream().map(CompanyInquiry::getId).toList()).stream()
                .collect(java.util.stream.Collectors.groupingBy(CompanyInquiryMember::getInquiryId,
                        java.util.stream.Collectors.mapping(m -> new InquiryMemberDto(
                                m.getName(), m.getEmail(), m.getRole()), java.util.stream.Collectors.toList())));
        return all.stream().map(inquiry -> view(inquiry, roster.getOrDefault(inquiry.getId(), List.of()))).toList();
    }

    @Transactional
    public CompanyResponse approve(Long inquiryId, Long adminId) {
        CompanyInquiry inquiry = inquiries.lockById(inquiryId)
                .orElseThrow(() -> new java.util.NoSuchElementException("기업 문의를 찾을 수 없습니다."));
        if (inquiry.getStatus() != CompanyInquiry.Status.PENDING) throw new IllegalStateException("이미 처리된 기업 문의입니다.");
        List<CompanyInquiryMember> roster = members.findByInquiryIdOrderById(inquiryId);
        validateRoster(roster.stream().map(m -> new InquiryMemberInputDto(m.getName(), m.getEmail(), m.getRole())).toList());
        for (CompanyInquiryMember member : roster) {
            if (accounts.existsByEmailIgnoreCase(member.getEmail())) {
                throw new ConflictException("이미 가입된 이메일입니다. 명단을 수정한 뒤 다시 승인해 주세요: " + member.getEmail());
            }
        }

        Company company = companies.saveAndFlush(new Company(inquiry.getCompanyName(), inquiry.getBusinessNo(),
                inquiry.getContactName(), Instant.now()));
        inquiry.approve(company.getId(), adminId, Instant.now());
        for (CompanyInquiryMember member : roster) {
            corporateAccounts.create(member.getEmail(), member.getName(), member.getRole(), company.getId());
        }
        return CompanyResponse.from(company);
    }

    @Transactional
    public void reject(Long inquiryId, Long adminId) {
        CompanyInquiry inquiry = inquiries.lockById(inquiryId)
                .orElseThrow(() -> new java.util.NoSuchElementException("기업 문의를 찾을 수 없습니다."));
        inquiry.reject(adminId, Instant.now());
    }

    @Transactional
    public InquiryResponse replaceRoster(Long inquiryId, byte[] csv) {
        CompanyInquiry inquiry = inquiries.lockById(inquiryId)
                .orElseThrow(() -> new java.util.NoSuchElementException("기업 문의를 찾을 수 없습니다."));
        if (inquiry.getStatus() != CompanyInquiry.Status.PENDING) throw new IllegalStateException("처리 전 문의만 명단을 바꿀 수 있습니다.");
        List<InquiryMemberInputDto> roster = parseCsv(csv);
        validateRoster(roster);
        for (InquiryMemberInputDto member : roster) {
            if (accounts.existsByEmailIgnoreCase(normalizeEmail(member.email()))) {
                throw new ConflictException("이미 가입된 이메일입니다. 명단을 수정해 주세요: " + member.email());
            }
        }
        members.deleteByInquiryId(inquiryId);
        members.saveAll(roster.stream().map(m -> new CompanyInquiryMember(inquiryId, m.name().trim(),
                normalizeEmail(m.email()), m.role())).toList());
        return view(inquiry, roster.stream().map(m -> new InquiryMemberDto(m.name().trim(),
                normalizeEmail(m.email()), m.role())).toList());
    }

    private static InquiryResponse view(CompanyInquiry inquiry, List<InquiryMemberDto> roster) {
        return new InquiryResponse(inquiry.getId(), inquiry.getCompanyName(), inquiry.getBusinessNo(),
                inquiry.getContactName(), inquiry.getContactEmail(), inquiry.getMessage(), inquiry.getStatus(),
                inquiry.getCompanyId(), inquiry.getCreatedAt(), roster);
    }

    private static void validateRoster(List<InquiryMemberInputDto> roster) {
        if (roster.isEmpty() || roster.size() > MAX_MEMBERS) {
            throw new IllegalArgumentException("회원 명단은 1명 이상 %d명 이하로 입력해 주세요.".formatted(MAX_MEMBERS));
        }
        Set<String> emails = new HashSet<>();
        long managers = 0;
        for (InquiryMemberInputDto member : roster) {
            required(member.name(), 50, "회원명");
            String email = normalizeEmail(member.email());
            if (!EMAIL.matcher(email).matches()) throw new IllegalArgumentException("이메일 형식이 올바르지 않습니다: " + email);
            if (!emails.add(email)) throw new ConflictException("CSV에 이메일이 중복되어 있습니다: " + email);
            if (member.role() != CurrentUser.Role.MANAGER && member.role() != CurrentUser.Role.PROCTOR) {
                throw new IllegalArgumentException("역할은 MANAGER 또는 PROCTOR여야 합니다.");
            }
            if (member.role() == CurrentUser.Role.MANAGER) managers++;
        }
        if (managers != 1) throw new IllegalArgumentException("회원 명단에는 MANAGER가 정확히 1명 있어야 합니다.");
    }

    private static List<InquiryMemberInputDto> parseCsv(byte[] content) {
        String csv = new String(content, StandardCharsets.UTF_8);
        if (!csv.isEmpty() && csv.charAt(0) == '\uFEFF') csv = csv.substring(1);
        List<List<String>> rows = parseRecords(csv);
        if (rows.isEmpty() || !rows.get(0).stream().map(String::trim).toList()
                .equals(List.of("회원명", "회원이메일", "역할"))) {
            throw new IllegalArgumentException("CSV 첫 줄은 회원명,회원이메일,역할 순서여야 합니다.");
        }
        List<InquiryMemberInputDto> result = new ArrayList<>();
        for (int i = 1; i < rows.size(); i++) {
            List<String> row = rows.get(i);
            if (row.size() == 1 && row.get(0).isBlank()) continue;
            if (row.size() != 3) throw new IllegalArgumentException("CSV %d번째 행은 3개 열이어야 합니다.".formatted(i + 1));
            CurrentUser.Role role;
            try {
                role = CurrentUser.Role.valueOf(row.get(2).trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("CSV %d번째 역할은 MANAGER 또는 PROCTOR여야 합니다.".formatted(i + 1));
            }
            result.add(new InquiryMemberInputDto(row.get(0).trim(), row.get(1).trim(), role));
        }
        return result;
    }

    private static List<List<String>> parseRecords(String input) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < input.length() && input.charAt(i + 1) == '"') {
                    field.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    field.append(c);
                }
            } else if (c == '"' && field.isEmpty()) {
                quoted = true;
            } else if (c == ',') {
                row.add(field.toString());
                field.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < input.length() && input.charAt(i + 1) == '\n') i++;
                row.add(field.toString());
                field.setLength(0);
                if (!(row.size() == 1 && row.get(0).isBlank())) rows.add(List.copyOf(row));
                row.clear();
            } else {
                field.append(c);
            }
        }
        if (quoted) throw new IllegalArgumentException("CSV 따옴표가 닫히지 않았습니다.");
        if (!row.isEmpty() || !field.isEmpty()) {
            row.add(field.toString());
            rows.add(List.copyOf(row));
        }
        return rows;
    }

    private static String required(String value, int maxLength, String label) {
        if (value == null || value.isBlank() || value.trim().length() > maxLength) {
            throw new IllegalArgumentException("%s은(는) 1~%d자로 입력해 주세요.".formatted(label, maxLength));
        }
        return value.trim();
    }

    private static String normalizeEmail(String email) {
        return required(email, 255, "이메일").toLowerCase(Locale.ROOT);
    }
}
