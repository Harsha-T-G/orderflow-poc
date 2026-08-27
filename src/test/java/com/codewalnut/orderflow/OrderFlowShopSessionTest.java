package com.codewalnut.orderflow;

import org.junit.jupiter.api.Test;

import com.codewalnut.orderflow.core.domain.customer.Customer;
import com.codewalnut.orderflow.core.domain.customer.CustomerType;
import com.codewalnut.orderflow.core.service.customer.CustomerDirectory;

import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderFlowShopSessionTest {

    @Test
    void givenCustomerProductChoiceAndCheckout_whenShopSessionRuns_thenOrderCompletesWithoutAskingForOrderId() {
        // Arrange
        String input = """
                zara@example.com
                Zara Khan
                1
                13 1
                0
                n
                """;
        StringWriter output = new StringWriter();
        OrderFlowShopSession session = new OrderFlowShopSession(
                new StringReader(input), new PrintWriter(output, true));

        // Act
        session.run();

        // Assert
        String printed = output.toString();
        assertTrue(printed.contains("OrderFlow shop"));
        assertTrue(printed.contains("Product 13"));
        assertTrue(printed.contains("COMPLETED"));
        assertTrue(printed.contains("Reference O-SHOP-"));
        assertTrue(printed.contains("paid 13.99") || printed.contains("paid "));
    }

    @Test
    void givenTwoCheckouts_whenShopSessionRuns_thenGeneratesTwoOrderReferences() {
        // Arrange
        String input = """
                zara@example.com
                Zara Khan
                1
                13 1
                0
                y
                14 1
                0
                n
                """;
        StringWriter output = new StringWriter();
        OrderFlowShopSession session = new OrderFlowShopSession(
                new StringReader(input), new PrintWriter(output, true));

        // Act
        session.run();

        // Assert
        String printed = output.toString();
        assertTrue(printed.contains("Reference O-SHOP-001"));
        assertTrue(printed.contains("Reference O-SHOP-002"));
    }

    @Test
    void givenKnownEmailInDirectory_whenShopSessionRuns_thenWelcomesBackWithoutRegisteringAgain() {
        // Arrange
        CustomerDirectory customers = new CustomerDirectory();
        customers.register(new Customer(
                "C-SHOP-001", "Alice Example", "alice@example.com", CustomerType.REGULAR));
        String input = """
                alice@example.com
                13 1
                0
                n
                """;
        StringWriter output = new StringWriter();
        OrderFlowShopSession session = new OrderFlowShopSession(
                new StringReader(input), new PrintWriter(output, true), customers);

        // Act
        session.run();

        // Assert
        String printed = output.toString();
        assertTrue(printed.contains("Welcome back, Alice Example (C-SHOP-001)"));
        assertTrue(printed.contains("Reference O-SHOP-001"));
    }
}
