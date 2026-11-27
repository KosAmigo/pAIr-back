-- 환불을 토스 결제 취소로 처리한다.
-- REFUNDING: 크레딧을 빼고 토스 취소를 요청한 중간 상태(결과를 모르면 이 상태로 남고, 다시 누르면 같은 멱등 키로 이어서 확인한다)
-- PURCHASE_REFUND_UNDO: 토스가 취소를 거절해 뺐던 크레딧을 되돌린 기록(구매 1건에 한 번, 그 뒤 그 구매는 환불을 막는다)
ALTER TABLE credit_purchases ADD COLUMN refund_key VARCHAR(36);
ALTER TABLE credit_purchases ADD COLUMN refund_started_at TIMESTAMPTZ;
ALTER TABLE credit_purchases DROP CONSTRAINT credit_purchases_status_check;
ALTER TABLE credit_purchases ADD CONSTRAINT credit_purchases_status_check
    CHECK (status IN ('READY', 'CONFIRMING', 'PAID', 'FAILED', 'CANCELED', 'REFUNDING', 'REFUNDED'));
COMMENT ON COLUMN credit_purchases.status IS '결제 상태(READY 결제 전/CONFIRMING 승인 확인 중/PAID 완료/FAILED 실패/CANCELED 취소/REFUNDING 환불 처리 중/REFUNDED 환불)';

ALTER TABLE credit_ledger DROP CONSTRAINT credit_ledger_reason_check;
ALTER TABLE credit_ledger ADD CONSTRAINT credit_ledger_reason_check
    CHECK (reason IN ('SIGNUP', 'PURCHASE', 'REFUND', 'ADJUST', 'ATTEMPT_START', 'ROLLBACK',
                      'CHAT_TURN', 'PURCHASE_REFUND', 'PURCHASE_REFUND_UNDO'));
ALTER TABLE credit_ledger DROP CONSTRAINT credit_ledger_reason_amount_check;
ALTER TABLE credit_ledger ADD CONSTRAINT credit_ledger_reason_amount_check
    CHECK ((reason IN ('SIGNUP', 'PURCHASE', 'REFUND', 'PURCHASE_REFUND_UNDO') AND amount > 0)
        OR (reason IN ('ATTEMPT_START', 'ROLLBACK', 'CHAT_TURN', 'PURCHASE_REFUND') AND amount < 0)
        OR reason = 'ADJUST');
ALTER TABLE credit_ledger DROP CONSTRAINT credit_ledger_purchase_check;
ALTER TABLE credit_ledger ADD CONSTRAINT credit_ledger_purchase_check
    CHECK ((reason IN ('PURCHASE', 'PURCHASE_REFUND', 'PURCHASE_REFUND_UNDO')) = (purchase_id IS NOT NULL));
