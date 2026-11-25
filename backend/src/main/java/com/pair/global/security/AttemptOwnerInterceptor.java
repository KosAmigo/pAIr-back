package com.pair.global.security;

import com.pair.global.error.ForbiddenException;
import com.pair.global.error.UnauthorizedException;
import java.util.Map;
import java.util.NoSuchElementException;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import com.pair.domain.attempt.entity.Attempt;
import com.pair.domain.attempt.repository.AttemptRepository;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * /api/attempts/{id}/** 요청은 자가진단 응시의 주인만 보낼 수 있다. 컨트롤러 메서드마다 검사를 빠뜨려도 새지 않게 한곳에서 막는다.
 * - 자가진단 응시(user_id 있음): 로그인하지 않았으면 401, 다른 회원의 응시면 403
 * - 실전 응시(user_id 없음): 로그인 없이 초대 토큰으로 들어오므로 여기서는 검사하지 않는다(실전 입장 인증은 팀원 4와 정할 일)
 * - 없는 응시는 404
 */
@Component
public class AttemptOwnerInterceptor implements HandlerInterceptor {

    private final AttemptRepository records;
    private final CurrentUserResolver currentUser;

    public AttemptOwnerInterceptor(AttemptRepository records, CurrentUserResolver currentUser) {
        this.records = records;
        this.currentUser = currentUser;
    }

    @Override
    @SuppressWarnings("unchecked")
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        var vars = (Map<String, String>) request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        String id = vars == null ? null : vars.get("id");
        if (id == null) return true;
        Attempt record = find(id);
        if (record.getUserId() != null) {
            CurrentUser user = currentUser.resolve(request).orElseThrow(() -> new UnauthorizedException("로그인이 필요합니다."));
            if (!record.getUserId().equals(user.userId())) throw new ForbiddenException("다른 회원의 응시입니다.");
            // 기업 계정이 예전에 남긴 자가진단 응시는 보기만 한다
            if (user.isCompanyMember() && !CompanyAccountAccessInterceptor.isRead(request.getMethod())) {
                throw new ForbiddenException(CompanyAccountAccessInterceptor.DENIED_MESSAGE);
            }
        }
        return true;
    }

    private Attempt find(String id) {
        try {
            return records.findById(Long.parseLong(id)).orElseThrow(() -> new NoSuchElementException("없는 응시입니다: " + id));
        } catch (NumberFormatException e) {
            throw new NoSuchElementException("없는 응시입니다: " + id);
        }
    }
}
