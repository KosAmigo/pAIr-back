package com.pair.credit;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pair.web.auth.CurrentUser;

@RestController
@RequestMapping("/api/credits")
public class CreditController {
    private final CreditService credits;

    public CreditController(CreditService credits) {
        this.credits = credits;
    }

    public record BalanceResponse(int balance) {}

    @GetMapping
    public BalanceResponse balance(CurrentUser user) {
        return new BalanceResponse(credits.balance(user.userId()));
    }
}
