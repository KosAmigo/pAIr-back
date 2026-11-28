package com.pair.domain.exam.service;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Service;

import com.pair.global.config.VisionProperties;

/**
 * 추론 서버 → 백엔드 이벤트 콜백의 서명 검사 (계약 §3-4·§3-5).
 * 서명 = hex( HMAC-SHA256(콜백 비밀, timestamp + "." + 원본 본문 바이트) ), timestamp 는 epoch 초이고 서버 시각 ±60초 안이어야 한다.
 * 비밀은 키 번호(k1, k2)로 고르고 둘이 동시에 유효하다(무중단 교체). 비밀·서명은 로그에 남기지 않는다.
 */
@Service
public class VisionSignatureService {

    public static final long TOLERANCE_SECONDS = 60;

    public enum Result { OK, MISSING, UNKNOWN_KEY_ID, STALE_TIMESTAMP, BAD_SIGNATURE }

    private final VisionProperties props;
    private final Clock clock;

    public VisionSignatureService(VisionProperties props, Clock clock) {
        this.props = props;
        this.clock = clock;
    }

    public Result verify(String keyId, String timestamp, String signature, byte[] body) {
        if (blank(keyId) || blank(timestamp) || blank(signature)) return Result.MISSING;
        String secret = switch (keyId) {
            case "k1" -> props.eventHmacSecret();
            case "k2" -> props.eventHmacSecretNext();
            default -> "";
        };
        if (secret.isEmpty()) return Result.UNKNOWN_KEY_ID;
        long ts;
        try {
            ts = Long.parseLong(timestamp.trim());
        } catch (NumberFormatException e) {
            return Result.STALE_TIMESTAMP;
        }
        if (Math.abs(clock.instant().getEpochSecond() - ts) > TOLERANCE_SECONDS) return Result.STALE_TIMESTAMP;
        byte[] expected = sign(secret, timestamp.trim(), body).getBytes(StandardCharsets.US_ASCII);
        byte[] given = signature.trim().toLowerCase().getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, given) ? Result.OK : Result.BAD_SIGNATURE; // 시간이 일정하게 비교한다
    }

    /** 서명 만들기 (추론 서버가 하는 일을 테스트가 흉내 낼 때도 쓴다) */
    public static String sign(String secret, String timestamp, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update(timestamp.getBytes(StandardCharsets.US_ASCII));
            mac.update((byte) '.');
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("서명을 만들 수 없습니다.", e);
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
