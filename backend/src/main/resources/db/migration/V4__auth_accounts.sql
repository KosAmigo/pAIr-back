-- 가입 계정은 BCrypt 해시만 저장하고, 로그인 세션은 토큰 해시와 만료 시각으로 관리한다.
CREATE TABLE auth_users (
    user_id       bigserial    PRIMARY KEY,
    email         varchar(255) NOT NULL,
    name          varchar(50)  NOT NULL,
    password_hash varchar(100) NOT NULL,
    role          varchar(20)  NOT NULL DEFAULT 'USER'
                               CHECK (role IN ('USER', 'MANAGER', 'PROCTOR', 'ADMIN')),
    company_id    bigint,
    created_at    timestamptz  NOT NULL DEFAULT now(),
    CHECK ((role IN ('MANAGER', 'PROCTOR')) = (company_id IS NOT NULL))
);
CREATE UNIQUE INDEX idx_auth_users_email_ci ON auth_users (lower(email));

CREATE TABLE auth_sessions (
    token_hash varchar(64) PRIMARY KEY,
    user_id    bigint      NOT NULL REFERENCES auth_users (user_id) ON DELETE CASCADE,
    created_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL,
    CHECK (expires_at > created_at)
);
CREATE INDEX idx_auth_sessions_expires_at ON auth_sessions (expires_at);
