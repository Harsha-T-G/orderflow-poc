package com.codewalnut.orderflow.core.service.processing;

import com.codewalnut.orderflow.core.domain.audit.AuditEventType;
import com.codewalnut.orderflow.core.domain.customer.Customer;
import com.codewalnut.orderflow.core.domain.inventory.Reservation;
import com.codewalnut.orderflow.core.domain.order.Order;
import com.codewalnut.orderflow.core.domain.order.OrderItem;
import com.codewalnut.orderflow.core.domain.order.OrderRequest;
import com.codewalnut.orderflow.core.domain.order.OrderStatus;
import com.codewalnut.orderflow.core.domain.order.RequestedProduct;
import com.codewalnut.orderflow.core.domain.pricing.DiscountContext;
import com.codewalnut.orderflow.core.domain.pricing.DiscountResult;
import com.codewalnut.orderflow.core.exception.DuplicateOrderSubmissionException;
import com.codewalnut.orderflow.core.exception.InsufficientStockException;
import com.codewalnut.orderflow.core.exception.InvalidOrderStatusTransitionException;
import com.codewalnut.orderflow.core.exception.PaymentFailedException;
import com.codewalnut.orderflow.core.service.audit.AuditLog;
import com.codewalnut.orderflow.core.service.catalog.ProductCatalog;
import com.codewalnut.orderflow.core.service.customer.CustomerDirectory;
import com.codewalnut.orderflow.core.service.inventory.Inventory;
import com.codewalnut.orderflow.core.service.notification.NotificationChannel;
import com.codewalnut.orderflow.core.service.order.validation.OrderValidationContext;
import com.codewalnut.orderflow.core.service.order.validation.OrderValidationPipeline;
import com.codewalnut.orderflow.core.service.order.validation.ValidationResult;
import com.codewalnut.orderflow.core.service.payment.PaymentGateway;
import com.codewalnut.orderflow.core.service.pricing.DiscountEngine;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public final class OrderProcessor {
    public static final int WORKER_COUNT = 3;
    public static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(10);

    private static final Logger LOGGER = Logger.getLogger(OrderProcessor.class.getName());
    private static final Predicate<Order> IS_CANCELLED =
            order -> order.getStatus() == OrderStatus.CANCELLED;
    private static final Function<Order, Map<String, Integer>> REQUESTED_QUANTITIES = order -> {
        Map<String, Integer> quantitiesByProductId = new LinkedHashMap<>();
        for (OrderItem item : order.getItems()) {
            quantitiesByProductId.merge(item.getProductId(), item.getQuantity(), Math::addExact);
        }
        return quantitiesByProductId;
    };
    private static final Function<Order, OrderRequest> REQUEST_FROM_ORDER = order -> new OrderRequest(
            order.getCustomerId(),
            order.getItems().stream()
                    .map(item -> new RequestedProduct(item.getProductId(), item.getQuantity()))
                    .toList());

    private final ProductCatalog catalog;
    private final CustomerDirectory customers;
    private final Inventory inventory;
    private final OrderValidationPipeline validationPipeline;
    private final DiscountEngine discountEngine;
    private final PaymentGateway paymentGateway;
    private final List<NotificationChannel> notificationChannels;
    private final AuditLog auditLog;
    private final BlockingQueue<Order> queuedOrders = new LinkedBlockingQueue<>();
    private final Set<String> submittedOrderIds = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, Order> ordersById = new ConcurrentHashMap<>();
    private final ExecutorService workerExecutor;
    private final ExecutorService paymentExecutor;
    private final ExecutorService notificationExecutor;
    private final AtomicBoolean acceptingSubmissions = new AtomicBoolean(true);
    private final Object workMonitor = new Object();
    private int outstandingWork;
    private final int workerCount;
    private final AtomicBoolean started = new AtomicBoolean();
    private final Supplier<Duration> shutdownTimeout = () -> SHUTDOWN_TIMEOUT;
    private final Consumer<Order> auditQueued;

    public OrderProcessor(
            ProductCatalog catalog,
            CustomerDirectory customers,
            Inventory inventory,
            OrderValidationPipeline validationPipeline,
            DiscountEngine discountEngine,
            PaymentGateway paymentGateway,
            List<NotificationChannel> notificationChannels,
            AuditLog auditLog) {
        this(
                catalog,
                customers,
                inventory,
                validationPipeline,
                discountEngine,
                paymentGateway,
                notificationChannels,
                auditLog,
                WORKER_COUNT,
                true);
    }

    OrderProcessor(
            ProductCatalog catalog,
            CustomerDirectory customers,
            Inventory inventory,
            OrderValidationPipeline validationPipeline,
            DiscountEngine discountEngine,
            PaymentGateway paymentGateway,
            List<NotificationChannel> notificationChannels,
            AuditLog auditLog,
            int workerCount,
            boolean startWorkers) {
        if (workerCount < WORKER_COUNT) {
            throw new IllegalArgumentException("Order processor requires at least " + WORKER_COUNT + " workers");
        }
        this.catalog = Objects.requireNonNull(catalog, "catalog must not be null");
        this.customers = Objects.requireNonNull(customers, "customers must not be null");
        this.inventory = Objects.requireNonNull(inventory, "inventory must not be null");
        this.validationPipeline = Objects.requireNonNull(validationPipeline, "validationPipeline must not be null");
        this.discountEngine = Objects.requireNonNull(discountEngine, "discountEngine must not be null");
        this.paymentGateway = Objects.requireNonNull(paymentGateway, "paymentGateway must not be null");
        this.notificationChannels = List.copyOf(
                Objects.requireNonNull(notificationChannels, "notificationChannels must not be null"));
        this.auditLog = Objects.requireNonNull(auditLog, "auditLog must not be null");
        this.auditQueued = order ->
                this.auditLog.record(order.getId(), AuditEventType.QUEUED, "Order queued for processing");
        this.workerCount = workerCount;
        this.workerExecutor = Executors.newFixedThreadPool(workerCount, namedThreads("order-worker-"));
        this.paymentExecutor = Executors.newCachedThreadPool(namedThreads("order-payment-"));
        this.notificationExecutor = Executors.newCachedThreadPool(namedThreads("order-notification-"));
        if (startWorkers) {
            start();
        }
    }

    void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        for (int i = 0; i < workerCount; i++) {
            workerExecutor.execute(this::runWorker);
        }
    }

    public void submit(Order order) {
        Objects.requireNonNull(order, "order must not be null");
        if (!acceptingSubmissions.get()) {
            throw new IllegalStateException("Order processor is shut down; rejected order " + order.getId());
        }
        if (!submittedOrderIds.add(order.getId())) {
            throw new DuplicateOrderSubmissionException(order.getId());
        }
        ordersById.computeIfAbsent(order.getId(), ignoredOrderId -> order);
        order.queue();
        auditQueued.accept(order);
        beginWork();
        try {
            queuedOrders.put(order);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            endWork();
            throw new IllegalStateException("Interrupted while queueing order " + order.getId(), exception);
        }
    }

    public void awaitIdle(Duration timeout) throws InterruptedException {
        Objects.requireNonNull(timeout, "timeout must not be null");
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        synchronized (workMonitor) {
            while (outstandingWork > 0) {
                long remainingNanos = deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0) {
                    throw new IllegalStateException(
                            "Timed out waiting for order processing to become idle; outstanding=" + outstandingWork);
                }
                workMonitor.wait(Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remainingNanos)));
            }
        }
    }

    public void shutdown() {
        acceptingSubmissions.set(false);
        Duration timeout = shutdownTimeout.get();
        shutdownExecutor(workerExecutor, timeout);
        shutdownExecutor(paymentExecutor, timeout);
        shutdownExecutor(notificationExecutor, timeout);
    }

    public boolean isShutdown() {
        return workerExecutor.isTerminated()
                && paymentExecutor.isTerminated()
                && notificationExecutor.isTerminated();
    }

    public List<Order> snapshotOrders() {
        return List.copyOf(ordersById.values());
    }

    private void runWorker() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Order order = queuedOrders.poll(100, TimeUnit.MILLISECONDS);
                if (order == null) {
                    if (!acceptingSubmissions.get() && queuedOrders.isEmpty()) {
                        return;
                    }
                    continue;
                }
                processQueuedOrder(order);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException exception) {
                LOGGER.log(Level.SEVERE, "Order worker failed while taking work", exception);
            }
        }
    }

    private void processQueuedOrder(Order order) {
        try {
            if (IS_CANCELLED.test(order)) {
                auditLog.record(order.getId(), AuditEventType.SKIPPED, "Cancelled order skipped");
                endWork();
                return;
            }
            try {
                order.startProcessing();
            } catch (InvalidOrderStatusTransitionException exception) {
                if (IS_CANCELLED.test(order)) {
                    auditLog.record(order.getId(), AuditEventType.SKIPPED, "Cancelled order skipped");
                    endWork();
                    return;
                }
                throw exception;
            }
            auditLog.record(order.getId(), AuditEventType.PROCESSING, "Order processing started");
            List<ValidationResult> failures = validationPipeline.evaluate(
                            new OrderValidationContext(REQUEST_FROM_ORDER.apply(order), customers, catalog, inventory))
                    .stream()
                    .filter(result -> !result.passed())
                    .toList();
            if (!failures.isEmpty()) {
                String message = failures.stream()
                        .map(failure -> failure.ruleName() + ": " + failure.failureMessage())
                        .collect(Collectors.joining("; "));
                auditLog.record(order.getId(), AuditEventType.VALIDATION, message);
                order.fail(message);
                auditLog.record(order.getId(), AuditEventType.FAILED, message);
                notifyFinal(order);
                return;
            }
            auditLog.record(order.getId(), AuditEventType.VALIDATION, "Order validation passed");
            Customer customer = customers.findById(order.getCustomerId());
            int totalQuantity = order.getItems().stream().mapToInt(OrderItem::getQuantity).sum();
            DiscountResult pricing = discountEngine.evaluate(
                    new DiscountContext(customer.getType(), order.getOriginalAmount(), totalQuantity));
            Reservation reservation = inventory.reserve(order.getId(), REQUESTED_QUANTITIES.apply(order));
            auditLog.record(order.getId(), AuditEventType.RESERVATION, "Inventory reserved");
            CompletableFuture.runAsync(() -> settlePayment(order, reservation, pricing), paymentExecutor);
        } catch (InsufficientStockException exception) {
            auditLog.record(order.getId(), AuditEventType.RESERVATION, exception.getMessage());
            failProcessingOrder(order, exception.getMessage());
            notifyFinal(order);
        } catch (RuntimeException exception) {
            LOGGER.log(Level.SEVERE, "Isolated failure while processing order " + order.getId(), exception);
            failProcessingOrder(order, exception.getMessage());
            notifyFinal(order);
        }
    }

    private void settlePayment(Order order, Reservation reservation, DiscountResult pricing) {
        try {
            paymentGateway.charge(order, pricing.getFinalAmount());
            order.complete(pricing.getDiscountAmount(), pricing.getFinalAmount());
            auditLog.record(order.getId(), AuditEventType.PAYMENT, "Payment succeeded");
            auditLog.record(order.getId(), AuditEventType.COMPLETED, "Order completed");
            notifyFinal(order);
        } catch (PaymentFailedException exception) {
            inventory.release(reservation);
            auditLog.record(order.getId(), AuditEventType.PAYMENT, exception.getMessage());
            auditLog.record(order.getId(), AuditEventType.RELEASE, "Reservation released after payment failure");
            failProcessingOrder(order, exception.getMessage());
            notifyFinal(order);
        } catch (RuntimeException exception) {
            PaymentFailedException translated = new PaymentFailedException(order.getId(), exception);
            inventory.release(reservation);
            LOGGER.log(Level.SEVERE, "Payment execution failed for order " + order.getId(), exception);
            auditLog.record(order.getId(), AuditEventType.RELEASE, "Reservation released after processing failure");
            failProcessingOrder(order, translated.getMessage());
            notifyFinal(order);
        }
    }

    private void failProcessingOrder(Order order, String reason) {
        if (order.getStatus() != OrderStatus.PROCESSING) {
            return;
        }
        String failureReason = reason == null || reason.isBlank() ? "Order processing failed" : reason;
        order.fail(failureReason);
        auditLog.record(order.getId(), AuditEventType.FAILED, failureReason);
    }

    private void notifyFinal(Order order) {
        if (notificationChannels.isEmpty()) {
            endWork();
            return;
        }
        AtomicInteger remainingChannels = new AtomicInteger(notificationChannels.size());
        for (NotificationChannel channel : notificationChannels) {
            CompletableFuture.runAsync(() -> {
                try {
                    channel.notify(order);
                    auditLog.record(
                            order.getId(),
                            AuditEventType.NOTIFICATION,
                            "Notification succeeded via " + channel.getClass().getSimpleName());
                } catch (RuntimeException exception) {
                    LOGGER.log(Level.WARNING, "Notification failed for order " + order.getId(), exception);
                    String message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
                    auditLog.record(order.getId(), AuditEventType.NOTIFICATION, "Notification failed: " + message);
                } finally {
                    if (remainingChannels.decrementAndGet() == 0) {
                        endWork();
                    }
                }
            }, notificationExecutor);
        }
    }

    private void beginWork() {
        synchronized (workMonitor) {
            outstandingWork++;
        }
    }

    private void endWork() {
        synchronized (workMonitor) {
            outstandingWork--;
            if (outstandingWork == 0) {
                workMonitor.notifyAll();
            }
        }
    }

    private static void shutdownExecutor(ExecutorService executor, Duration timeout) {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                executor.shutdownNow();
                executor.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException exception) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static ThreadFactory namedThreads(String prefix) {
        AtomicInteger sequence = new AtomicInteger(1);
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + sequence.getAndIncrement());
            thread.setDaemon(false);
            return thread;
        };
    }
}
