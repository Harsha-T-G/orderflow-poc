package com.codewalnut.orderflow.core.service.processing;

import com.codewalnut.orderflow.core.domain.audit.AuditEventType;
import com.codewalnut.orderflow.core.domain.catalog.Product;
import com.codewalnut.orderflow.core.domain.customer.Customer;
import com.codewalnut.orderflow.core.domain.customer.CustomerType;
import com.codewalnut.orderflow.core.domain.order.Order;
import com.codewalnut.orderflow.core.domain.order.OrderRequest;
import com.codewalnut.orderflow.core.domain.order.OrderStatus;
import com.codewalnut.orderflow.core.domain.order.RequestedProduct;
import com.codewalnut.orderflow.core.domain.pricing.DiscountRule;
import com.codewalnut.orderflow.core.exception.DuplicateOrderSubmissionException;
import com.codewalnut.orderflow.core.service.audit.AuditLog;
import com.codewalnut.orderflow.core.service.catalog.ProductCatalog;
import com.codewalnut.orderflow.core.service.customer.CustomerDirectory;
import com.codewalnut.orderflow.core.service.inventory.Inventory;
import com.codewalnut.orderflow.core.service.notification.NotificationChannel;
import com.codewalnut.orderflow.core.service.order.OrderFactory;
import com.codewalnut.orderflow.core.service.order.validation.OrderValidationPipeline;
import com.codewalnut.orderflow.core.service.order.validation.OrderValidationRule;
import com.codewalnut.orderflow.core.service.payment.AlwaysSuccessfulPaymentGateway;
import com.codewalnut.orderflow.core.service.payment.ConfigurableFailurePaymentGateway;
import com.codewalnut.orderflow.core.service.payment.PaymentGateway;
import com.codewalnut.orderflow.core.service.pricing.DiscountEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderProcessorTest {

    private OrderProcessor processor;

    @AfterEach
    void shutDownProcessor() {
        if (processor != null) {
            processor.shutdown();
        }
    }

    @Test
    void givenCreatedOrder_whenSubmittedAndProcessed_thenCompletesWithPricingAndReservedStock() throws Exception {
        // Arrange
        Fixture fixture = Fixture.premiumCatalog();
        processor = fixture.processor(new AlwaysSuccessfulPaymentGateway(), List.of());
        Order order = fixture.createOrder("ORD-1", "C-PREM", "P-1", 2);

        // Act
        processor.submit(order);
        processor.awaitIdle(Duration.ofSeconds(5));

        // Assert
        assertEquals(OrderStatus.COMPLETED, order.getStatus());
        assertEquals(new BigDecimal("1.00"), order.getDiscountAmount().orElseThrow());
        assertEquals(new BigDecimal("19.00"), order.getFinalAmount().orElseThrow());
        assertEquals(8, fixture.inventory.availableQuantity("P-1"));
        assertTrue(fixture.audit.eventsFor("ORD-1").stream()
                .anyMatch(event -> event.type() == AuditEventType.CREATED));
        assertTrue(fixture.audit.eventsFor("ORD-1").stream()
                .anyMatch(event -> event.type() == AuditEventType.COMPLETED));
    }

    @Test
    void givenDuplicateOrderId_whenSubmitted_thenThrowsAndProcessesAtMostOnce() throws Exception {
        // Arrange
        Fixture fixture = Fixture.premiumCatalog();
        processor = fixture.processor(new AlwaysSuccessfulPaymentGateway(), List.of());
        Order first = fixture.createOrder("DUP-1", "C-PREM", "P-1", 1);
        Order second = fixture.createOrder("DUP-1", "C-PREM", "P-1", 1);

        // Act
        processor.submit(first);
        DuplicateOrderSubmissionException exception = assertThrows(
                DuplicateOrderSubmissionException.class,
                () -> processor.submit(second));
        processor.awaitIdle(Duration.ofSeconds(5));

        // Assert
        assertTrue(exception.getMessage().contains("DUP-1"));
        assertEquals(OrderStatus.COMPLETED, first.getStatus());
        assertEquals(OrderStatus.CREATED, second.getStatus());
        assertEquals(9, fixture.inventory.availableQuantity("P-1"));
    }

    @Test
    void givenCancelledQueuedOrder_whenWorkerDequeues_thenSkippedWithoutStockChange() throws Exception {
        // Arrange
        Fixture fixture = Fixture.premiumCatalog();
        processor = fixture.processor(new AlwaysSuccessfulPaymentGateway(), List.of(), false);
        Order order = fixture.createOrder("CAN-1", "C-PREM", "P-1", 2);
        processor.submit(order);
        order.cancel();
        processor.start();

        // Act
        processor.awaitIdle(Duration.ofSeconds(5));

        // Assert
        assertEquals(OrderStatus.CANCELLED, order.getStatus());
        assertEquals(10, fixture.inventory.availableQuantity("P-1"));
        assertTrue(fixture.audit.eventsFor("CAN-1").stream()
                .anyMatch(event -> event.type() == AuditEventType.SKIPPED));
    }

    @Test
    void givenPaymentFailure_whenProcessed_thenReservationIsReleasedAndOrderFails() throws Exception {
        // Arrange
        Fixture fixture = Fixture.premiumCatalog();
        processor = fixture.processor(new ConfigurableFailurePaymentGateway(Set.of("PAY-1")), List.of());
        Order order = fixture.createOrder("PAY-1", "C-PREM", "P-1", 3);

        // Act
        processor.submit(order);
        processor.awaitIdle(Duration.ofSeconds(5));

        // Assert
        assertEquals(OrderStatus.FAILED, order.getStatus());
        assertTrue(order.getFailureReason().orElseThrow().toLowerCase().contains("payment"));
        assertEquals(10, fixture.inventory.availableQuantity("P-1"));
        assertTrue(fixture.audit.eventsFor("PAY-1").stream()
                .anyMatch(event -> event.type() == AuditEventType.RELEASE));
        assertTrue(fixture.audit.eventsFor("PAY-1").stream()
                .anyMatch(event -> event.type() == AuditEventType.FAILED));
    }

    @Test
    void givenFailingNotificationChannel_whenOrderCompletes_thenFinalStateDoesNotChange() throws Exception {
        // Arrange
        Fixture fixture = Fixture.premiumCatalog();
        List<String> successful = new CopyOnWriteArrayList<>();
        NotificationChannel failing = order -> {
            throw new IllegalStateException("email down");
        };
        NotificationChannel succeeding = order -> successful.add(order.getId());
        processor = fixture.processor(new AlwaysSuccessfulPaymentGateway(), List.of(failing, succeeding));
        Order order = fixture.createOrder("NOTE-1", "C-PREM", "P-1", 1);

        // Act
        processor.submit(order);
        processor.awaitIdle(Duration.ofSeconds(5));

        // Assert
        assertEquals(OrderStatus.COMPLETED, order.getStatus());
        assertEquals(List.of("NOTE-1"), successful);
        assertTrue(fixture.audit.eventsFor("NOTE-1").stream()
                .anyMatch(event -> event.type() == AuditEventType.NOTIFICATION
                        && event.message().toLowerCase().contains("email down")));
    }

    @RepeatedTest(3)
    void givenLimitedStock_whenManyOrdersAreSubmittedConcurrently_thenSoldQuantityNeverExceedsStock() throws Exception {
        // Arrange
        Fixture fixture = Fixture.premiumCatalog();
        processor = fixture.processor(new AlwaysSuccessfulPaymentGateway(), List.of());
        int orderCount = 20;
        List<Order> orders = new ArrayList<>();
        for (int i = 0; i < orderCount; i++) {
            orders.add(fixture.createOrder("CONT-" + i, "C-PREM", "P-1", 1));
        }
        CyclicBarrier start = new CyclicBarrier(orderCount);
        CountDownLatch submitted = new CountDownLatch(orderCount);

        // Act
        for (Order order : orders) {
            Thread thread = new Thread(() -> {
                try {
                    start.await(2, TimeUnit.SECONDS);
                    processor.submit(order);
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                } finally {
                    submitted.countDown();
                }
            });
            thread.start();
        }
        assertTrue(submitted.await(5, TimeUnit.SECONDS));
        processor.awaitIdle(Duration.ofSeconds(5));

        // Assert
        long completed = orders.stream().filter(order -> order.getStatus() == OrderStatus.COMPLETED).count();
        long failed = orders.stream().filter(order -> order.getStatus() == OrderStatus.FAILED).count();
        assertEquals(orderCount, completed + failed);
        assertEquals(10, completed);
        assertEquals(0, fixture.inventory.availableQuantity("P-1"));
        assertTrue(orders.stream().noneMatch(order -> order.getStatus() == OrderStatus.PROCESSING));
        assertTrue(orders.stream().noneMatch(order -> order.getStatus() == OrderStatus.QUEUED));
    }

    @Test
    void givenWorkerFailure_whenOneOrderThrows_thenOtherOrdersStillComplete() throws Exception {
        // Arrange
        Fixture fixture = Fixture.premiumCatalog();
        AtomicInteger charges = new AtomicInteger();
        PaymentGateway explodingThenSucceeding = (order, amount) -> {
            if (order.getId().equals("BOOM-1")) {
                throw new IllegalStateException("gateway panic");
            }
            charges.incrementAndGet();
        };
        processor = fixture.processor(explodingThenSucceeding, List.of());
        Order exploding = fixture.createOrder("BOOM-1", "C-PREM", "P-1", 1);
        Order surviving = fixture.createOrder("OK-1", "C-PREM", "P-1", 1);

        // Act
        processor.submit(exploding);
        processor.submit(surviving);
        processor.awaitIdle(Duration.ofSeconds(5));

        // Assert
        assertEquals(OrderStatus.FAILED, exploding.getStatus());
        assertEquals(OrderStatus.COMPLETED, surviving.getStatus());
        assertEquals(1, charges.get());
        assertEquals(9, fixture.inventory.availableQuantity("P-1"));
    }

    @Test
    void givenAcceptedWork_whenShutdownRequested_thenExecutorsTerminate() throws Exception {
        // Arrange
        Fixture fixture = Fixture.premiumCatalog();
        CountDownLatch paymentStarted = new CountDownLatch(1);
        CountDownLatch allowPayment = new CountDownLatch(1);
        PaymentGateway gated = (order, amount) -> {
            paymentStarted.countDown();
            try {
                assertTrue(allowPayment.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting to complete payment", exception);
            }
        };
        processor = fixture.processor(gated, List.of());
        Order order = fixture.createOrder("STOP-1", "C-PREM", "P-1", 1);
        processor.submit(order);
        assertTrue(paymentStarted.await(5, TimeUnit.SECONDS));

        // Act
        allowPayment.countDown();
        processor.shutdown();

        // Assert
        assertTrue(processor.isShutdown());
        assertEquals(OrderStatus.COMPLETED, order.getStatus());
        assertThrows(IllegalStateException.class, () -> processor.submit(
                fixture.createOrder("STOP-2", "C-PREM", "P-1", 1)));
    }

    private static final class Fixture {
        private final Inventory inventory = new Inventory();
        private final ProductCatalog catalog = new ProductCatalog(inventory);
        private final CustomerDirectory customers = new CustomerDirectory();
        private final AuditLog audit = new AuditLog();
        private final OrderFactory factory;
        private final DiscountEngine discounts = new DiscountEngine(List.of(
                DiscountRule.regularCustomer(),
                DiscountRule.premiumCustomer(),
                DiscountRule.corporateCustomer(),
                DiscountRule.bulkQuantity(),
                DiscountRule.highValue()));
        private final OrderValidationPipeline pipeline = new OrderValidationPipeline(List.of(
                OrderValidationRule.nonEmptyRequest(),
                OrderValidationRule.positiveQuantities(),
                OrderValidationRule.customerExists(),
                OrderValidationRule.productExists(),
                OrderValidationRule.activeProducts(),
                OrderValidationRule.availableStock()));

        private Fixture() {
            customers.register(new Customer("C-PREM", "Pat Premium", "pat@example.com", CustomerType.PREMIUM));
            catalog.add(new Product("P-1", "Widget", "Tools", new BigDecimal("10.00"), Set.of("metal"), 2), 10);
            factory = new OrderFactory(customers, catalog, inventory, pipeline, audit);
        }

        static Fixture premiumCatalog() {
            return new Fixture();
        }

        Order createOrder(String orderId, String customerId, String productId, int quantity) {
            return factory.create(orderId, new OrderRequest(customerId, List.of(new RequestedProduct(productId, quantity))));
        }

        OrderProcessor processor(
                PaymentGateway paymentGateway,
                List<NotificationChannel> channels) {
            return processor(paymentGateway, channels, true);
        }

        OrderProcessor processor(
                PaymentGateway paymentGateway,
                List<NotificationChannel> channels,
                boolean startWorkers) {
            return new OrderProcessor(
                    catalog,
                    customers,
                    inventory,
                    pipeline,
                    discounts,
                    paymentGateway,
                    channels,
                    audit,
                    OrderProcessor.WORKER_COUNT,
                    startWorkers);
        }
    }
}
