package com.pair.domain.credit.entity;

import static com.pair.domain.credit.entity.CreditPurchase.Status.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import com.pair.domain.credit.entity.CreditPurchase.Status;
import com.pair.global.error.ConflictException;

class CreditPurchaseTest {

    /** 상태를 바꾸는 메서드와, 그 메서드가 가는 상태(retryRefund는 상태를 안 바꾸므로 null) */
    record Op(String name, Consumer<CreditPurchase> run, Status target) {}

    static final List<Op> OPS = List.of(
            new Op("beginConfirmation", p -> p.beginConfirmation("pk", "ck"), CONFIRMING),
            new Op("cancel", CreditPurchase::cancel, CANCELED),
            new Op("paid", CreditPurchase::paid, PAID),
            new Op("failed", CreditPurchase::failed, FAILED),
            new Op("beginRefund", p -> p.beginRefund("rk"), REFUNDING),
            new Op("refunded", CreditPurchase::refunded, REFUNDED),
            new Op("refundRejected", CreditPurchase::refundRejected, PAID),
            new Op("retryRefund", p -> p.retryRefund("rk2"), null));

    /** 허용 경로로 원하는 상태까지 옮긴다 */
    static CreditPurchase at(Status s) {
        CreditPurchase p = new CreditPurchase(1L, 10, 1000, "order");
        switch (s) {
            case READY -> {}
            case CANCELED -> p.cancel();
            case CONFIRMING -> p.beginConfirmation("pk", "ck");
            case FAILED -> { p.beginConfirmation("pk", "ck"); p.failed(); }
            case PAID -> { p.beginConfirmation("pk", "ck"); p.paid(); }
            case REFUNDING -> { p.beginConfirmation("pk", "ck"); p.paid(); p.beginRefund("rk"); }
            case REFUNDED -> { p.beginConfirmation("pk", "ck"); p.paid(); p.beginRefund("rk"); p.refunded(); }
        }
        assertThat(p.getStatus()).isEqualTo(s);
        return p;
    }

    @Test
    void 모든_상태와_메서드_조합() {
        for (Status from : Status.values()) {
            for (Op op : OPS) {
                CreditPurchase p = at(from);
                boolean allowed = op.target() == null ? from == REFUNDING : from.canMoveTo(op.target());
                String label = from + " -> " + op.name();
                if (allowed) {
                    op.run().accept(p);
                    assertThat(p.getStatus()).as(label).isEqualTo(op.target() == null ? from : op.target());
                } else {
                    assertThatThrownBy(() -> op.run().accept(p)).as(label).isInstanceOf(ConflictException.class);
                    assertThat(p.getStatus()).as(label + " 상태 유지").isEqualTo(from);
                }
            }
        }
    }

    @Test
    void 허용_전이_표() {
        assertThat(READY.canMoveTo(CONFIRMING)).isTrue();
        assertThat(READY.canMoveTo(CANCELED)).isTrue();
        assertThat(CONFIRMING.canMoveTo(PAID)).isTrue();
        assertThat(CONFIRMING.canMoveTo(FAILED)).isTrue();
        assertThat(PAID.canMoveTo(REFUNDING)).isTrue();
        assertThat(REFUNDING.canMoveTo(REFUNDED)).isTrue();
        assertThat(REFUNDING.canMoveTo(PAID)).isTrue();
        for (Status end : List.of(FAILED, CANCELED, REFUNDED))
            for (Status to : Status.values()) assertThat(end.canMoveTo(to)).as(end + "->" + to).isFalse();
        assertThat(REFUNDED.canMoveTo(PAID)).isFalse();
    }

    @Test
    void 허용된_전이는_시각과_키를_넣는다() {
        CreditPurchase p = at(PAID);
        assertThat(p.getPaidAt()).isNotNull();
        p.beginRefund("rk");
        assertThat(p.getRefundKey()).isEqualTo("rk");
        assertThat(p.getRefundStartedAt()).isNotNull();
        p.retryRefund("rk2");
        assertThat(p.getRefundKey()).isEqualTo("rk2");
        p.refunded();
        assertThat(p.getRefundedAt()).isNotNull();
    }
}
