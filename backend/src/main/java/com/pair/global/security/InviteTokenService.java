package com.pair.global.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 입장 링크용 초대 토큰. 추측할 수 없도록 32바이트 난수를 URL 안전한 base64 로 (43자).
 * DB 에는 {@link #hash(String)} 만 저장한다. DB 가 유출돼도 입장 링크를 만들 수 없다.
 * 토큰 자체가 256비트 난수라 솔트·느린 해시 없이 SHA-256 한 번으로 충분하다.
 */
public final class InviteTokenService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 32;

    private InviteTokenService() {}

    public static String next() {
        byte[] b = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    /** SHA-256 hex 64자 (exam_participants.invite_token_hash) */
    public static String hash(String token) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 을 쓸 수 없습니다.", e);
        }
    }

    /** 형식이 아예 다른 값은 DB 를 조회하기 전에 거른다 */
    public static boolean looksValid(String token) {
        return token != null && token.matches("[A-Za-z0-9_-]{43}");
    }
}
