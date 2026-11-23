package com.pair.web.auth;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class WithdrawnEmailHasher {
    private static final String ALGORITHM = "HmacSHA256";
    private final SecretKeySpec key;

    public WithdrawnEmailHasher(@Value("${pair.auth.withdrawn-email.secret:}") String encodedSecret) {
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(encodedSecret);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("PAIR_WITHDRAWN_EMAIL_SECRET must be a Base64-encoded random key of at least 32 bytes", e);
        }
        if (decoded.length < 32) {
            throw new IllegalStateException("PAIR_WITHDRAWN_EMAIL_SECRET must be a Base64-encoded random key of at least 32 bytes");
        }
        this.key = new SecretKeySpec(decoded, ALGORITHM);
    }

    String hash(String normalizedEmail) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return "withdrawn:" + HexFormat.of().formatHex(mac.doFinal(normalizedEmail.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("탈퇴 이메일 해시 생성에 실패했습니다.", e);
        }
    }
}
