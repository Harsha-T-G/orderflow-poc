package com.codewalnut.orderflow.core.domain.pricing;

import java.math.BigDecimal;
import java.util.List;

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
        this.appliedRuleNames = List.copyOf(appliedRuleNames);
        this.originalAmount = originalAmount;
        this.discountAmount = discountAmount;
        this.finalAmount = finalAmount;
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
