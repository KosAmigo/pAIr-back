package com.pair.domain.credit.controller;

import com.pair.domain.credit.dto.BalanceResponse;
import com.pair.domain.credit.dto.ConfirmRequest;
import com.pair.domain.credit.dto.CreatePurchaseRequest;
import com.pair.domain.credit.dto.LedgerResponse;
import com.pair.domain.credit.dto.ProductResponse;
import com.pair.domain.credit.dto.PurchaseResponse;
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

    @GetMapping
    public BalanceResponse balance(CurrentUser user) {
        return new BalanceResponse(credits.balance(user.userId()));
    }

    @GetMapping("/products")
    public List<ProductResponse> products(CurrentUser user) {
        return purchases.products();
    }

    @PostMapping("/purchases")
    public PurchaseResponse create(CurrentUser user, @Valid @RequestBody CreatePurchaseRequest request) {
        return purchases.create(user.userId(), request.credits());
    }

    @PostMapping("/purchases/{orderId}/confirm")
    public PurchaseResponse confirm(CurrentUser user, @PathVariable String orderId,
                                                       @Valid @RequestBody ConfirmRequest request) {
        return purchases.confirm(user.userId(), orderId, request.paymentKey(), request.amount());
    }

    @PostMapping("/purchases/{orderId}/recover")
    public PurchaseResponse recover(CurrentUser user, @PathVariable String orderId) {
        return purchases.recover(user.userId(), orderId);
    }

    @PostMapping("/purchases/{orderId}/cancel")
    public PurchaseResponse cancel(CurrentUser user, @PathVariable String orderId) {
        return purchases.cancelReady(user.userId(), orderId);
    }

    @PostMapping("/purchases/{orderId}/refund")
    public PurchaseResponse refund(CurrentUser user, @PathVariable String orderId) {
        return purchases.refund(user.userId(), orderId);
    }

    @GetMapping("/purchases")
    public Page<PurchaseResponse> history(CurrentUser user,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        validatePage(page, size);
        return purchases.history(user.userId(), page, size);
    }

    @GetMapping("/ledger")
    public Page<LedgerResponse> ledger(CurrentUser user, @RequestParam(defaultValue = "0") int page,
                                   @RequestParam(defaultValue = "20") int size) {
        validatePage(page, size);
        Page<CreditLedger> entries = ledger.findByUserIdOrderByIdDesc(user.userId(), PageRequest.of(page, size));
        List<Long> turnIds = entries.getContent().stream().map(CreditLedger::getTurnId)
                .filter(java.util.Objects::nonNull).distinct().toList();
        Map<Long, ChatTurn> turnMap = turns.findAllById(turnIds).stream()
                .collect(Collectors.toMap(ChatTurn::getId, Function.identity()));
        return entries.map(entry -> LedgerResponse.from(entry, turnMap));
    }

    private static void validatePage(int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw new IllegalArgumentException("페이지 또는 크기가 올바르지 않습니다.");
    }
}
