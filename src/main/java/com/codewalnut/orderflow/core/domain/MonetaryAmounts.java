package com.codewalnut.orderflow.core.domain;

import com.codewalnut.orderflow.core.exception.InvalidMonetaryValueException;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class MonetaryAmounts {

    private MonetaryAmounts() {
    }

    public static BigDecimal requirePositive(BigDecimal amount, String label) {
        BigDecimal normalizedAmount = scaled(amount, label);
        if (normalizedAmount.signum() <= 0) {
            throw new InvalidMonetaryValueException(label + " must be positive: " + normalizedAmount);
        }
        return normalizedAmount;
    }

    public static BigDecimal requireNonNegative(BigDecimal amount, String label) {
        BigDecimal normalizedAmount = scaled(amount, label);
        if (normalizedAmount.signum() < 0) {
            throw new InvalidMonetaryValueException(label + " must not be negative: " + normalizedAmount);
        }
        return normalizedAmount;
    }

    private static BigDecimal scaled(BigDecimal amount, String label) {
        if (amount == null) {
            throw new InvalidMonetaryValueException(label + " must not be null");
        }
        return amount.setScale(2, RoundingMode.HALF_UP);
    }
}
