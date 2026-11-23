package com.pair.domain.credit.controller;

import com.pair.domain.credit.entity.CreditLedger;
import com.pair.domain.credit.repository.CreditLedgerRepository;
import com.pair.domain.credit.service.CreditPurchaseService;
import com.pair.domain.credit.service.CreditService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import com.pair.global.security.CurrentUser;
import com.pair.domain.attempt.entity.ChatTurn;
import com.pair.domain.attempt.repository.ChatTurnRepository;

@RestController
@RequestMapping("/api/credits")
public class CreditController {
    private final CreditService credits;
    private final CreditPurchaseService purchases;
    private final CreditLedgerRepository ledger;
    private final ChatTurnRepository turns;

    public CreditController(CreditService credits, CreditPurchaseService purchases, CreditLedgerRepository ledger,
                            ChatTurnRepository turns) {
        this.credits = credits;
        this.purchases = purchases;
        this.ledger = ledger;
        this.turns = turns;
    }

    public record BalanceResponse(int balance) {}

    @GetMapping
    public BalanceResponse balance(CurrentUser user) {
        return new BalanceResponse(credits.balance(user.userId()));
    }

    public record CreatePurchaseRequest(@Positive int credits) {}
    public record ConfirmRequest(@NotBlank @Size(max = 200) String paymentKey, @Positive int amount) {}
    public record LedgerView(Long id, int amount, int balanceAfter, String reason, Long attemptId,
                             Long turnId, Long purchaseId, Instant createdAt) {
        static LedgerView from(CreditLedger entry, Map<Long, ChatTurn> turns) {
            Long attemptId = entry.getAttemptId();
            if (attemptId == null && entry.getTurnId() != null) {
                ChatTurn turn = turns.get(entry.getTurnId());
                if (turn != null) attemptId = turn.getAttemptId();
            }
            return new LedgerView(entry.getId(), entry.getAmount(), entry.getBalanceAfter(), entry.getReason(),
                    attemptId, entry.getTurnId(), entry.getPurchaseId(), entry.getCreatedAt());
        }
    }

    @GetMapping("/products")
    public List<CreditPurchaseService.Product> products(CurrentUser user) {
        return purchases.products();
    }

    @PostMapping("/purchases")
    public CreditPurchaseService.PurchaseView create(CurrentUser user, @Valid @RequestBody CreatePurchaseRequest request) {
        return purchases.create(user.userId(), request.credits());
    }

    @PostMapping("/purchases/{orderId}/confirm")
    public CreditPurchaseService.PurchaseView confirm(CurrentUser user, @PathVariable String orderId,
                                                       @Valid @RequestBody ConfirmRequest request) {
        return purchases.confirm(user.userId(), orderId, request.paymentKey(), request.amount());
    }

    @PostMapping("/purchases/{orderId}/recover")
    public CreditPurchaseService.PurchaseView recover(CurrentUser user, @PathVariable String orderId) {
        return purchases.recover(user.userId(), orderId);
    }

    @PostMapping("/purchases/{orderId}/cancel")
    public CreditPurchaseService.PurchaseView cancel(CurrentUser user, @PathVariable String orderId) {
        return purchases.cancelReady(user.userId(), orderId);
    }

    @PostMapping("/purchases/{orderId}/refund")
    public CreditPurchaseService.PurchaseView refund(CurrentUser user, @PathVariable String orderId) {
        return purchases.refund(user.userId(), orderId);
    }

    @GetMapping("/purchases")
    public Page<CreditPurchaseService.PurchaseView> history(CurrentUser user,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        validatePage(page, size);
        return purchases.history(user.userId(), page, size);
    }

    @GetMapping("/ledger")
    public Page<LedgerView> ledger(CurrentUser user, @RequestParam(defaultValue = "0") int page,
                                   @RequestParam(defaultValue = "20") int size) {
        validatePage(page, size);
        Page<CreditLedger> entries = ledger.findByUserIdOrderByIdDesc(user.userId(), PageRequest.of(page, size));
        List<Long> turnIds = entries.getContent().stream().map(CreditLedger::getTurnId)
                .filter(java.util.Objects::nonNull).distinct().toList();
        Map<Long, ChatTurn> turnMap = turns.findAllById(turnIds).stream()
                .collect(Collectors.toMap(ChatTurn::getId, Function.identity()));
        return entries.map(entry -> LedgerView.from(entry, turnMap));
    }

    private static void validatePage(int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw new IllegalArgumentException("페이지 또는 크기가 올바르지 않습니다.");
    }
}
