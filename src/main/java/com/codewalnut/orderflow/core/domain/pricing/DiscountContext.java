package com.codewalnut.orderflow.core.domain.pricing;

import com.codewalnut.orderflow.core.domain.customer.CustomerType;
import com.codewalnut.orderflow.core.exception.InvalidCustomerDataException;
import com.codewalnut.orderflow.core.exception.InvalidMonetaryValueException;
import com.codewalnut.orderflow.core.exception.InvalidOrderException;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class DiscountContext {

    private final CustomerType customerType;
    private final BigDecimal originalAmount;
    private final int totalQuantity;

    public DiscountContext(CustomerType customerType, BigDecimal originalAmount, int totalQuantity) {
        if (customerType == null) {
            throw new InvalidCustomerDataException("Discount context customer type must not be null");
        }
        if (originalAmount == null || originalAmount.signum() < 0) {
            throw new InvalidMonetaryValueException(
                    "Discount context original amount must not be null or negative: " + originalAmount);
        }
        if (totalQuantity < 0) {
            throw new InvalidOrderException(
                    "Discount context total quantity must not be negative: " + totalQuantity);
        }
        this.customerType = customerType;
        this.originalAmount = originalAmount.setScale(2, RoundingMode.HALF_UP);
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
