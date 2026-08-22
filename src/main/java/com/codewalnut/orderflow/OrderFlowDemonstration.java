package com.codewalnut.orderflow;

import com.codewalnut.orderflow.core.domain.catalog.Product;
import com.codewalnut.orderflow.core.domain.customer.Customer;
import com.codewalnut.orderflow.core.domain.customer.CustomerType;
import com.codewalnut.orderflow.core.domain.order.Order;
import com.codewalnut.orderflow.core.domain.order.OrderRequest;
import com.codewalnut.orderflow.core.domain.order.OrderStatus;
import com.codewalnut.orderflow.core.domain.order.RequestedProduct;
import com.codewalnut.orderflow.core.domain.pricing.DiscountRule;
import com.codewalnut.orderflow.core.exception.InvalidOrderException;
import com.codewalnut.orderflow.core.service.audit.AuditLog;
import com.codewalnut.orderflow.core.service.catalog.ProductCatalog;
import com.codewalnut.orderflow.core.service.customer.CustomerDirectory;
import com.codewalnut.orderflow.core.service.inventory.Inventory;
import com.codewalnut.orderflow.core.service.notification.ConsoleNotificationChannel;
import com.codewalnut.orderflow.core.service.notification.EmailNotificationChannel;
import com.codewalnut.orderflow.core.service.order.OrderFactory;
import com.codewalnut.orderflow.core.service.order.validation.OrderValidationPipeline;
import com.codewalnut.orderflow.core.service.order.validation.OrderValidationRule;
import com.codewalnut.orderflow.core.service.payment.ConfigurableFailurePaymentGateway;
import com.codewalnut.orderflow.core.service.pricing.DiscountEngine;
import com.codewalnut.orderflow.core.service.processing.OrderProcessor;
import com.codewalnut.orderflow.core.service.reporting.OrderReporter;

