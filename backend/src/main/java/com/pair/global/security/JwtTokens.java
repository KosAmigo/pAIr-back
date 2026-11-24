package com.pair.global.security;

import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.stereotype.Component;

import com.nimbusds.jose.jwk.source.ImmutableSecret;

@Component
public class JwtTokens {
    public static final Duration ACCESS_LIFETIME = Duration.ofMinutes(15);
    public static final Duration REFRESH_LIFETIME = Duration.ofDays(7);
    private static final String ISSUER = "pair-backend";

    private final JwtEncoder encoder;
    private final JwtDecoder decoder;

    public JwtTokens(@Value("${pair.auth.jwt.secret:}") String encodedSecret) {
        byte[] key;
        try {
            key = Base64.getDecoder().decode(encodedSecret);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("PAIR_JWT_SECRET must be a Base64-encoded random key of at least 32 bytes", e);
        }
        if (key.length < 32) {
            throw new IllegalStateException("PAIR_JWT_SECRET must be a Base64-encoded random key of at least 32 bytes");
        }
        SecretKey secret = new SecretKeySpec(key, "HmacSHA256");
        this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(secret));
        NimbusJwtDecoder jwtDecoder = NimbusJwtDecoder.withSecretKey(secret).macAlgorithm(MacAlgorithm.HS256).build();
        jwtDecoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(Duration.ZERO), JwtValidators.createDefaultWithIssuer(ISSUER)));
        this.decoder = jwtDecoder;
    }

    /** 액세스 토큰은 로그인 세션(sessionId)에 묶인다. 세션이 폐기되면 만료 전이라도 쓸 수 없다. */
    public String access(long userId, long tokenVersion, String sessionId, Instant now) {
        return encode(userId, "access", sessionId, tokenVersion, now, ACCESS_LIFETIME);
    }

    public String refresh(long userId, String sessionId, Instant now) {
        return encode(userId, "refresh", sessionId, null, now, REFRESH_LIFETIME);
    }

    private String encode(long userId, String type, String sessionId, Long tokenVersion, Instant now, Duration lifetime) {
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(ISSUER).subject(Long.toString(userId)).issuedAt(now).expiresAt(now.plus(lifetime))
                .id(UUID.randomUUID().toString()).claim("token_type", type);
        if (sessionId != null) claims.claim("sid", sessionId);
        if (tokenVersion != null) claims.claim("ver", tokenVersion);
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims.build()))
                .getTokenValue();
    }

    public Optional<VerifiedToken> verify(String token, String expectedType) {
        if (token == null || token.length() > 4096) return Optional.empty();
        try {
            Jwt jwt = decoder.decode(token);
            if (!expectedType.equals(jwt.getClaimAsString("token_type")) || jwt.getExpiresAt() == null
                    || jwt.getIssuedAt() == null || jwt.getId() == null) return Optional.empty();
            if (jwt.getSubject() == null) return Optional.empty();
            long userId = Long.parseLong(jwt.getSubject());
            String sessionId = jwt.getClaimAsString("sid");
            Object versionClaim = jwt.getClaim("ver");
            if (versionClaim != null && !(versionClaim instanceof Integer) && !(versionClaim instanceof Long)) {
                return Optional.empty();
            }
            // 버전 도입 전 토큰은 초기 버전의 계정에서만 계속 사용할 수 있다.
            long tokenVersion = versionClaim == null ? 0 : ((Number) versionClaim).longValue();
            // 두 토큰 모두 로그인 세션 ID가 있어야 한다. 세션 없이 발급된 옛 액세스 토큰은 거부되어 한 번 재발급된다
            if (userId <= 0 || !InviteTokenService.looksValid(sessionId)) {
                return Optional.empty();
            }
            return Optional.of(new VerifiedToken(userId, sessionId, jwt.getExpiresAt(), tokenVersion));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public record VerifiedToken(long userId, String sessionId, Instant expiresAt, long tokenVersion) {}
}
