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
import com.codewalnut.orderflow.core.exception.OrderQueueCapacityException;
import com.codewalnut.orderflow.core.service.audit.AuditLog;
import com.codewalnut.orderflow.core.service.catalog.ProductCatalog;
import com.codewalnut.orderflow.core.service.customer.CustomerDirectory;
import com.codewalnut.orderflow.core.service.inventory.Inventory;
import com.codewalnut.orderflow.core.service.notification.NotificationChannel;
import com.codewalnut.orderflow.core.service.notification.NotificationDispatcher;
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
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public final class OrderProcessor {
    private static final long WORK_POLL_TIMEOUT_MILLIS = 100L;
    private static final Logger LOGGER = Logger.getLogger(OrderProcessor.class.getName());

    private final ProductCatalog catalog;
    private final CustomerDirectory customers;
    private final Inventory inventory;
    private final OrderValidationPipeline validationPipeline;
    private final DiscountEngine discountEngine;
    private final AuditLog auditLog;
    private final OrderProcessingPolicy policy;
    private final BlockingQueue<Order> queuedOrders;
    private final Set<String> submittedOrderIds = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, Order> ordersById = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ReservedOrderAttempt> activeReservedAttempts = new ConcurrentHashMap<>();
    private final OrderWorkTracker workTracker = new OrderWorkTracker();
    private final ExecutorService workerExecutor;
    private final PaymentCoordinator paymentCoordinator;
    private final NotificationDispatcher notificationDispatcher;
    private final Object submissionLock = new Object();
    private final AtomicBoolean started = new AtomicBoolean();
    private boolean acceptingSubmissions = true;
    private boolean shutdownCompleted;

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
                OrderProcessingPolicy.defaults(),
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
            OrderProcessingPolicy policy,
            boolean startWorkers) {
        this.catalog = Objects.requireNonNull(catalog, "catalog must not be null");
        this.customers = Objects.requireNonNull(customers, "customers must not be null");
        this.inventory = Objects.requireNonNull(inventory, "inventory must not be null");
        this.validationPipeline = Objects.requireNonNull(validationPipeline, "validationPipeline must not be null");
        this.discountEngine = Objects.requireNonNull(discountEngine, "discountEngine must not be null");
        this.auditLog = Objects.requireNonNull(auditLog, "auditLog must not be null");
        this.policy = Objects.requireNonNull(policy, "policy must not be null");
        queuedOrders = new LinkedBlockingQueue<>(policy.orderQueueCapacity());
        workerExecutor = Executors.newFixedThreadPool(policy.orderWorkerCount(), namedThreads("order-worker-"));
        paymentCoordinator = new PaymentCoordinator(
                Objects.requireNonNull(paymentGateway, "paymentGateway must not be null"),
                policy);
        notificationDispatcher = new NotificationDispatcher(notificationChannels, auditLog, policy);
        if (startWorkers) {
            start();
        }
    }

    void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        for (int workerIndex = 0; workerIndex < policy.orderWorkerCount(); workerIndex++) {
            workerExecutor.execute(this::runWorker);
        }
    }

    public void submit(Order order) {
        Objects.requireNonNull(order, "order must not be null");
        synchronized (submissionLock) {
            rejectUnavailableSubmission(order);
            acceptSubmission(order);
        }
    }

    public void awaitIdle(Duration timeout) throws InterruptedException {
        workTracker.awaitIdle(timeout);
    }

    public synchronized void shutdown() {
        if (shutdownCompleted) {
            return;
        }
        boolean shouldRestoreInterrupt = Thread.interrupted();
        long deadlineNanos = deadlineFromNow(policy.shutdownBudget());
        try {
            stopSubmissions();
            shouldRestoreInterrupt |= stopWorkers(deadlineNanos);
            paymentCoordinator.shutdown(remainingDuration(deadlineNanos));
            shouldRestoreInterrupt |= Thread.interrupted();
            failRemainingReservedAttempts();
            reconcileProcessingOrdersWithoutReservations();
            notificationDispatcher.shutdown(remainingDuration(deadlineNanos));
            shouldRestoreInterrupt |= Thread.interrupted();
            closeFinalTrackedWork();
            shutdownCompleted = true;
        } finally {
            if (shouldRestoreInterrupt) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public boolean isShutdown() {
        return workerExecutor.isTerminated()
                && paymentCoordinator.isTerminated()
                && notificationDispatcher.isTerminated();
    }

    public List<Order> snapshotOrders() {
        return List.copyOf(ordersById.values());
    }

    private void rejectUnavailableSubmission(Order order) {
        if (!acceptingSubmissions) {
            throw new IllegalStateException("Order processor is shut down; rejected order " + order.getId());
        }
        if (submittedOrderIds.contains(order.getId())) {
            throw new DuplicateOrderSubmissionException(order.getId());
        }
        if (queuedOrders.remainingCapacity() == 0) {
            throw new OrderQueueCapacityException(order.getId(), "order ingress", policy.orderQueueCapacity());
        }
    }

    private void acceptSubmission(Order order) {
        synchronized (order) {
            requireCreated(order);
            if (!submittedOrderIds.add(order.getId())) {
                throw new DuplicateOrderSubmissionException(order.getId());
            }
            boolean workWasTracked = false;
            try {
                Order indexed = ordersById.computeIfAbsent(order.getId(), ignoredOrderId -> order);
                if (indexed != order) {
                    throw new DuplicateOrderSubmissionException(order.getId());
                }
                workWasTracked = workTracker.begin(order.getId());
                if (!workWasTracked) {
                    throw new DuplicateOrderSubmissionException(order.getId());
                }
                auditLog.record(order.getId(), AuditEventType.QUEUED, "Order queued for processing");
                order.queue();
                if (!queuedOrders.offer(order)) {
                    throw new IllegalStateException(
                            "Order ingress capacity changed unexpectedly while accepting order " + order.getId());
                }
            } catch (RuntimeException exception) {
                rollbackSubmission(order, workWasTracked);
                throw exception;
            }
        }
    }

    private void rollbackSubmission(Order order, boolean workWasTracked) {
        queuedOrders.remove(order);
        if (workWasTracked) {
            workTracker.rollback(order.getId());
        }
        ordersById.remove(order.getId(), order);
        submittedOrderIds.remove(order.getId());
    }

    private static void requireCreated(Order order) {
        OrderStatus status = order.getStatus();
        if (status != OrderStatus.CREATED) {
            throw new InvalidOrderStatusTransitionException(
                    "Order " + order.getId() + " cannot transition from " + status + " to " + OrderStatus.QUEUED);
        }
    }

    private void runWorker() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Order order = queuedOrders.poll(WORK_POLL_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
                if (order != null) {
                    processQueuedOrder(order);
                } else if (!isAcceptingSubmissions() && queuedOrders.isEmpty()) {
                    return;
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException exception) {
                logSafely(Level.SEVERE, "Order worker isolated an unexpected failure", exception);
            }
        }
    }

    private void processQueuedOrder(Order order) {
        Consumer<Order> notifyFinalOutcome = this::dispatchFinalNotification;
        if (order.getStatus() == OrderStatus.CANCELLED) {
            recordSafely(order.getId(), AuditEventType.SKIPPED, "Cancelled order skipped");
            workTracker.complete(order.getId());
            return;
        }
        try {
            if (!startProcessing(order)) {
                return;
            }
            List<ValidationResult> failures = validationFailures(order);
            if (!failures.isEmpty()) {
                failValidation(order, failures);
                notifyFinalOutcome.accept(order);
                return;
            }
            recordSafely(order.getId(), AuditEventType.VALIDATION, "Order validation passed");
            DiscountResult pricing = price(order);
            reserveAndCharge(order, pricing);
        } catch (InsufficientStockException exception) {
            recordSafely(order.getId(), AuditEventType.RESERVATION, exception.getMessage());
            failProcessingOrder(order, exception.getMessage());
            notifyFinalOutcome.accept(order);
        } catch (RuntimeException exception) {
            logSafely(Level.SEVERE, "Isolated failure while processing order " + order.getId(), exception);
            failProcessingOrder(order, failureDetail(exception));
            notifyFinalOutcome.accept(order);
        }
    }

    private boolean startProcessing(Order order) {
        try {
            order.startProcessing();
        } catch (InvalidOrderStatusTransitionException exception) {
            if (order.getStatus() == OrderStatus.CANCELLED) {
                recordSafely(order.getId(), AuditEventType.SKIPPED, "Cancelled order skipped");
                workTracker.complete(order.getId());
                return false;
            }
            throw exception;
        }
        auditLog.record(order.getId(), AuditEventType.PROCESSING, "Order processing started");
        return true;
    }

    private List<ValidationResult> validationFailures(Order order) {
        Predicate<ValidationResult> failedValidation = result -> !result.isPassed();
        return validationPipeline.evaluate(new OrderValidationContext(requestFrom(order), customers, catalog, inventory))
                .stream()
                .filter(failedValidation)
                .toList();
    }

    private void failValidation(Order order, List<ValidationResult> failures) {
        Function<ValidationResult, String> validationFailureDetail =
                failure -> failure.ruleName() + ": " + failure.failureMessage();
        String message = failures.stream()
                .map(validationFailureDetail)
                .collect(Collectors.joining("; "));
        recordSafely(order.getId(), AuditEventType.VALIDATION, message);
        failProcessingOrder(order, message);
    }

    private DiscountResult price(Order order) {
        Customer customer = customers.findById(order.getCustomerId());
        int totalQuantity = order.getItems().stream().mapToInt(OrderItem::getQuantity).sum();
        return discountEngine.evaluate(
                new DiscountContext(customer.getType(), order.getOriginalAmount(), totalQuantity));
    }

    private void reserveAndCharge(Order order, DiscountResult pricing) {
        Reservation reservation = inventory.reserve(order.getId(), requestedQuantities(order));
        ReservedOrderAttempt attempt = new ReservedOrderAttempt(order, reservation, pricing);
        activeReservedAttempts.put(order.getId(), attempt);
        try {
            auditLog.record(order.getId(), AuditEventType.RESERVATION, "Inventory reserved");
            paymentCoordinator.charge(order, pricing.getFinalAmount())
                    .whenComplete((outcome, completionFailure) ->
                            settlePayment(attempt, outcome, completionFailure));
        } catch (RuntimeException exception) {
            settleReservedFailure(
                    attempt,
                    "Payment handoff failed for order " + order.getId() + ": " + failureDetail(exception),
                    exception);
        }
    }

    private void settlePayment(
            ReservedOrderAttempt attempt,
            PaymentOutcome outcome,
            Throwable completionFailure) {
        if (completionFailure != null) {
            settleReservedFailure(
                    attempt,
                    "Payment coordination failed for order " + attempt.order().getId(),
                    completionFailure);
            return;
        }
        if (outcome == null || outcome.kind() != PaymentOutcome.Kind.SUCCESS) {
            String reason = outcome == null
                    ? "Payment produced no outcome for order " + attempt.order().getId()
                    : outcome.reason();
            settleReservedFailure(attempt, reason, outcome == null ? null : outcome.cause());
            return;
        }
        settleReservedSuccess(attempt);
    }

    private void settleReservedSuccess(ReservedOrderAttempt attempt) {
        if (!claimAttempt(attempt)) {
            return;
        }
        Order order = attempt.order();
        try {
            order.complete(attempt.pricing().getDiscountAmount(), attempt.pricing().getFinalAmount());
        } catch (RuntimeException exception) {
            releaseSafely(attempt, "Reservation released after completion failure");
            failProcessingOrder(order, failureDetail(exception));
            dispatchFinalNotification(order);
            return;
        }
        recordSafely(order.getId(), AuditEventType.PAYMENT, "Payment succeeded");
        recordSafely(order.getId(), AuditEventType.COMPLETED, "Order completed");
        dispatchFinalNotification(order);
    }

    private void settleReservedFailure(ReservedOrderAttempt attempt, String reason, Throwable cause) {
        if (!claimAttempt(attempt)) {
            return;
        }
        Order order = attempt.order();
        if (cause != null) {
            logSafely(Level.WARNING, reason, cause);
        }
        recordSafely(order.getId(), AuditEventType.PAYMENT, reason);
        releaseSafely(attempt, "Reservation released after payment failure");
        failProcessingOrder(order, reason);
        dispatchFinalNotification(order);
    }

    private boolean claimAttempt(ReservedOrderAttempt attempt) {
        if (!attempt.trySettle()) {
            return false;
        }
        activeReservedAttempts.remove(attempt.order().getId(), attempt);
        return true;
    }

    private void releaseSafely(ReservedOrderAttempt attempt, String auditMessage) {
        try {
            inventory.release(attempt.reservation());
        } catch (RuntimeException exception) {
            logSafely(
                    Level.SEVERE,
                    "Reservation release failed for order " + attempt.order().getId(),
                    exception);
        }
        recordSafely(attempt.order().getId(), AuditEventType.RELEASE, auditMessage);
    }

    private void failProcessingOrder(Order order, String reason) {
        if (order.getStatus() != OrderStatus.PROCESSING) {
            return;
        }
        String failureReason = reason == null || reason.isBlank() ? "Order processing failed" : reason;
        try {
            order.fail(failureReason);
        } catch (RuntimeException exception) {
            logSafely(Level.SEVERE, "Failed to mark order " + order.getId() + " failed", exception);
            return;
        }
        recordSafely(order.getId(), AuditEventType.FAILED, failureReason);
    }

    private void dispatchFinalNotification(Order order) {
        try {
            notificationDispatcher.dispatch(order)
                    .whenComplete((ignoredResult, ignoredFailure) -> workTracker.complete(order.getId()));
        } catch (RuntimeException exception) {
            logSafely(Level.SEVERE, "Notification dispatch failed for order " + order.getId(), exception);
            workTracker.complete(order.getId());
        }
    }

    private void stopSubmissions() {
        synchronized (submissionLock) {
            acceptingSubmissions = false;
        }
    }

    private boolean stopWorkers(long deadlineNanos) {
        boolean wasInterrupted = false;
        workerExecutor.shutdown();
        try {
            if (!workerExecutor.awaitTermination(remainingNanos(deadlineNanos), TimeUnit.NANOSECONDS)) {
                cancelQueuedOrders();
                workerExecutor.shutdownNow();
                workerExecutor.awaitTermination(remainingNanos(deadlineNanos), TimeUnit.NANOSECONDS);
            }
        } catch (InterruptedException exception) {
            wasInterrupted = true;
            cancelQueuedOrders();
            workerExecutor.shutdownNow();
            try {
                workerExecutor.awaitTermination(remainingNanos(deadlineNanos), TimeUnit.NANOSECONDS);
            } catch (InterruptedException repeatedInterruption) {
                wasInterrupted = true;
            }
        }
        cancelQueuedOrders();
        return wasInterrupted;
    }

    private void cancelQueuedOrders() {
        Order queuedOrder;
        while ((queuedOrder = queuedOrders.poll()) != null) {
            if (queuedOrder.getStatus() == OrderStatus.QUEUED) {
                try {
                    queuedOrder.cancel();
                } catch (RuntimeException exception) {
                    logSafely(Level.WARNING, "Failed to cancel queued order " + queuedOrder.getId(), exception);
                }
            }
            recordSafely(queuedOrder.getId(), AuditEventType.CANCELLED, "Order cancelled during shutdown");
            workTracker.complete(queuedOrder.getId());
        }
    }

    private void failRemainingReservedAttempts() {
        for (ReservedOrderAttempt attempt : List.copyOf(activeReservedAttempts.values())) {
            settleReservedFailure(
                    attempt,
                    "Order processing cancelled during shutdown",
                    null);
        }
    }

    private void reconcileProcessingOrdersWithoutReservations() {
        for (Order order : ordersById.values()) {
            if (order.getStatus() == OrderStatus.PROCESSING
                    && !activeReservedAttempts.containsKey(order.getId())) {
                failProcessingOrder(order, "Order processing cancelled during shutdown");
                dispatchFinalNotification(order);
            }
        }
    }

    private void closeFinalTrackedWork() {
        for (Order order : ordersById.values()) {
            if (isFinal(order.getStatus())) {
                workTracker.complete(order.getId());
            }
        }
    }

    private boolean isAcceptingSubmissions() {
        synchronized (submissionLock) {
            return acceptingSubmissions;
        }
    }

    private void recordSafely(String orderId, AuditEventType type, String message) {
        try {
            auditLog.record(orderId, type, message);
        } catch (RuntimeException exception) {
            logSafely(Level.SEVERE, "Audit recording failed for order " + orderId + " type " + type, exception);
        }
    }

    private static Map<String, Integer> requestedQuantities(Order order) {
        Map<String, Integer> quantitiesByProductId = new LinkedHashMap<>();
        for (OrderItem item : order.getItems()) {
            quantitiesByProductId.merge(item.getProductId(), item.getQuantity(), Math::addExact);
        }
        return quantitiesByProductId;
    }

    private static OrderRequest requestFrom(Order order) {
        return new OrderRequest(
                order.getCustomerId(),
                order.getItems().stream()
                        .map(item -> new RequestedProduct(item.getProductId(), item.getQuantity()))
                        .toList());
    }

    private static boolean isFinal(OrderStatus status) {
        return status == OrderStatus.COMPLETED
                || status == OrderStatus.FAILED
                || status == OrderStatus.CANCELLED;
    }

    private static String failureDetail(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }

    private static void logSafely(Level level, String message, Throwable cause) {
        try {
            LOGGER.log(level, message, cause);
        } catch (RuntimeException loggingFailure) {
            // JUL handlers are external callbacks; lifecycle settlement cannot depend on them.
        }
    }

    private static long deadlineFromNow(Duration budget) {
        long budgetNanos;
        try {
            budgetNanos = budget.toNanos();
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
        long now = System.nanoTime();
        return budgetNanos > Long.MAX_VALUE - now ? Long.MAX_VALUE : now + budgetNanos;
    }

    private static long remainingNanos(long deadlineNanos) {
        return Math.max(0L, deadlineNanos - System.nanoTime());
    }

    private static Duration remainingDuration(long deadlineNanos) {
        return Duration.ofNanos(remainingNanos(deadlineNanos));
    }

    private static ThreadFactory namedThreads(String prefix) {
        AtomicInteger sequence = new AtomicInteger(1);
        return runnable -> new Thread(runnable, prefix + sequence.getAndIncrement());
    }
}
