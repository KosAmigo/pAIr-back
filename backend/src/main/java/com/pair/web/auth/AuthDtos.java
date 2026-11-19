package com.pair.web.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 인증 API 요청과 응답에 쓰는 검증 규칙 포함 데이터 타입. */
public final class AuthDtos {

    private AuthDtos() {}

    public record Credentials(
            @NotBlank @Email @Size(max = 255) String email,
            @NotBlank @Size(max = 72) String password) {}

    public record SignupRequest(
            @NotBlank @Size(max = 50) String name,
            @NotBlank @Email @Size(max = 255) String email,
            @NotBlank @Size(min = 8, max = 72) String password) {}

    public record UserView(long id, String email, String name, CurrentUser.Role role, Long companyId) {
        static UserView from(AuthAccount account) {
            return new UserView(account.getId(), account.getEmail(), account.getName(), account.getRole(), account.getCompanyId());
        }
    }

    public record AuthResponse(String token, UserView user) {}
}
