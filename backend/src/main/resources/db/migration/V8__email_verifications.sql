CREATE TABLE email_verifications (
    email           varchar(255) PRIMARY KEY,
    code_hash       varchar(60)  NOT NULL,
    created_at      timestamptz  NOT NULL,
    expires_at      timestamptz  NOT NULL,
    failed_attempts integer      NOT NULL DEFAULT 0 CHECK (failed_attempts >= 0),
    consumed_at     timestamptz,
    CHECK (expires_at > created_at)
);
