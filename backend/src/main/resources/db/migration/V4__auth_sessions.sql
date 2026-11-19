-- 로그인 세션. 계정은 ERD 의 users 테이블(V1)을 그대로 쓴다(비밀번호는 users.password_hash 에 BCrypt 해시).
-- 세션은 토큰 원문이 아니라 해시와 만료 시각만 저장한다.
CREATE TABLE auth_sessions (
    token_hash varchar(64) PRIMARY KEY,
    user_id    bigint      NOT NULL REFERENCES users (user_id) ON DELETE CASCADE,
    created_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL,
    CHECK (expires_at > created_at)
);
CREATE INDEX idx_auth_sessions_expires_at ON auth_sessions (expires_at);
