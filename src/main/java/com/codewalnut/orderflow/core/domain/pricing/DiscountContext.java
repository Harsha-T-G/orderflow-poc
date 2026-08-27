package com.codewalnut.orderflow.core.domain.pricing;

import com.codewalnut.orderflow.core.domain.MonetaryAmounts;
import com.codewalnut.orderflow.core.domain.customer.CustomerType;
import com.codewalnut.orderflow.core.exception.InvalidCustomerDataException;
import com.codewalnut.orderflow.core.exception.InvalidOrderException;

import java.math.BigDecimal;

public final class DiscountContext {

    private final CustomerType customerType;
    private final BigDecimal originalAmount;
    private final int totalQuantity;

    public DiscountContext(CustomerType customerType, BigDecimal originalAmount, int totalQuantity) {
        if (customerType == null) {
            throw new InvalidCustomerDataException("Discount context customer type must not be null");
        }
        if (totalQuantity < 0) {
            throw new InvalidOrderException(
                    "Discount context total quantity must not be negative: " + totalQuantity);
        }
        this.customerType = customerType;
        this.originalAmount = MonetaryAmounts.requireNonNegative(
                originalAmount, "Discount context original amount");
        this.totalQuantity = totalQuantity;
    }

    public CustomerType getCustomerType() {
        return customerType;
    }

    public BigDecimal getOriginalAmount() {
        return originalAmount;
    }

    public int getTotalQuantity() {
        return totalQuantity;
    }
}
