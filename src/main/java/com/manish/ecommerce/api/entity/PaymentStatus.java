package com.manish.ecommerce.api.entity;

import java.util.EnumSet;
import java.util.Set;

public enum PaymentStatus {
    PENDING,
    COMPLETED,
    FAILED,
    REFUNDED;

    /** REFUNDED is only reached by cancelling a paid order, never requested directly. */
    public Set<PaymentStatus> allowedTransitions() {
        return switch (this) {
            case PENDING -> EnumSet.of(COMPLETED, FAILED);
            case COMPLETED -> EnumSet.of(REFUNDED);
            case FAILED, REFUNDED -> EnumSet.noneOf(PaymentStatus.class);
        };
    }

    public boolean canTransitionTo(PaymentStatus next) {
        return allowedTransitions().contains(next);
    }
}
