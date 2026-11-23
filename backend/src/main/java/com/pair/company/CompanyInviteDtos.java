package com.pair.company;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class CompanyInviteDtos {
    private CompanyInviteDtos() {}

    public record InviteRequest(@NotBlank @Email @Size(max = 255) String email) {}
    public record AcceptRequest(@NotBlank @Size(max = 43) String token,
            @NotBlank @Size(max = 50) String name,
            @NotBlank @Size(min = 8, max = 72) String password,
            @NotBlank @jakarta.validation.constraints.Pattern(regexp = "[0-9]{6}") String code) {}
    public record InviteInfo(String email, String role, String companyName, java.time.Instant expiresAt) {}
    public record InviteSent(String message) {}
}
