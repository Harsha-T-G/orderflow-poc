package com.codewalnut.orderflow;

import com.codewalnut.orderflow.core.domain.audit.AuditEvent;
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
import com.codewalnut.orderflow.core.service.notification.NotificationDispatcher;
import com.codewalnut.orderflow.core.service.order.OrderFactory;
import com.codewalnut.orderflow.core.service.order.validation.OrderValidationPipeline;
import com.codewalnut.orderflow.core.service.order.validation.OrderValidationRule;
import com.codewalnut.orderflow.core.service.payment.ConfigurableFailurePaymentGateway;
import com.codewalnut.orderflow.core.service.pricing.DiscountEngine;
import com.codewalnut.orderflow.core.service.processing.OrderProcessor;
import com.codewalnut.orderflow.core.service.reporting.OrderReporter;

import java.io.PrintWriter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class OrderFlowDemonstration {
    private static final int PRODUCT_COUNT = 15;
    private static final int CUSTOMER_COUNT = 10;
    private static final int ATTEMPTED_ORDER_COUNT = 50;
    private static final int SUBMITTER_THREAD_COUNT = 8;
    private static final Duration SUBMISSION_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration PROCESSING_TIMEOUT = Duration.ofSeconds(15);
    private static final String[] CATEGORIES = {"Tools", "Garden", "Kitchen", "Sports"};
    private static final CustomerType[] CUSTOMER_TYPES = CustomerType.values();
    private static final Set<String> PAYMENT_FAILURE_ORDER_IDS = Set.of("O-48", "O-49");
    private static final List<String> DEMO_LOGGER_NAMES = List.of(
            ConfigurableFailurePaymentGateway.class.getName(),
            ConsoleNotificationChannel.class.getName(),
            EmailNotificationChannel.class.getName(),
            NotificationDispatcher.class.getName(),
            OrderProcessor.class.getName());

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

        List<Level> previousLogLevels = quietDemoLogs();
        try {
            OrderProcessor processor = new OrderProcessor(
                    catalog,
                    customers,
                    inventory,
                    pipeline,
                    discounts,
                    new ConfigurableFailurePaymentGateway(PAYMENT_FAILURE_ORDER_IDS),
                    List.of(new ConsoleNotificationChannel(), new EmailNotificationChannel()),
                    auditLog);
            List<Order> acceptedOrders = new ArrayList<>();
            int invalidCreationCount = createOrders(factory, acceptedOrders);
            submitAcceptedOrders(processor, acceptedOrders);
            printResults(catalog, customers, inventory, processor, auditLog);
            return summarize(processor, acceptedOrders.size(), invalidCreationCount);
        } finally {
            restoreDemoLogs(previousLogLevels);
        }
    }

    private int createOrders(OrderFactory factory, List<Order> acceptedOrders) {
        int invalidCreationCount = 0;
        for (int orderIndex = 1; orderIndex <= ATTEMPTED_ORDER_COUNT; orderIndex++) {
            try {
                acceptedOrders.add(factory.create(orderId(orderIndex), requestFor(orderIndex)));
            } catch (InvalidOrderException exception) {
                invalidCreationCount++;
            }
        }
        return invalidCreationCount;
    }

    private void submitAcceptedOrders(OrderProcessor processor, List<Order> acceptedOrders) {
        CountDownLatch submitted = new CountDownLatch(acceptedOrders.size());
        List<RuntimeException> submitFailures = new CopyOnWriteArrayList<>();
        ExecutorService submitter = Executors.newFixedThreadPool(SUBMITTER_THREAD_COUNT);
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
            if (!submitted.await(SUBMISSION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("Timed out submitting demonstration orders");
            }
            processor.awaitIdle(PROCESSING_TIMEOUT);
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
    }

    private DemonstrationResult summarize(
            OrderProcessor processor,
            int acceptedOrderCount,
            int invalidCreationCount) {
        long completed = processor.snapshotOrders().stream()
                .filter(order -> order.getStatus() == OrderStatus.COMPLETED)
                .count();
        long failed = processor.snapshotOrders().stream()
                .filter(order -> order.getStatus() == OrderStatus.FAILED)
                .count();
        return new DemonstrationResult(
                PRODUCT_COUNT,
                CATEGORIES.length,
                CUSTOMER_COUNT,
                ATTEMPTED_ORDER_COUNT,
                acceptedOrderCount,
                (int) completed,
                (int) failed,
                invalidCreationCount,
                processor.isShutdown());
    }

    private void seedCatalog(ProductCatalog catalog) {
        OrderFlowCatalogSeed.seed(catalog);
    }

    private void seedCustomers(CustomerDirectory customers) {
        for (int customerIndex = 1; customerIndex <= CUSTOMER_COUNT; customerIndex++) {
            customers.register(new Customer(
                    customerId(customerIndex),
                    "Customer " + customerIndex,
                    "customer" + customerIndex + "@example.com",
                    CUSTOMER_TYPES[(customerIndex - 1) % CUSTOMER_TYPES.length]));
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
                customerId(((sequence - 1) % CUSTOMER_COUNT) + 1),
                List.of(new RequestedProduct(
                        productId(((sequence - 1) % PRODUCT_COUNT) + 1),
                        1)));
    }

    private void printResults(
            ProductCatalog catalog,
            CustomerDirectory customers,
            Inventory inventory,
            OrderProcessor processor,
            AuditLog auditLog) {
        OrderReporter reporter = new OrderReporter();
        List<Order> orders = processor.snapshotOrders().stream()
                .sorted(Comparator.comparing(Order::getId))
                .toList();
        Optional<Order> completedExample = orders.stream()
                .filter(order -> order.getStatus() == OrderStatus.COMPLETED)
                .filter(order -> !PAYMENT_FAILURE_ORDER_IDS.contains(order.getId()))
                .filter(order -> !requestsContendedProduct(order))
                .findFirst()
                .or(() -> orders.stream()
                        .filter(order -> order.getStatus() == OrderStatus.COMPLETED)
                        .findFirst());
        List<Order> stockFightOrders = orders.stream()
                .filter(OrderFlowDemonstration::requestsContendedProduct)
                .toList();
        long stockFightCompleted = stockFightOrders.stream()
                .filter(order -> order.getStatus() == OrderStatus.COMPLETED)
                .count();
        long stockFightFailed = stockFightOrders.stream()
                .filter(order -> order.getStatus() == OrderStatus.FAILED)
                .count();

        out.println("OrderFlow walkthrough");
        out.println("The system still processes 50 order attempts concurrently.");
        out.println("This view shows the few cases to explain out loud.");
        out.println();
        out.println("Setup");
        out.println("  15 products in Tools, Garden, Kitchen, Sports");
        out.println("  10 customers covering regular, premium, and corporate");
        out.println("  P-01 starts with only 5 units, so several orders compete");
        out.println("  O-48 and O-49 are configured to fail payment");
        out.println();
        out.println("Rejected at create — never queued");
        out.println("  O-01  empty order. An order must contain at least one item.");
        out.println("  O-02  customer does not exist.");
        out.println();
        out.println("Stock fight on P-01");
        out.println("  " + stockFightOrders.size() + " orders asked for 1 unit each; only 5 units existed.");
        out.println("  Completed: " + stockFightCompleted
                + "  Failed: " + stockFightFailed
                + "  Remaining P-01 stock: " + inventory.availableQuantity("P-01"));
        out.println();
        out.println("Payment failure — reserved stock was returned");
        printNamedOrder(findOrder(orders, "O-48"), "payment declined");
        printNamedOrder(findOrder(orders, "O-49"), "payment declined");
        out.println();
        out.println("Completed example");
        completedExample.ifPresentOrElse(
                order -> out.println("  " + explainOrder(order)),
                () -> out.println("  No completed order in this run."));
        out.println();
        out.println("Totals");
        out.println("  Attempted " + ATTEMPTED_ORDER_COUNT
                + ", accepted " + orders.size()
                + ", completed " + countStatus(orders, OrderStatus.COMPLETED)
                + ", failed " + countStatus(orders, OrderStatus.FAILED)
                + ", invalid at create 2");
        out.println("  All executors shut down: " + processor.isShutdown());
        out.println();
        out.println("Inventory snapshot: " + inventory.snapshot());
        out.println();
        out.println("Reports");
        out.println("  Completed revenue: " + reporter.completedRevenue(orders));
        out.println("  Revenue by category: " + reporter.revenueByCategory(orders, catalog));
        out.println("  Orders by status: " + reporter.ordersByStatus(orders));
        out.println("  Spending by customer: " + reporter.spendingByCustomer(orders));
        out.println("  Top customers: " + reporter.topFiveCustomers(orders));
        out.println("  Top products: " + reporter.topFiveProducts(orders));
        out.println("  Average completed order value: " + reporter.averageCompletedOrderValue(orders));
        out.println("  Completed orders by day: " + reporter.completedOrdersByDay(orders));
        out.println("  Failures by reason: " + reporter.failuresByReason(orders));
        out.println("  Low stock: " + reporter.lowStock(catalog).stream().map(Product::getId).toList());
        out.println("  Unique tags: " + reporter.uniqueTagsAlphabetically(catalog));
        out.println("  Highest value by customer type: "
                + reporter.highestValueCompletedOrderByCustomerType(orders, customers).entrySet().stream()
                        .map(entry -> entry.getKey()
                                + "="
                                + entry.getValue().getId()
                                + " paid "
                                + entry.getValue().getFinalAmount().orElse(null))
                        .toList());
        out.println();
        out.println("Audit events:");
        printExampleAudit(auditLog, completedExample.map(Order::getId).orElse(null));
        printExampleAudit(auditLog, "O-48");
        out.println("  Recorded " + auditLog.allEvents().size()
                + " events in total; only the example orders above are printed.");
        out.flush();
    }

    private void printNamedOrder(Optional<Order> order, String plainReason) {
        if (order.isEmpty()) {
            out.println("  (not present in this run)");
            return;
        }
        out.println("  " + explainOrder(order.get()) + " — " + plainReason);
    }

    private static String explainOrder(Order order) {
        String amount = order.getFinalAmount()
                .map(value -> " paid " + value)
                .orElse("");
        String failure = order.getFailureReason()
                .map(reason -> " (" + reason + ")")
                .orElse("");
        return order.getId()
                + "  " + order.getStatus()
                + "  customer " + order.getCustomerId()
                + amount
                + failure;
    }

    private static Optional<Order> findOrder(List<Order> orders, String orderId) {
        return orders.stream().filter(order -> order.getId().equals(orderId)).findFirst();
    }

    private static long countStatus(List<Order> orders, OrderStatus status) {
        return orders.stream().filter(order -> order.getStatus() == status).count();
    }

    private static boolean requestsContendedProduct(Order order) {
        return order.getItems().stream().anyMatch(item -> "P-01".equals(item.getProductId()));
    }

    private void printExampleAudit(AuditLog auditLog, String orderId) {
        if (orderId == null) {
            return;
        }
        List<AuditEvent> events = auditLog.eventsFor(orderId);
        if (events.isEmpty()) {
            return;
        }
        out.println("  " + orderId);
        for (AuditEvent event : events) {
            out.println("    " + event.id()
                    + "  " + event.orderId()
                    + "  " + event.type()
                    + "  " + event.message()
                    + "  " + event.timestamp()
                    + "  " + event.threadName());
        }
    }

    private static List<Level> quietDemoLogs() {
        List<Level> previousLevels = new ArrayList<>();
        for (String loggerName : DEMO_LOGGER_NAMES) {
            Logger logger = Logger.getLogger(loggerName);
            previousLevels.add(logger.getLevel());
            logger.setLevel(Level.SEVERE);
        }
        return previousLevels;
    }

    private static void restoreDemoLogs(List<Level> previousLevels) {
        for (int loggerIndex = 0; loggerIndex < DEMO_LOGGER_NAMES.size(); loggerIndex++) {
            Logger.getLogger(DEMO_LOGGER_NAMES.get(loggerIndex)).setLevel(previousLevels.get(loggerIndex));
        }
    }

    private static String productId(int sequence) {
        return OrderFlowCatalogSeed.productId(sequence);
    }

    private static String customerId(int sequence) {
        return "C-" + String.format("%02d", sequence);
    }

    private static String orderId(int sequence) {
        return "O-" + String.format("%02d", sequence);
    }
}
