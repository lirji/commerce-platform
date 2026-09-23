package com.lrj.commerce.order;

import com.lrj.commerce.kernel.DomainException;
import com.lrj.commerce.order.api.*;
import com.lrj.commerce.order.domain.OrderLifecycle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.params.provider.Arguments;
import static org.junit.jupiter.api.Assertions.*;

class OrderLifecycleTest {
    private static final Map<String, OrderState> ALLOWED = Map.ofEntries(
        Map.entry("PENDING_PAYMENT/START_PAYMENT", OrderState.PAYMENT_IN_PROGRESS),
        Map.entry("PENDING_PAYMENT/REQUEST_CANCEL", OrderState.CANCELLED),
        Map.entry("PENDING_PAYMENT/PAYMENT_CONFIRMED", OrderState.PAID),
        Map.entry("PAYMENT_IN_PROGRESS/REQUEST_CANCEL", OrderState.CLOSING),
        Map.entry("PAYMENT_IN_PROGRESS/PAYMENT_CONFIRMED", OrderState.PAID),
        Map.entry("CLOSING/PAYMENT_CONFIRMED", OrderState.PAID),
        Map.entry("CLOSING/PAYMENT_ABSENCE_CONFIRMED", OrderState.CANCELLED),
        Map.entry("PAID/START_FULFILLMENT", OrderState.FULFILLING),
        Map.entry("FULFILLING/CONFIRM_DELIVERY", OrderState.COMPLETED));

    @ParameterizedTest @MethodSource("transitions")
    void validatesEveryStateEventPair(OrderState state, OrderEvent event) {
        var original = new OrderLifecycle(state, 5);
        var expected = ALLOWED.get(state.name() + "/" + event.name());
        if (expected == null) {
            assertEquals(DomainException.Code.ILLEGAL_TRANSITION,
                assertThrows(DomainException.class, () -> original.apply(event)).code());
        } else {
            assertEquals(new OrderLifecycle(expected, 6), original.apply(event));
        }
        assertEquals(5, original.version());
    }

    @Test void cancellationDuringPaymentWaitsForEvidenceAndAcceptsLateSuccess() {
        var pending = OrderLifecycle.start().apply(OrderEvent.START_PAYMENT).apply(OrderEvent.REQUEST_CANCEL);
        assertEquals(OrderState.CLOSING, pending.state());
        assertEquals(OrderState.PAID, pending.apply(OrderEvent.PAYMENT_CONFIRMED).state());
        assertEquals(OrderState.CANCELLED, pending.apply(OrderEvent.PAYMENT_ABSENCE_CONFIRMED).state());
    }

    @Test void paidOrderCanCompleteButCannotBeCancelledAsUnpaid() {
        var paid = OrderLifecycle.start().apply(OrderEvent.PAYMENT_CONFIRMED);
        assertThrows(DomainException.class, () -> paid.apply(OrderEvent.REQUEST_CANCEL));
        assertEquals(OrderState.COMPLETED, paid.apply(OrderEvent.START_FULFILLMENT).apply(OrderEvent.CONFIRM_DELIVERY).state());
    }

    @Test void rejectsInvalidRestorationNullEventsAndVersionOverflow() {
        assertThrows(DomainException.class, () -> new OrderLifecycle(OrderState.PAID, -1));
        assertThrows(DomainException.class, () -> OrderLifecycle.start().apply(null));
        assertThrows(DomainException.class, () -> new OrderLifecycle(OrderState.PENDING_PAYMENT, Long.MAX_VALUE).apply(OrderEvent.START_PAYMENT));
    }

    static Stream<Arguments> transitions() {
        return Stream.of(OrderState.values()).flatMap(state -> Stream.of(OrderEvent.values()).map(event -> Arguments.of(state, event)));
    }
}
