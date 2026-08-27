package com.codewalnut.orderflow.core.domain.pricing;

import com.codewalnut.orderflow.core.domain.customer.CustomerType;
import com.codewalnut.orderflow.core.exception.InvalidMonetaryValueException;

import java.math.BigDecimal;

@FunctionalInterface
public interface DiscountRule {

    String REGULAR_CUSTOMER = "Regular customer";
    String PREMIUM_CUSTOMER = "Premium customer";
    String CORPORATE_CUSTOMER = "Corporate customer";
    String BULK_QUANTITY = "Bulk quantity";
    String HIGH_VALUE = "High value";

    NamedRate evaluate(DiscountContext context);

    record NamedRate(String name, BigDecimal rate) {
        public NamedRate {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("Discount rule name must not be null or blank");
            }
            if (rate == null) {
                throw new InvalidMonetaryValueException("Discount rate must not be null");
            }
            if (rate.signum() < 0) {
                throw new InvalidMonetaryValueException(
                        "Discount rate must not be negative: " + rate);
            }
        }
    }

    static DiscountRule regularCustomer() {
        return context -> new NamedRate(REGULAR_CUSTOMER, BigDecimal.ZERO);
    }

    static DiscountRule premiumCustomer() {
        return context -> new NamedRate(
                PREMIUM_CUSTOMER,
                context.getCustomerType() == CustomerType.PREMIUM
                        ? new BigDecimal("0.05")
                        : BigDecimal.ZERO);
    }

    static DiscountRule corporateCustomer() {
        return context -> new NamedRate(
                CORPORATE_CUSTOMER,
                context.getCustomerType() == CustomerType.CORPORATE
                        ? new BigDecimal("0.10")
                        : BigDecimal.ZERO);
    }

    static DiscountRule bulkQuantity() {
        return context -> new NamedRate(
                BULK_QUANTITY,
                context.getTotalQuantity() >= 10
                        ? new BigDecimal("0.05")
                        : BigDecimal.ZERO);
    }

    static DiscountRule highValue() {
        return context -> new NamedRate(
                HIGH_VALUE,
                context.getOriginalAmount().compareTo(new BigDecimal("10000")) >= 0
                        ? new BigDecimal("0.05")
                        : BigDecimal.ZERO);
    }
}
