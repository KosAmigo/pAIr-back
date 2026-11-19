package com.pair.web.auth;

/**
 * 로그인한 콘솔 사용자. 컨트롤러 메서드 파라미터로 받는다 ({@link CurrentUserArgumentResolver}).
 * companyId 는 기업 회원(MANAGER·PROCTOR)만 있고 관리자(ADMIN)는 null 이다.
 */
public record CurrentUser(Long userId, Role role, Long companyId) {

    public enum Role { USER, MANAGER, PROCTOR, ADMIN }

    public CurrentUser {
        if (userId == null || role == null) throw new IllegalArgumentException("사용자 정보가 비어 있습니다.");
        if ((role == Role.MANAGER || role == Role.PROCTOR) && companyId == null) {
            throw new IllegalArgumentException("기업 회원은 회사가 있어야 합니다.");
        }
    }

    public boolean isAdmin() {
        return role == Role.ADMIN;
    }

    /** 이 회사의 데이터를 볼 수 있는지 (관리자는 모든 회사) */
    public boolean belongsTo(Long company) {
        return isAdmin() || (companyId != null && companyId.equals(company));
    }
}
