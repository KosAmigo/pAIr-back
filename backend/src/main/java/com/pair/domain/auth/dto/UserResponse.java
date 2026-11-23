package com.pair.domain.auth.dto;

import com.pair.domain.auth.entity.AuthAccount;
import com.pair.global.security.CurrentUser;

public record UserResponse(long id, String email, String name, CurrentUser.Role role, Long companyId,
        boolean emailVerified, boolean passwordChangeRequired) {
    public static UserResponse from(AuthAccount account) {
        return new UserResponse(account.getId(), account.getEmail(), account.getName(), account.getRole(), account.getCompanyId(),
                account.isEmailVerified(), account.isPasswordChangeRequired());
    }
}
