package com.pair.global.security;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.springframework.stereotype.Component;

import com.pair.global.config.LoginLimitProperties;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 요청을 보낸 사람의 IP. X-Forwarded-For 의 왼쪽 값은 보낸 사람이 꾸밀 수 있으므로,
 * 우리 프록시가 덧붙인 오른쪽에서 trustedProxyHops 번째 값만 믿는다. 값이 모자라면 직접 연결된 주소를 쓴다.
 */
@Component
public class ClientIpResolver {

    private static final int MAX_LENGTH = 64;

    private final int hops;

    public ClientIpResolver(LoginLimitProperties props) {
        this.hops = props.trustedProxyHops();
    }

    public String resolve(HttpServletRequest request) {
        if (hops == 0) return request.getRemoteAddr();
        List<String> chain = new ArrayList<>();
        for (String header : Collections.list(request.getHeaders("X-Forwarded-For"))) {
            for (String part : header.split(",")) chain.add(part.strip());
        }
        if (chain.size() < hops) return request.getRemoteAddr();
        String ip = chain.get(chain.size() - hops);
        return ip.isEmpty() || ip.length() > MAX_LENGTH ? request.getRemoteAddr() : ip;
    }
}
