package com.codewalnut.orderflow.core.domain.pricing;

import com.codewalnut.orderflow.core.exception.InvalidMonetaryValueException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Objects;

public final class DiscountResult {

    private final List<String> appliedRuleNames;
    private final BigDecimal originalAmount;
    private final BigDecimal discountAmount;
    private final BigDecimal finalAmount;

    public DiscountResult(
            List<String> appliedRuleNames,
            BigDecimal originalAmount,
            BigDecimal discountAmount,
            BigDecimal finalAmount) {
        this.appliedRuleNames = List.copyOf(Objects.requireNonNull(appliedRuleNames, "applied rule names must not be null"));
        this.originalAmount = requireNonNegativeAmount(originalAmount, "original amount");
        this.discountAmount = requireNonNegativeAmount(discountAmount, "discount amount");
        this.finalAmount = requireNonNegativeAmount(finalAmount, "final amount");
    }

    public List<String> getAppliedRuleNames() {
        return appliedRuleNames;
    }

    public BigDecimal getOriginalAmount() {
        return originalAmount;
    }

    public BigDecimal getDiscountAmount() {
        return discountAmount;
    }

    public BigDecimal getFinalAmount() {
        return finalAmount;
    }

    private static BigDecimal requireNonNegativeAmount(BigDecimal amount, String label) {
        if (amount == null || amount.signum() < 0) {
            throw new InvalidMonetaryValueException(
                    "Discount result " + label + " must not be null or negative: " + amount);
        }
        return amount.setScale(2, RoundingMode.HALF_UP);
    }
}
