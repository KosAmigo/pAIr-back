package com.pair.domain.credit.dto;

import java.time.Instant;
import java.util.Map;

import com.pair.domain.attempt.entity.ChatTurn;
import com.pair.domain.credit.entity.CreditLedger;

public record LedgerResponse(Long id, int amount, int balanceAfter, String reason, Long attemptId,
                             Long turnId, Long purchaseId, Instant createdAt) {
    public static LedgerResponse from(CreditLedger entry, Map<Long, ChatTurn> turns) {
        Long attemptId = entry.getAttemptId();
        if (attemptId == null && entry.getTurnId() != null) {
            ChatTurn turn = turns.get(entry.getTurnId());
            if (turn != null) attemptId = turn.getAttemptId();
        }
        return new LedgerResponse(entry.getId(), entry.getAmount(), entry.getBalanceAfter(), entry.getReason(),
                attemptId, entry.getTurnId(), entry.getPurchaseId(), entry.getCreatedAt());
    }
}
