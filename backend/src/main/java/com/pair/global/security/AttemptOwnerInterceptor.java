package com.pair.global.security;

import com.pair.global.error.ForbiddenException;
import com.pair.global.error.UnauthorizedException;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import com.pair.domain.attempt.entity.Attempt;
import com.pair.domain.attempt.repository.AttemptRepository;
import com.pair.domain.exam.service.LiveAttemptAccessService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * /api/attempts/{id}/** 요청은 그 응시의 주인만 보낼 수 있다. 컨트롤러 메서드마다 검사를 빠뜨려도 새지 않게 한곳에서 막는다.
 * - 자가진단 응시(user_id 있음): 로그인하지 않았으면 401, 다른 회원의 응시면 403
 * - 실전 응시(user_id 없음): 응시자는 로그인하지 않으므로 X-Pair-Invite 헤더의 초대 토큰으로 확인한다. 풀이에 필요한 경로만 열고
 *   리포트·다시 채점·복기는 막는다. 리포트 조회는 그 회사의 매니저와 관리자만
 * - 없는 응시는 404
 */
@Component
public class AttemptOwnerInterceptor implements HandlerInterceptor {

    public static final String INVITE = "X-Pair-Invite";

    private static final String BASE = "/api/attempts/{id}";
    private static final String REPORT = BASE + "/report";

    /** 실전 응시자에게 여는 경로. 새 경로를 만들면 기본으로 막히므로, 실전에서도 쓰는 것만 여기에 더한다 */
    private static final Set<String> LIVE_PATHS = Set.of(
            BASE, BASE + "/file", BASE + "/build", BASE + "/run", BASE + "/diagnostics", BASE + "/complete", BASE + "/definition",
            BASE + "/chat", BASE + "/chat/stream", BASE + "/attachments/{attachmentId}", BASE + "/edits",
            BASE + "/questions", BASE + "/questions/{questionId}/answer",
            BASE + "/materials", BASE + "/materials/file", BASE + "/materials/pages",
            BASE + "/submit", BASE + "/followups");

    private final AttemptRepository records;
    private final CurrentUserResolver currentUser;
    private final LiveAttemptAccessService live;

    public AttemptOwnerInterceptor(AttemptRepository records, CurrentUserResolver currentUser, LiveAttemptAccessService live) {
        this.records = records;
        this.currentUser = currentUser;
        this.live = live;
    }

    @Override
    @SuppressWarnings("unchecked")
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        var vars = (Map<String, String>) request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        String id = vars == null ? null : vars.get("id");
        if (id == null) return true;
        Attempt record = find(id);
        if (record.getUserId() == null) {
            checkLive(request, record.getParticipantId());
            return true;
        }
        CurrentUser user = currentUser.resolve(request).orElseThrow(() -> new UnauthorizedException("로그인이 필요합니다."));
        if (!record.getUserId().equals(user.userId())) throw new ForbiddenException("다른 회원의 응시입니다.");
        // 기업 계정이 예전에 남긴 자가진단 응시는 보기만 한다
        if (user.isCompanyMember() && !CompanyAccountAccessInterceptor.isRead(request.getMethod())) {
            throw new ForbiddenException(CompanyAccountAccessInterceptor.DENIED_MESSAGE);
        }
        return true;
    }

    private void checkLive(HttpServletRequest request, Long participantId) {
        String path = String.valueOf(request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE));
        if (path.equals(REPORT) && CompanyAccountAccessInterceptor.isRead(request.getMethod())) {
            CurrentUser user = currentUser.resolve(request).orElseThrow(() -> new UnauthorizedException("로그인이 필요합니다."));
            live.requireResultViewer(user, participantId);
            return;
        }
        String token = request.getHeader(INVITE);
        if (token == null || token.isBlank()) {
            Optional<CurrentUser> user = currentUser.resolve(request);
            if (user.isEmpty()) throw new UnauthorizedException("입장 링크로 다시 들어와 주세요.");
            throw new ForbiddenException("실전 시험 응시는 입장 링크로 들어온 응시자만 이용할 수 있습니다.");
        }
        live.requireParticipant(token, participantId);
        if (!LIVE_PATHS.contains(path)) throw new ForbiddenException("실전 시험에서는 이용할 수 없습니다.");
    }

    private Attempt find(String id) {
        try {
            return records.findById(Long.parseLong(id)).filter(r -> !r.isDeleted()).orElseThrow(() -> new NoSuchElementException("없는 응시입니다: " + id));
        } catch (NumberFormatException e) {
            throw new NoSuchElementException("없는 응시입니다: " + id);
        }
    }
}
