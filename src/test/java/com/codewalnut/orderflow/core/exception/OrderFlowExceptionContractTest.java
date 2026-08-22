package com.codewalnut.orderflow.core.exception;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderFlowExceptionContractTest {

    @Test
    void givenEachRequiredException_whenConstructed_thenMessageContainsUsefulContext() {
        // Arrange / Act
        OrderFlowException[] exceptions = {
                new InvalidProductDataException("Product id must not be blank"),
                new DuplicateProductException("P-1"),
                new ProductNotFoundException("P-missing"),
                new InactiveProductException("P-1"),
                new InvalidCustomerDataException("Customer email must not be blank"),
                new DuplicateCustomerException("Customer C-1 already exists"),
                new CustomerNotFoundException("C-missing"),
                new InvalidOrderException("Order request must contain at least one product"),
                new DuplicateOrderSubmissionException("O-1"),
                new InvalidOrderStatusTransitionException("Order O-1 cannot transition from CREATED to PROCESSING"),
                new InsufficientStockException("P-1", 5, 2),
                new PaymentFailedException("O-1"),
                new InvalidMonetaryValueException("Product price must be positive: 0")
        };

        // Assert
        for (OrderFlowException exception : exceptions) {
            assertTrue(exception.getMessage() != null && !exception.getMessage().isBlank(), exception.getClass().getSimpleName());
        }
        assertTrue(new DuplicateProductException("P-1").getMessage().contains("P-1"));
        assertTrue(new InactiveProductException("P-1").getMessage().contains("P-1"));
        assertTrue(new InsufficientStockException("P-1", 5, 2).getMessage().contains("5"));
        assertTrue(new DuplicateOrderSubmissionException("O-1").getMessage().contains("O-1"));
        assertTrue(new PaymentFailedException("O-1").getMessage().contains("O-1"));
    }

    @Test
    void givenUnderlyingFailure_whenPaymentFailedExceptionIsTranslated_thenCauseIsPreserved() {
        // Arrange
        IllegalStateException cause = new IllegalStateException("gateway panic");

        // Act
        PaymentFailedException translated = new PaymentFailedException("BOOM-1", cause);

        // Assert
        assertEquals("Payment failed for order BOOM-1", translated.getMessage());
        assertSame(cause, translated.getCause());
    }
}
