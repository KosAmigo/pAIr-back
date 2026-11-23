package com.pair.domain.company.dto;

public record InviteInfoResponse(String email, String role, String companyName, java.time.Instant expiresAt) {}
