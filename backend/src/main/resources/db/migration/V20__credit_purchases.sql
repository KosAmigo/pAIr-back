ALTER TABLE credit_purchases ADD COLUMN order_id VARCHAR(64) UNIQUE;
ALTER TABLE credit_purchases ADD COLUMN confirmation_key VARCHAR(36);
ALTER TABLE credit_purchases ADD COLUMN confirmation_started_at TIMESTAMPTZ;
ALTER TABLE credit_purchases ADD COLUMN refunded_at TIMESTAMPTZ;
ALTER TABLE credit_purchases DROP CONSTRAINT credit_purchases_status_check;
ALTER TABLE credit_purchases ADD CONSTRAINT credit_purchases_status_check
    CHECK (status IN ('READY', 'CONFIRMING', 'PAID', 'FAILED', 'CANCELED', 'REFUNDED'));
ALTER TABLE credit_ledger DROP CONSTRAINT credit_ledger_purchase_id_key;
-- Preserve the unrelated balance and amount checks while replacing the reason-related checks.
DO $$
DECLARE c RECORD;
BEGIN
    FOR c IN SELECT conname FROM pg_constraint
        WHERE conrelid = 'credit_ledger'::regclass AND contype = 'c'
          AND pg_get_constraintdef(oid) LIKE '%reason%'
    LOOP
        EXECUTE format('ALTER TABLE credit_ledger DROP CONSTRAINT %I', c.conname);
    END LOOP;
END $$;
ALTER TABLE credit_ledger ADD CONSTRAINT credit_ledger_reason_check
    CHECK (reason IN ('SIGNUP', 'PURCHASE', 'REFUND', 'ADJUST', 'ATTEMPT_START', 'ROLLBACK',
                      'CHAT_TURN', 'PURCHASE_REFUND'));
ALTER TABLE credit_ledger ADD CONSTRAINT credit_ledger_reason_amount_check
    CHECK ((reason IN ('SIGNUP', 'PURCHASE', 'REFUND') AND amount > 0)
        OR (reason IN ('ATTEMPT_START', 'ROLLBACK', 'CHAT_TURN', 'PURCHASE_REFUND') AND amount < 0)
        OR reason = 'ADJUST');
ALTER TABLE credit_ledger ADD CONSTRAINT credit_ledger_purchase_check
    CHECK ((reason IN ('PURCHASE', 'PURCHASE_REFUND')) = (purchase_id IS NOT NULL));
ALTER TABLE credit_ledger ADD CONSTRAINT credit_ledger_attempt_check
    CHECK ((reason NOT IN ('ATTEMPT_START', 'ROLLBACK') OR attempt_id IS NOT NULL)
       AND (attempt_id IS NULL OR reason IN ('ATTEMPT_START', 'ROLLBACK', 'REFUND')));
ALTER TABLE credit_ledger ADD CONSTRAINT credit_ledger_turn_check
    CHECK ((reason = 'CHAT_TURN') = (turn_id IS NOT NULL));
ALTER TABLE credit_ledger ADD CONSTRAINT credit_ledger_note_check
    CHECK (reason <> 'ADJUST' OR note IS NOT NULL);
CREATE UNIQUE INDEX credit_ledger_purchase_reason_once ON credit_ledger (purchase_id, reason)
    WHERE purchase_id IS NOT NULL;
