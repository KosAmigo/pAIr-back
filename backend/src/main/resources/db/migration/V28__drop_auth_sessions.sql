-- V13 auth_refresh_sessions 로 대체된 로그인 세션 테이블(V4)을 제거한다. 인덱스 idx_auth_sessions_expires_at 도 함께 사라진다.
DROP TABLE auth_sessions;
