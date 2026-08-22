package com.codewalnut.orderflow.core.service.pricing;

import com.codewalnut.orderflow.core.domain.customer.CustomerType;
import com.codewalnut.orderflow.core.exception.InvalidCustomerDataException;
import com.codewalnut.orderflow.core.exception.InvalidMonetaryValueException;
import com.codewalnut.orderflow.core.exception.InvalidOrderException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.codewalnut.orderflow.core.domain.pricing.DiscountContext;
import com.codewalnut.orderflow.core.domain.pricing.DiscountResult;
import com.codewalnut.orderflow.core.domain.pricing.DiscountRule;

class DiscountEngineTest {

    @Test
    void givenRegularCustomerWithoutOtherEligibility_whenDiscounted_thenNoCustomerDiscountApplies() {
        // Arrange
        DiscountContext context = new DiscountContext(
                CustomerType.REGULAR,
                new BigDecimal("100.00"),
                1);
        DiscountEngine engine = new DiscountEngine(List.of(DiscountRule.regularCustomer()));

        // Act
        DiscountResult result = engine.evaluate(context);

        // Assert
        assertTrue(result.getAppliedRuleNames().isEmpty());
        assertEquals(new BigDecimal("100.00"), result.getOriginalAmount());
        assertEquals(new BigDecimal("0.00"), result.getDiscountAmount());
        assertEquals(new BigDecimal("100.00"), result.getFinalAmount());
    }

    @Test
    void givenPremiumCustomer_whenDiscounted_thenFivePercentApplies() {
        // Arrange
        DiscountContext context = new DiscountContext(
                CustomerType.PREMIUM,
                new BigDecimal("200.00"),
                1);
        DiscountEngine engine = new DiscountEngine(List.of(DiscountRule.premiumCustomer()));

        // Act
        DiscountResult result = engine.evaluate(context);

        // Assert
        assertEquals(List.of(DiscountRule.PREMIUM_CUSTOMER), result.getAppliedRuleNames());
        assertEquals(new BigDecimal("200.00"), result.getOriginalAmount());
        assertEquals(new BigDecimal("10.00"), result.getDiscountAmount());
        assertEquals(new BigDecimal("190.00"), result.getFinalAmount());
    }

    @Test
    void givenNonPremiumCustomer_whenPremiumRuleApplied_thenZeroRateContributes() {
        // Arrange
        DiscountContext context = new DiscountContext(
                CustomerType.REGULAR,
                new BigDecimal("200.00"),
                1);
        DiscountEngine engine = new DiscountEngine(List.of(DiscountRule.premiumCustomer()));

        // Act
        DiscountResult result = engine.evaluate(context);

        // Assert
        assertTrue(result.getAppliedRuleNames().isEmpty());
        assertEquals(new BigDecimal("200.00"), result.getOriginalAmount());
        assertEquals(new BigDecimal("0.00"), result.getDiscountAmount());
        assertEquals(new BigDecimal("200.00"), result.getFinalAmount());
    }

    @Test
    void givenCorporateCustomer_whenDiscounted_thenTenPercentApplies() {
        // Arrange
        DiscountContext context = new DiscountContext(
                CustomerType.CORPORATE,
                new BigDecimal("200.00"),
                1);
        DiscountEngine engine = new DiscountEngine(List.of(DiscountRule.corporateCustomer()));

        // Act
        DiscountResult result = engine.evaluate(context);

        // Assert
        assertEquals(List.of(DiscountRule.CORPORATE_CUSTOMER), result.getAppliedRuleNames());
        assertEquals(new BigDecimal("200.00"), result.getOriginalAmount());
        assertEquals(new BigDecimal("20.00"), result.getDiscountAmount());
        assertEquals(new BigDecimal("180.00"), result.getFinalAmount());
    }

