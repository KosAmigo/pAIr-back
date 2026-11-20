-- 번호가 확정된 뒤 Flyway migration 경로에 배치한다. 공유 DB에 자동 적용되지 않는다.
CREATE TABLE auth_refresh_sessions (
    session_id varchar(43) PRIMARY KEY,
    user_id bigint NOT NULL REFERENCES users (user_id) ON DELETE CASCADE,
    token_hash varchar(64) NOT NULL,
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    CHECK (expires_at > created_at)
);
CREATE INDEX idx_auth_refresh_sessions_expires_at ON auth_refresh_sessions (expires_at);
