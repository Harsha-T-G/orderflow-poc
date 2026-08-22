package com.codewalnut.orderflow.core.service.notification;

import com.codewalnut.orderflow.core.domain.catalog.Product;
import com.codewalnut.orderflow.core.domain.customer.Customer;
import com.codewalnut.orderflow.core.domain.customer.CustomerType;
import com.codewalnut.orderflow.core.domain.order.Order;
import com.codewalnut.orderflow.core.domain.order.OrderRequest;
import com.codewalnut.orderflow.core.domain.order.OrderStatus;
import com.codewalnut.orderflow.core.domain.order.RequestedProduct;
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
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NotificationChannelTest {

    @Test
    void givenConsoleChannel_whenNotified_thenOrderStateIsUnchanged() {
        // Arrange
        Order order = completedOrder("N-1");
        NotificationChannel channel = new ConsoleNotificationChannel();

        // Act
        channel.notify(order);

        // Assert
        assertEquals(OrderStatus.COMPLETED, order.getStatus());
        assertEquals(new BigDecimal("20.00"), order.getFinalAmount().orElseThrow());
    }

    @Test
    void givenFailingChannel_whenNotified_thenExceptionDoesNotChangeOrderState() {
        // Arrange
        Order order = completedOrder("N-2");
        AtomicBoolean attempted = new AtomicBoolean();
        NotificationChannel channel = ignoredOrder -> {
            attempted.set(true);
            throw new IllegalStateException("smtp unavailable");
        };

        // Act
        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> channel.notify(order));

        // Assert
        assertTrue(attempted.get());
        assertTrue(exception.getMessage().contains("smtp unavailable"));
        assertEquals(OrderStatus.COMPLETED, order.getStatus());
    }

    private static Order completedOrder(String orderId) {
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
        Order order = factory.create(orderId, new OrderRequest("C-100", List.of(new RequestedProduct("P-1", 2))));
        order.queue();
        order.startProcessing();
        order.complete(new BigDecimal("0.00"), new BigDecimal("20.00"));
        return order;
    }
}