    @Test
    void givenNonCorporateCustomer_whenCorporateRuleApplied_thenZeroRateContributes() {
        // Arrange
        DiscountContext context = new DiscountContext(
                CustomerType.PREMIUM,
                new BigDecimal("200.00"),
                1);
        DiscountEngine engine = new DiscountEngine(List.of(DiscountRule.corporateCustomer()));

        // Act
        DiscountResult result = engine.evaluate(context);

        // Assert
        assertTrue(result.getAppliedRuleNames().isEmpty());
        assertEquals(new BigDecimal("200.00"), result.getOriginalAmount());
        assertEquals(new BigDecimal("0.00"), result.getDiscountAmount());
        assertEquals(new BigDecimal("200.00"), result.getFinalAmount());
    }

    @Test
    void givenAtLeastTenItems_whenDiscounted_thenBulkDiscountApplies() {
        // Arrange
        DiscountContext context = new DiscountContext(
                CustomerType.REGULAR,
                new BigDecimal("100.00"),
                10);
        DiscountEngine engine = new DiscountEngine(List.of(DiscountRule.bulkQuantity()));

        // Act
        DiscountResult result = engine.evaluate(context);

        // Assert
        assertEquals(List.of(DiscountRule.BULK_QUANTITY), result.getAppliedRuleNames());
        assertEquals(new BigDecimal("100.00"), result.getOriginalAmount());
        assertEquals(new BigDecimal("5.00"), result.getDiscountAmount());
        assertEquals(new BigDecimal("95.00"), result.getFinalAmount());
    }

    @Test
    void givenNineItems_whenBulkRuleApplied_thenZeroRateContributes() {
        // Arrange
        DiscountContext context = new DiscountContext(
                CustomerType.REGULAR,
                new BigDecimal("100.00"),
                9);
        DiscountEngine engine = new DiscountEngine(List.of(DiscountRule.bulkQuantity()));

        // Act
        DiscountResult result = engine.evaluate(context);

        // Assert
        assertTrue(result.getAppliedRuleNames().isEmpty());
        assertEquals(new BigDecimal("100.00"), result.getOriginalAmount());
        assertEquals(new BigDecimal("0.00"), result.getDiscountAmount());
        assertEquals(new BigDecimal("100.00"), result.getFinalAmount());
    }

    @Test
    void givenOriginalAmountAtLeastTenThousand_whenDiscounted_thenHighValueDiscountApplies() {
        // Arrange
        DiscountContext context = new DiscountContext(
                CustomerType.REGULAR,
                new BigDecimal("10000.00"),
                1);
        DiscountEngine engine = new DiscountEngine(List.of(DiscountRule.highValue()));

        // Act
        DiscountResult result = engine.evaluate(context);

        // Assert
        assertEquals(List.of(DiscountRule.HIGH_VALUE), result.getAppliedRuleNames());
        assertEquals(new BigDecimal("10000.00"), result.getOriginalAmount());
        assertEquals(new BigDecimal("500.00"), result.getDiscountAmount());
        assertEquals(new BigDecimal("9500.00"), result.getFinalAmount());
    }

    @Test
    void givenAmountBelowTenThousand_whenHighValueRuleApplied_thenZeroRateContributes() {
        // Arrange
        DiscountContext context = new DiscountContext(
                CustomerType.REGULAR,
                new BigDecimal("9999.99"),
                1);
        DiscountEngine engine = new DiscountEngine(List.of(DiscountRule.highValue()));

        // Act
        DiscountResult result = engine.evaluate(context);

        // Assert
        assertTrue(result.getAppliedRuleNames().isEmpty());
        assertEquals(new BigDecimal("9999.99"), result.getOriginalAmount());
        assertEquals(new BigDecimal("0.00"), result.getDiscountAmount());
        assertEquals(new BigDecimal("9999.99"), result.getFinalAmount());
    }

