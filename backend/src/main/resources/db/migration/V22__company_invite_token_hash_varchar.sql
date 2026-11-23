ALTER TABLE company_invites
    ALTER COLUMN token_hash TYPE VARCHAR(64)
    USING token_hash::VARCHAR(64);