import java.io.PrintWriter;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class OrderFlowDemonstration {
    private static final String[] CATEGORIES = {"Tools", "Garden", "Kitchen", "Sports"};
    private static final CustomerType[] CUSTOMER_TYPES = CustomerType.values();

    private final PrintWriter out;

    public OrderFlowDemonstration(PrintWriter out) {
        this.out = Objects.requireNonNull(out, "out must not be null");
    }

    public DemonstrationResult run() {
        Inventory inventory = new Inventory();
        ProductCatalog catalog = new ProductCatalog(inventory);
        CustomerDirectory customers = new CustomerDirectory();
        OrderValidationPipeline pipeline = new OrderValidationPipeline(List.of(
                OrderValidationRule.nonEmptyRequest(),
                OrderValidationRule.positiveQuantities(),
                OrderValidationRule.customerExists(),
                OrderValidationRule.productExists(),
                OrderValidationRule.activeProducts(),
                OrderValidationRule.availableStock()));
        AuditLog auditLog = new AuditLog();
        OrderFactory factory = new OrderFactory(customers, catalog, inventory, pipeline, auditLog);
        DiscountEngine discounts = new DiscountEngine(List.of(
                DiscountRule.regularCustomer(),
                DiscountRule.premiumCustomer(),
                DiscountRule.corporateCustomer(),
                DiscountRule.bulkQuantity(),
                DiscountRule.highValue()));
        seedCatalog(catalog);
        seedCustomers(customers);

        Set<String> paymentFailures = Set.of("O-48", "O-49");
        OrderProcessor processor = new OrderProcessor(
                catalog,
                customers,
                inventory,
                pipeline,
                discounts,
                new ConfigurableFailurePaymentGateway(paymentFailures),
                List.of(new ConsoleNotificationChannel(), new EmailNotificationChannel()),
                auditLog);
        int invalidCreationCount = 0;
        List<Order> acceptedOrders = new ArrayList<>();
        int attemptedOrderCount = 50;
        for (int i = 1; i <= attemptedOrderCount; i++) {
            try {
                acceptedOrders.add(factory.create(orderId(i), requestFor(i)));
            } catch (InvalidOrderException exception) {
                invalidCreationCount++;
                out.println("Invalid order " + orderId(i) + ": " + exception.getMessage());
            }
        }

        CountDownLatch submitted = new CountDownLatch(acceptedOrders.size());
        List<RuntimeException> submitFailures = new CopyOnWriteArrayList<>();
        ExecutorService submitter = Executors.newFixedThreadPool(8);
        try {
            for (Order order : acceptedOrders) {
                submitter.execute(() -> {
                    try {
                        processor.submit(order);
                    } catch (RuntimeException exception) {
                        submitFailures.add(exception);
                    } finally {
                        submitted.countDown();
                    }
                });
            }
            if (!submitted.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out submitting demonstration orders");
            }
            processor.awaitIdle(Duration.ofSeconds(15));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while running demonstration", exception);
        } finally {
            submitter.shutdown();
            processor.shutdown();
        }
        if (!submitFailures.isEmpty()) {
            throw submitFailures.getFirst();
        }

        printResults(catalog, customers, inventory, processor, auditLog);
        long completed = processor.snapshotOrders().stream()
                .filter(order -> order.getStatus() == OrderStatus.COMPLETED)
                .count();
        long failed = processor.snapshotOrders().stream()
                .filter(order -> order.getStatus() == OrderStatus.FAILED)
                .count();
        return new DemonstrationResult(
                15,
                CATEGORIES.length,
                10,
                attemptedOrderCount,
                acceptedOrders.size(),
                (int) completed,
                (int) failed,
                invalidCreationCount,
                processor.isShutdown());
    }

    private void seedCatalog(ProductCatalog catalog) {
        for (int i = 1; i <= 15; i++) {
            String category = CATEGORIES[(i - 1) % CATEGORIES.length];
            int initialQuantity = i == 1 ? 5 : 40;
            catalog.add(
                    new Product(
                            productId(i),
                            "Product " + i,
                            category,
                            new BigDecimal(i + ".99"),
                            Set.of(category.toLowerCase(), "demo"),
                            3),
                    initialQuantity);
        }
    }

    private void seedCustomers(CustomerDirectory customers) {
        for (int i = 1; i <= 10; i++) {
            customers.register(new Customer(
                    customerId(i),
                    "Customer " + i,
                    "customer" + i + "@example.com",
                    CUSTOMER_TYPES[(i - 1) % CUSTOMER_TYPES.length]));
        }
    }

    private OrderRequest requestFor(int sequence) {
        if (sequence == 1) {
            return new OrderRequest(customerId(1), List.of());
        }
        if (sequence == 2) {
            return new OrderRequest("missing-customer", List.of(new RequestedProduct(productId(2), 1)));
        }
        if (sequence <= 12) {
            return new OrderRequest(customerId(1), List.of(new RequestedProduct(productId(1), 1)));
        }
        return new OrderRequest(
                customerId(((sequence - 1) % 10) + 1),
                List.of(new RequestedProduct(productId(((sequence - 1) % 15) + 1), 1)));
    }

    private void printResults(
            ProductCatalog catalog,
            CustomerDirectory customers,
            Inventory inventory,
            OrderProcessor processor,
            AuditLog auditLog) {
        OrderReporter reporter = new OrderReporter();
        List<Order> orders = processor.snapshotOrders();
        out.println("Order summaries:");
        orders.forEach(order -> out.println(
                "  " + order.getId() + " " + order.getStatus()
                        + " original=" + order.getOriginalAmount()
                        + " final=" + order.getFinalAmount().orElse(null)));
        out.println("Inventory snapshot: " + inventory.snapshot());
        out.println("Audit events:");
        auditLog.allEvents().forEach(event -> out.println(
                "  " + event.id() + " " + event.orderId() + " " + event.type()
                        + " " + event.message()
                        + " " + event.timestamp()
                        + " " + event.threadName()));
        out.println("Completed revenue: " + reporter.completedRevenue(orders));
        out.println("Revenue by category: " + reporter.revenueByCategory(orders, catalog));
        out.println("Orders by status: " + reporter.ordersByStatus(orders));
        out.println("Spending by customer: " + reporter.spendingByCustomer(orders));
        out.println("Top customers: " + reporter.topFiveCustomers(orders));
        out.println("Top products: " + reporter.topFiveProducts(orders));
        out.println("Average completed order value: " + reporter.averageCompletedOrderValue(orders));
        out.println("Completed orders by day: " + reporter.completedOrdersByDay(orders));
        out.println("Failures by reason: " + reporter.failuresByReason(orders));
        out.println("Low stock: " + reporter.lowStock(catalog).stream().map(Product::getId).toList());
        out.println("Unique tags: " + reporter.uniqueTagsAlphabetically(catalog));
        out.println("Highest value by customer type: "
                + reporter.highestValueCompletedOrderByCustomerType(orders, customers));
        out.flush();
    }

    private static String productId(int sequence) {
        return "P-" + String.format("%02d", sequence);
    }

    private static String customerId(int sequence) {
        return "C-" + String.format("%02d", sequence);
    }

    private static String orderId(int sequence) {
        return "O-" + String.format("%02d", sequence);
    }
}
