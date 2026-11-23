package com.pair.global.config;

import com.pair.domain.exam.controller.ProctorRelayHandler;
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

    private final ProctorRelayHandler handler;

    public RelayWebSocketConfig(ProctorRelayHandler handler) {
        this.handler = handler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, PATH).setAllowedOriginPatterns("*");
    }
}
