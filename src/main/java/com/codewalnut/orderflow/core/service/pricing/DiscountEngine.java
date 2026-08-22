package com.codewalnut.orderflow.core.service.pricing;

import com.codewalnut.orderflow.core.domain.pricing.DiscountContext;
import com.codewalnut.orderflow.core.domain.pricing.DiscountResult;
import com.codewalnut.orderflow.core.domain.pricing.DiscountRule;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

public final class DiscountEngine {

    private static final BigDecimal MAXIMUM_DISCOUNT_RATE = new BigDecimal("0.25");

    private final List<DiscountRule> rules;

    public DiscountEngine(List<DiscountRule> rules) {
        this.rules = List.copyOf(rules);
    }

    public DiscountResult evaluate(DiscountContext context) {
        if (context == null) {
            throw new IllegalArgumentException("Discount context must not be null");
        }

        List<String> appliedRuleNames = new ArrayList<>();
        BigDecimal totalRate = BigDecimal.ZERO;

        for (DiscountRule rule : rules) {
            DiscountRule.NamedRate namedRate = rule.evaluate(context);
            if (namedRate == null) {
                throw new IllegalArgumentException("Discount rule must not return null NamedRate");
            }
            if (namedRate.rate().signum() > 0) {
                appliedRuleNames.add(namedRate.name());
                totalRate = totalRate.add(namedRate.rate());
            }
        }

        if (totalRate.compareTo(MAXIMUM_DISCOUNT_RATE) > 0) {
            totalRate = MAXIMUM_DISCOUNT_RATE;
        }

        BigDecimal originalAmount = context.getOriginalAmount().setScale(2, RoundingMode.HALF_UP);
        BigDecimal discountAmount = originalAmount.multiply(totalRate).setScale(2, RoundingMode.HALF_UP);
        BigDecimal finalAmount = originalAmount.subtract(discountAmount);

        return new DiscountResult(appliedRuleNames, originalAmount, discountAmount, finalAmount);
    }
}
