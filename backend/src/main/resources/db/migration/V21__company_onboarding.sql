ALTER TABLE users
    ADD COLUMN email_verified BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN password_change_required BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN onboarding_mail_status VARCHAR(10) NOT NULL DEFAULT 'SENT'
        CHECK (onboarding_mail_status IN ('PENDING', 'SENT', 'FAILED'));

CREATE UNIQUE INDEX uq_users_active_company_manager
    ON users (company_id)
    WHERE role = 'MANAGER' AND member_status = 'ACTIVE';

CREATE TABLE company_inquiry_members (
    inquiry_member_id BIGSERIAL PRIMARY KEY,
    inquiry_id BIGINT NOT NULL REFERENCES company_inquiries (inquiry_id) ON DELETE CASCADE,
    name VARCHAR(50) NOT NULL,
    email VARCHAR(255) NOT NULL,
    role VARCHAR(10) NOT NULL CHECK (role IN ('MANAGER', 'PROCTOR')),
    UNIQUE (inquiry_id, email)
);

CREATE INDEX idx_company_inquiry_members_inquiry ON company_inquiry_members (inquiry_id);

ALTER TABLE email_verifications ALTER COLUMN expires_at DROP NOT NULL;
ALTER TABLE email_verifications DROP CONSTRAINT IF EXISTS email_verifications_check;
ALTER TABLE email_verifications ADD CONSTRAINT email_verifications_expiry_check
    CHECK (expires_at IS NULL OR expires_at > created_at);

ALTER TABLE companies DROP CONSTRAINT IF EXISTS companies_business_no_key;

CREATE TABLE company_account_events (
    event_id BIGSERIAL PRIMARY KEY,
    company_id BIGINT NOT NULL REFERENCES companies (company_id),
    event_type VARCHAR(30) NOT NULL,
    actor_user_id BIGINT NOT NULL REFERENCES users (user_id),
    target_user_id BIGINT REFERENCES users (user_id),
    details TEXT,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_company_account_events_company_time
    ON company_account_events (company_id, created_at DESC);

ALTER TABLE company_invites ADD COLUMN revoked_at TIMESTAMPTZ;

CREATE UNIQUE INDEX uq_company_invites_pending_email
    ON company_invites (lower(email))
    WHERE accepted_at IS NULL AND revoked_at IS NULL;
