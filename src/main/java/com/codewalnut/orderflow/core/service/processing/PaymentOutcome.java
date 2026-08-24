package com.codewalnut.orderflow.core.service.processing;

import java.util.Objects;

record PaymentOutcome(Kind kind, String reason, Throwable cause) {

    PaymentOutcome {
        kind = Objects.requireNonNull(kind, "kind must not be null");
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
    }

    enum Kind {
        SUCCESS,
        DECLARED_FAILURE,
        UNEXPECTED_FAILURE,
        TIMED_OUT,
        REJECTED,
        CANCELLED
    }
}
