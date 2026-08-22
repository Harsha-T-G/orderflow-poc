package com.codewalnut.orderflow.core.service.payment;

import com.codewalnut.orderflow.core.domain.catalog.Product;
import com.codewalnut.orderflow.core.domain.customer.Customer;
import com.codewalnut.orderflow.core.domain.customer.CustomerType;
import com.codewalnut.orderflow.core.domain.order.Order;
import com.codewalnut.orderflow.core.domain.order.OrderRequest;
import com.codewalnut.orderflow.core.domain.order.RequestedProduct;
import com.codewalnut.orderflow.core.exception.PaymentFailedException;
import com.codewalnut.orderflow.core.service.catalog.ProductCatalog;
import com.codewalnut.orderflow.core.service.customer.CustomerDirectory;
import com.codewalnut.orderflow.core.service.inventory.Inventory;
import com.codewalnut.orderflow.core.service.order.OrderFactory;
import com.codewalnut.orderflow.core.service.order.validation.OrderValidationPipeline;
import com.codewalnut.orderflow.core.service.order.validation.OrderValidationRule;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaymentGatewayTest {

    @Test
    void givenAlwaysSuccessfulGateway_whenCharged_thenDoesNotThrow() {
        // Arrange
        PaymentGateway gateway = new AlwaysSuccessfulPaymentGateway();
        Order order = createOrder("PAY-1");

        // Act / Assert
        assertDoesNotThrow(() -> gateway.charge(order, new BigDecimal("20.00")));
    }

    @Test
    void givenConfiguredFailure_whenCharged_thenThrowsPaymentFailedException() {
        // Arrange
        PaymentGateway gateway = new ConfigurableFailurePaymentGateway(Set.of("PAY-FAIL"));
        Order order = createOrder("PAY-FAIL");

        // Act
        PaymentFailedException exception = assertThrows(
                PaymentFailedException.class,
                () -> gateway.charge(order, new BigDecimal("20.00")));

        // Assert
        assertTrue(exception.getMessage().contains("PAY-FAIL"));
    }

    private static Order createOrder(String orderId) {
        Inventory inventory = new Inventory();
        ProductCatalog catalog = new ProductCatalog(inventory);
        CustomerDirectory customers = new CustomerDirectory();
        customers.register(new Customer("C-100", "Alice Example", "alice@example.com", CustomerType.REGULAR));
        catalog.add(new Product("P-1", "Widget", "Tools", new BigDecimal("10.00"), Set.of("metal"), 2), 20);
        OrderFactory factory = new OrderFactory(
                customers,
                catalog,
                inventory,
                new OrderValidationPipeline(List.of(
                        OrderValidationRule.nonEmptyRequest(),
                        OrderValidationRule.positiveQuantities(),
                        OrderValidationRule.customerExists(),
                        OrderValidationRule.productExists(),
                        OrderValidationRule.activeProducts(),
                        OrderValidationRule.availableStock())));
        return factory.create(orderId, new OrderRequest("C-100", List.of(new RequestedProduct("P-1", 2))));
    }
}
