package com.pair.domain.company.dto;

import com.pair.domain.auth.entity.AuthAccount;
import com.pair.global.security.CurrentUser;

public record CompanyMemberResponse(long id, String name, String email, CurrentUser.Role role, String status,
        boolean emailVerified, boolean passwordChangeRequired, String onboardingMailStatus) {
    public static CompanyMemberResponse from(AuthAccount account) {
        return new CompanyMemberResponse(account.getId(), account.getName(), account.getEmail(), account.getRole(),
                account.getMemberStatus(), account.isEmailVerified(), account.isPasswordChangeRequired(),
                account.getOnboardingMailStatus());
    }
}
