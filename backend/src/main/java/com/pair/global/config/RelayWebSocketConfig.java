package com.pair.global.config;

import com.pair.domain.exam.service.ProctorRelayService;
import com.pair.domain.exam.service.VisionRelayService;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * 휴대폰·응시자 PC 중계 연결. 쿠키 같은 자동 인증이 없고 입장 토큰·짝 코드가 비밀이라 출처 제한은 두지 않는다
 * (휴대폰 페이지가 PC 와 같은 주소에서 열리므로 출처가 같거나 터널 주소일 수 있다).
 */
@Configuration
@EnableWebSocket
public class RelayWebSocketConfig implements WebSocketConfigurer {

    public static final String PATH = "/ws/proctor";
    /** 서버 추론 감독 (PAIR_SERVER_VISION_ENABLED 가 꺼져 있으면 연결 직후 4403 으로 닫는다) */
    public static final String VISION_PATH = "/ws/vision";

    private final ProctorRelayService handler;
    private final VisionRelayService visionHandler;

    public RelayWebSocketConfig(ProctorRelayService handler, VisionRelayService visionHandler) {
        this.handler = handler;
        this.visionHandler = visionHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, PATH).setAllowedOriginPatterns("*");
        registry.addHandler(visionHandler, VISION_PATH).setAllowedOriginPatterns("*");
    }
}
