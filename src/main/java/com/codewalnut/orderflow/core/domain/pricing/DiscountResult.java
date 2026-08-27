package com.codewalnut.orderflow.core.domain.pricing;

import com.codewalnut.orderflow.core.domain.MonetaryAmounts;

import java.math.BigDecimal;
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
        this.originalAmount = MonetaryAmounts.requireNonNegative(originalAmount, "Discount result original amount");
        this.discountAmount = MonetaryAmounts.requireNonNegative(discountAmount, "Discount result discount amount");
        this.finalAmount = MonetaryAmounts.requireNonNegative(finalAmount, "Discount result final amount");
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
}
