package com.codewalnut.orderflow.core.service.processing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OrderProcessingPolicyTest {

    @Test
    void givenDefaultPolicy_whenCreated_thenUsesApprovedProcessingValues() {
        // Act
        OrderProcessingPolicy policy = OrderProcessingPolicy.defaults();

        // Assert
        assertEquals(3, policy.orderWorkerCount());
        assertEquals(256, policy.orderQueueCapacity());
        assertEquals(3, policy.paymentWorkerCount());
        assertEquals(256, policy.paymentQueueCapacity());
        assertEquals(3, policy.notificationWorkerCount());
        assertEquals(256, policy.notificationQueueCapacity());
        assertEquals(Duration.ofSeconds(5), policy.paymentDeadline());
        assertEquals(Duration.ofSeconds(2), policy.notificationDeadline());
        assertEquals(Duration.ofSeconds(10), policy.shutdownBudget());
    }

    @Test
    void givenPositiveTestValues_whenPolicyIsCreated_thenRetainsInjectedValues() {
        // Act
        OrderProcessingPolicy policy = new OrderProcessingPolicy(
                1,
                2,
                3,
                4,
                5,
                6,
                Duration.ofMillis(7),
                Duration.ofMillis(8),
                Duration.ofMillis(9));

        // Assert
        assertEquals(1, policy.orderWorkerCount());
        assertEquals(2, policy.orderQueueCapacity());
        assertEquals(3, policy.paymentWorkerCount());
        assertEquals(4, policy.paymentQueueCapacity());
        assertEquals(5, policy.notificationWorkerCount());
        assertEquals(6, policy.notificationQueueCapacity());
        assertEquals(Duration.ofMillis(7), policy.paymentDeadline());
        assertEquals(Duration.ofMillis(8), policy.notificationDeadline());
        assertEquals(Duration.ofMillis(9), policy.shutdownBudget());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("nonPositivePolicyValues")
    void givenNonPositivePolicyValue_whenPolicyIsCreated_thenRejectsInvalidState(
            String fieldName,
            Supplier<OrderProcessingPolicy> policyCreation) {
        // Act
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, policyCreation::get);

        // Assert
        assertEquals(fieldName + " must be positive", exception.getMessage());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("nullDurationPolicyValues")
    void givenNullPolicyDuration_whenPolicyIsCreated_thenRejectsNamedDuration(
            String fieldName,
            Supplier<OrderProcessingPolicy> policyCreation) {
        // Act
        NullPointerException exception = assertThrows(NullPointerException.class, policyCreation::get);

        // Assert
        assertEquals(fieldName + " must not be null", exception.getMessage());
    }

    private static Stream<Object[]> nonPositivePolicyValues() {
        Duration oneSecond = Duration.ofSeconds(1);
        return Stream.of(
                invalidPolicy("orderWorkerCount", () -> new OrderProcessingPolicy(
                        0, 1, 1, 1, 1, 1, oneSecond, oneSecond, oneSecond)),
                invalidPolicy("orderQueueCapacity", () -> new OrderProcessingPolicy(
                        1, -1, 1, 1, 1, 1, oneSecond, oneSecond, oneSecond)),
                invalidPolicy("paymentWorkerCount", () -> new OrderProcessingPolicy(
                        1, 1, 0, 1, 1, 1, oneSecond, oneSecond, oneSecond)),
                invalidPolicy("paymentQueueCapacity", () -> new OrderProcessingPolicy(
                        1, 1, 1, -1, 1, 1, oneSecond, oneSecond, oneSecond)),
                invalidPolicy("notificationWorkerCount", () -> new OrderProcessingPolicy(
                        1, 1, 1, 1, 0, 1, oneSecond, oneSecond, oneSecond)),
                invalidPolicy("notificationQueueCapacity", () -> new OrderProcessingPolicy(
                        1, 1, 1, 1, 1, -1, oneSecond, oneSecond, oneSecond)),
                invalidPolicy("paymentDeadline", () -> new OrderProcessingPolicy(
                        1, 1, 1, 1, 1, 1, Duration.ZERO, oneSecond, oneSecond)),
                invalidPolicy("notificationDeadline", () -> new OrderProcessingPolicy(
                        1, 1, 1, 1, 1, 1, oneSecond, Duration.ofMillis(-1), oneSecond)),
                invalidPolicy("shutdownBudget", () -> new OrderProcessingPolicy(
                        1, 1, 1, 1, 1, 1, oneSecond, oneSecond, Duration.ZERO)));
    }

    private static Stream<Object[]> nullDurationPolicyValues() {
        Duration oneSecond = Duration.ofSeconds(1);
        return Stream.of(
                invalidPolicy("paymentDeadline", () -> new OrderProcessingPolicy(
                        1, 1, 1, 1, 1, 1, null, oneSecond, oneSecond)),
                invalidPolicy("notificationDeadline", () -> new OrderProcessingPolicy(
                        1, 1, 1, 1, 1, 1, oneSecond, null, oneSecond)),
                invalidPolicy("shutdownBudget", () -> new OrderProcessingPolicy(
                        1, 1, 1, 1, 1, 1, oneSecond, oneSecond, null)));
    }

    private static Object[] invalidPolicy(String fieldName, Supplier<OrderProcessingPolicy> policyCreation) {
        return new Object[]{fieldName, policyCreation};
    }
}
