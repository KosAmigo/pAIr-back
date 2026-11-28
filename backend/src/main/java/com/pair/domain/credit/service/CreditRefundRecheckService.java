package com.pair.domain.credit.service;

import java.time.Duration;
import java.time.Instant;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 환불 자동 재확인. 토스 결제 취소 결과를 몰라 REFUNDING 으로 남은 환불(크레딧은 이미 빠짐)을 5분마다 이어서 처리한다.
 * 사용자가 "환불 다시 확인"을 누르지 않아도 끝나게 하려는 것이다. 정상 요청은 토스 시간 제한을 다 써도 1분 안에 끝나므로
 * 2분 넘게 남은 것만 본다(진행 중인 요청과 겹치지 않게). 한 번에 최대 20건.
 * 서버 1대 기준이다(두 서버가 겹쳐 돌아도 같은 구매는 행 잠금으로 차례로 처리되고, 전액 취소는 한 번만 성공한다).
 */
@Component
@ConditionalOnProperty(name = "pair.toss.refund-recheck-enabled", havingValue = "true", matchIfMissing = true)
public class CreditRefundRecheckService {
    static final Duration STALE_AFTER = Duration.ofMinutes(2);
    static final int BATCH = 20;

    private final CreditPurchaseService purchases;

    public CreditRefundRecheckService(CreditPurchaseService purchases) {
        this.purchases = purchases;
    }

    @Scheduled(fixedDelay = 300_000, initialDelay = 120_000)
    public void recheck() {
        purchases.recheckStaleRefunds(Instant.now().minus(STALE_AFTER), BATCH);
    }
}
