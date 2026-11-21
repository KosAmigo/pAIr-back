ALTER TABLE users ADD COLUMN token_version BIGINT NOT NULL DEFAULT 0;
CREATE INDEX idx_auth_refresh_sessions_user_id ON auth_refresh_sessions (user_id);
CREATE INDEX idx_exam_sessions_company_active ON exam_sessions (company_id, ends_at) WHERE canceled_at IS NULL;
CREATE INDEX idx_users_company_id ON users (company_id);