    @Test
    void givenMultipleEligibleRules_whenDiscounted_thenDiscountsStack() {
        // Arrange
        DiscountContext context = new DiscountContext(
                CustomerType.CORPORATE,
                new BigDecimal("10000.00"),
                10);
        DiscountEngine engine = new DiscountEngine(List.of(
                DiscountRule.corporateCustomer(),
                DiscountRule.bulkQuantity(),
                DiscountRule.highValue()));

        // Act
        DiscountResult result = engine.evaluate(context);

        // Assert
        assertEquals(
                List.of(
                        DiscountRule.CORPORATE_CUSTOMER,
                        DiscountRule.BULK_QUANTITY,
                        DiscountRule.HIGH_VALUE),
                result.getAppliedRuleNames());
        assertEquals(new BigDecimal("10000.00"), result.getOriginalAmount());
        assertEquals(new BigDecimal("2000.00"), result.getDiscountAmount());
        assertEquals(new BigDecimal("8000.00"), result.getFinalAmount());
    }

    @Test
    void givenRulesAboveTwentyFivePercent_whenDiscounted_thenDiscountIsCapped() {
        // Arrange
        DiscountContext context = new DiscountContext(
                CustomerType.REGULAR,
                new BigDecimal("100.00"),
                1);
        DiscountEngine engine = new DiscountEngine(List.of(
                unused -> new DiscountRule.NamedRate("First fifteen", new BigDecimal("0.15")),
                unused -> new DiscountRule.NamedRate("Second fifteen", new BigDecimal("0.15"))));

        // Act
        DiscountResult result = engine.evaluate(context);

        // Assert
        assertEquals(
                List.of("First fifteen", "Second fifteen"),
                result.getAppliedRuleNames());
        assertEquals(new BigDecimal("100.00"), result.getOriginalAmount());
        assertEquals(new BigDecimal("25.00"), result.getDiscountAmount());
        assertEquals(new BigDecimal("75.00"), result.getFinalAmount());
    }

    @Test
    void givenAnyEligibleRules_whenDiscounted_thenFinalAmountIsNeverNegative() {
        // Arrange
        DiscountContext context = new DiscountContext(
                CustomerType.REGULAR,
                new BigDecimal("100.00"),
                1);
        DiscountEngine engine = new DiscountEngine(List.of(
                unused -> new DiscountRule.NamedRate("Half", new BigDecimal("0.50")),
                unused -> new DiscountRule.NamedRate("Another half", new BigDecimal("0.50"))));

        // Act
        DiscountResult result = engine.evaluate(context);

        // Assert
        assertTrue(result.getFinalAmount().signum() >= 0);
        assertEquals(new BigDecimal("100.00"), result.getOriginalAmount());
        assertEquals(new BigDecimal("25.00"), result.getDiscountAmount());
        assertEquals(new BigDecimal("75.00"), result.getFinalAmount());
    }

    @Test
    void givenDiscountResult_whenRuleNamesAreRead_thenResultIsImmutable() {
        // Arrange
        DiscountContext context = new DiscountContext(
                CustomerType.PREMIUM,
                new BigDecimal("100.00"),
                1);
        DiscountEngine engine = new DiscountEngine(List.of(DiscountRule.premiumCustomer()));
        DiscountResult result = engine.evaluate(context);

        // Act / Assert
        assertThrows(
                UnsupportedOperationException.class,
                () -> result.getAppliedRuleNames().add("Tampered"));
        assertEquals(List.of(DiscountRule.PREMIUM_CUSTOMER), result.getAppliedRuleNames());
    }

    @Test
    void givenNullCustomerType_whenDiscountContextIsCreated_thenThrowsInvalidCustomerDataException() {
        // Arrange / Act
        InvalidCustomerDataException exception = assertThrows(
                InvalidCustomerDataException.class,
                () -> new DiscountContext(null, new BigDecimal("100.00"), 1));

        // Assert
        assertTrue(exception.getMessage().contains("customer type"));
    }

    @Test
    void givenNullOriginalAmount_whenDiscountContextIsCreated_thenThrowsInvalidMonetaryValueException() {
        // Arrange / Act
        InvalidMonetaryValueException exception = assertThrows(
                InvalidMonetaryValueException.class,
                () -> new DiscountContext(CustomerType.REGULAR, null, 1));

        // Assert
        assertTrue(exception.getMessage().contains("original amount"));
    }

