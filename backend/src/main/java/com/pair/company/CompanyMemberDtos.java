package com.pair.company;

import java.time.Instant;
import java.util.List;

import com.pair.web.auth.AuthAccount;
import com.pair.web.auth.CurrentUser;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class CompanyMemberDtos {
    private CompanyMemberDtos() {}

    public record MemberView(long id, String name, String email, CurrentUser.Role role, String status,
            boolean emailVerified, boolean passwordChangeRequired, String onboardingMailStatus) {
        static MemberView from(AuthAccount account) {
            return new MemberView(account.getId(), account.getName(), account.getEmail(), account.getRole(),
                    account.getMemberStatus(), account.isEmailVerified(), account.isPasswordChangeRequired(),
                    account.getOnboardingMailStatus());
        }
    }

    public record DelegateRequest(long proctorUserId) {}
    public record ReplaceManagerRequest(@NotBlank @Size(max = 50) String name,
            @NotBlank @Email @Size(max = 255) String email) {}
    public record CompanyDetail(CompanyInquiryDtos.CompanyView company, List<MemberView> members) {}
    public record MemberList(long companyId, List<MemberView> members) {}
    public record ManagerReplacement(long companyId, long managerUserId, String message) {}
}
