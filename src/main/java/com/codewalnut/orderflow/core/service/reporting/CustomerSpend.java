package com.codewalnut.orderflow.core.service.reporting;

import java.math.BigDecimal;
import java.math.RoundingMode;

public record CustomerSpend(String customerId, BigDecimal amount) {
    public CustomerSpend {
        if (customerId == null || customerId.isBlank()) {
            throw new IllegalArgumentException("Customer ID must not be blank");
        }
        if (amount == null) {
            throw new IllegalArgumentException("Spend amount must not be null");
        }
        amount = amount.setScale(2, RoundingMode.HALF_UP);
    }
}