    @Test
    void givenNegativeOriginalAmount_whenDiscountContextIsCreated_thenThrowsInvalidMonetaryValueException() {
        // Arrange / Act
        InvalidMonetaryValueException exception = assertThrows(
                InvalidMonetaryValueException.class,
                () -> new DiscountContext(CustomerType.REGULAR, new BigDecimal("-0.01"), 1));

        // Assert
        assertTrue(exception.getMessage().contains("original amount"));
    }

    @Test
    void givenNegativeTotalQuantity_whenDiscountContextIsCreated_thenThrowsInvalidOrderException() {
        // Arrange / Act
        InvalidOrderException exception = assertThrows(
                InvalidOrderException.class,
                () -> new DiscountContext(CustomerType.REGULAR, new BigDecimal("100.00"), -1));

        // Assert
        assertTrue(exception.getMessage().contains("total quantity"));
    }

    @Test
    void givenNullNamedRateName_whenCreated_thenThrowsIllegalArgumentException() {
        // Arrange / Act
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new DiscountRule.NamedRate(null, BigDecimal.ZERO));

        // Assert
        assertTrue(exception.getMessage().contains("name"));
    }

    @Test
    void givenBlankNamedRateName_whenCreated_thenThrowsIllegalArgumentException() {
        // Arrange / Act
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new DiscountRule.NamedRate("  ", BigDecimal.ZERO));

        // Assert
        assertTrue(exception.getMessage().contains("name"));
    }

    @Test
    void givenNullNamedRateRate_whenCreated_thenThrowsInvalidMonetaryValueException() {
        // Arrange / Act
        InvalidMonetaryValueException exception = assertThrows(
                InvalidMonetaryValueException.class,
                () -> new DiscountRule.NamedRate("Custom", null));

        // Assert
        assertTrue(exception.getMessage().contains("rate"));
    }

    @Test
    void givenNegativeNamedRateRate_whenCreated_thenThrowsInvalidMonetaryValueException() {
        // Arrange / Act
        InvalidMonetaryValueException exception = assertThrows(
                InvalidMonetaryValueException.class,
                () -> new DiscountRule.NamedRate("Custom", new BigDecimal("-0.01")));

        // Assert
        assertTrue(exception.getMessage().contains("rate"));
    }

    @Test
    void givenRuleReturningNullNamedRate_whenEvaluated_thenThrowsIllegalArgumentException() {
        // Arrange
        DiscountContext context = new DiscountContext(
                CustomerType.REGULAR,
                new BigDecimal("100.00"),
                1);
        DiscountEngine engine = new DiscountEngine(List.of(unused -> null));

        // Act
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> engine.evaluate(context));

        // Assert
        assertTrue(exception.getMessage().contains("NamedRate"));
    }

    @Test
    void givenNullDiscountContext_whenEvaluated_thenThrowsIllegalArgumentException() {
        // Arrange
        DiscountEngine engine = new DiscountEngine(List.of(DiscountRule.regularCustomer()));

        // Act
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> engine.evaluate(null));

        // Assert
        assertTrue(exception.getMessage().contains("context"));
    }

    @Test
    void givenMutableRuleList_whenEngineIsCreated_thenCallerMutationDoesNotAffectEngine() {
        // Arrange
        ArrayList<DiscountRule> rules = new ArrayList<>();
        rules.add(DiscountRule.premiumCustomer());
        DiscountEngine engine = new DiscountEngine(rules);
        DiscountContext context = new DiscountContext(
                CustomerType.PREMIUM,
                new BigDecimal("100.00"),
                1);

        // Act
        rules.clear();
        DiscountResult result = engine.evaluate(context);

        // Assert
        assertEquals(List.of(DiscountRule.PREMIUM_CUSTOMER), result.getAppliedRuleNames());
        assertEquals(new BigDecimal("5.00"), result.getDiscountAmount());
    }
}
