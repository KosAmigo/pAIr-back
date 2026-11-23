package com.pair.domain.auth.dto;

public record AuthResponse(String token, String refreshToken, long expiresIn, UserResponse user) {}
