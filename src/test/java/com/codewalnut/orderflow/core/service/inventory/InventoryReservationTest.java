package com.codewalnut.orderflow.core.service.inventory;

import com.codewalnut.orderflow.core.domain.inventory.Reservation;
import com.codewalnut.orderflow.core.exception.InsufficientStockException;
import com.codewalnut.orderflow.core.exception.InvalidProductDataException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InventoryReservationTest {

    @Test
    void givenSufficientStock_whenOrderIsReserved_thenAvailableQuantityDecreases() {
        // Arrange
        Inventory inventory = new Inventory();
        inventory.register("product-1", 10);
        Map<String, Integer> requestedQuantities = Map.of("product-1", 3);

        // Act
        Reservation reservation = inventory.reserve("order-1", requestedQuantities);

        // Assert
        assertEquals("order-1", reservation.orderId());
        assertEquals(3, reservation.reservedQuantities().get("product-1"));
        assertEquals(7, inventory.availableQuantity("product-1"));
    }

    @Test
    void givenInsufficientStock_whenOrderIsReserved_thenThrowsInsufficientStockException() {
        // Arrange
        Inventory inventory = new Inventory();
        inventory.register("product-1", 2);
        Map<String, Integer> requestedQuantities = Map.of("product-1", 5);

        // Act
        InsufficientStockException exception = assertThrows(
                InsufficientStockException.class,
                () -> inventory.reserve("order-2", requestedQuantities));

        // Assert
        assertTrue(exception.getMessage().contains("product-1"));
        assertTrue(exception.getMessage().contains("5"));
        assertTrue(exception.getMessage().contains("2"));
        assertEquals(2, inventory.availableQuantity("product-1"));
    }

    @Test
    void givenMultipleProductsWithStock_whenOrderIsReserved_thenAllQuantitiesDecrease() {
        // Arrange
        Inventory inventory = new Inventory();
        inventory.register("product-a", 10);
        inventory.register("product-b", 8);
        Map<String, Integer> requestedQuantities = new LinkedHashMap<>();
        requestedQuantities.put("product-b", 2);
        requestedQuantities.put("product-a", 4);

        // Act
        Reservation reservation = inventory.reserve("order-3", requestedQuantities);

        // Assert
        assertEquals(4, reservation.reservedQuantities().get("product-a"));
        assertEquals(2, reservation.reservedQuantities().get("product-b"));
        assertEquals(6, inventory.availableQuantity("product-a"));
        assertEquals(6, inventory.availableQuantity("product-b"));
    }

    @Test
    void givenLaterItemLacksStock_whenMultiItemOrderIsReserved_thenEarlierDecrementsAreReleased() {
        // Arrange
        Inventory inventory = new Inventory();
        inventory.register("product-a", 10);
        inventory.register("product-b", 1);
        Map<String, Integer> requestedQuantities = new LinkedHashMap<>();
        requestedQuantities.put("product-a", 4);
        requestedQuantities.put("product-b", 3);

        // Act
        InsufficientStockException exception = assertThrows(
                InsufficientStockException.class,
                () -> inventory.reserve("order-4", requestedQuantities));

        // Assert
        assertTrue(exception.getMessage().contains("product-b"));
        assertEquals(10, inventory.availableQuantity("product-a"));
        assertEquals(1, inventory.availableQuantity("product-b"));
    }

    @Test
    void givenSuccessfulReservation_whenReleased_thenQuantitiesAreRestored() {
        // Arrange
        Inventory inventory = new Inventory();
        inventory.register("product-1", 10);
        Reservation reservation = inventory.reserve("order-5", Map.of("product-1", 4));

        // Act
        inventory.release(reservation);

        // Assert
        assertEquals(10, inventory.availableQuantity("product-1"));
    }

    @Test
    void givenUnregisteredProduct_whenReserved_thenThrowsAndLeavesInventoryUnchanged() {
        // Arrange
        Inventory inventory = new Inventory();
        inventory.register("product-1", 10);

        // Act
        InvalidProductDataException exception = assertThrows(
                InvalidProductDataException.class,
                () -> inventory.reserve("order-6", Map.of("missing", 1)));

        // Assert
        assertTrue(exception.getMessage().contains("missing"));
        assertEquals(10, inventory.availableQuantity("product-1"));
    }

    @Test
    void givenNonPositiveQuantity_whenReserved_thenThrowsAndLeavesQuantityUnchanged() {
        // Arrange
        Inventory inventory = new Inventory();
        inventory.register("product-1", 10);

        // Act
        InvalidProductDataException zeroException = assertThrows(
                InvalidProductDataException.class,
                () -> inventory.reserve("order-7", Map.of("product-1", 0)));
        InvalidProductDataException negativeException = assertThrows(
                InvalidProductDataException.class,
                () -> inventory.reserve("order-8", Map.of("product-1", -1)));

        // Assert
        assertTrue(zeroException.getMessage().contains("product-1"));
        assertTrue(negativeException.getMessage().contains("product-1"));
        assertEquals(10, inventory.availableQuantity("product-1"));
    }

    @Test
    void givenReservationSnapshot_whenCallerMutates_thenInventoryAndReservationStayUnchanged() {
        // Arrange
        Inventory inventory = new Inventory();
        inventory.register("product-1", 10);
        Reservation reservation = inventory.reserve("order-9", Map.of("product-1", 2));

        // Act
        assertThrows(
                UnsupportedOperationException.class,
                () -> reservation.reservedQuantities().put("product-1", 99));

        // Assert
        assertEquals(2, reservation.reservedQuantities().get("product-1"));
        assertEquals(8, inventory.availableQuantity("product-1"));
    }

    @Test
    void givenLimitedStock_whenManyThreadsReserveConcurrently_thenSoldQuantityNeverExceedsStock() throws Exception {
        // Arrange
        Inventory inventory = new Inventory();
        inventory.register("product-1", 5);
        int threadCount = 20;
        CyclicBarrier start = new CyclicBarrier(threadCount);
        CountDownLatch finished = new CountDownLatch(threadCount);
        AtomicInteger successes = new AtomicInteger();
        List<Throwable> failures = new ArrayList<>();

        // Act
        for (int i = 0; i < threadCount; i++) {
            Thread thread = new Thread(() -> {
                try {
                    start.await(2, TimeUnit.SECONDS);
                    inventory.reserve("order-" + Thread.currentThread().threadId(), Map.of("product-1", 1));
                    successes.incrementAndGet();
                } catch (InsufficientStockException ignored) {
                    // expected under contention
                } catch (Throwable throwable) {
                    failures.add(throwable);
                } finally {
                    finished.countDown();
                }
            });
            thread.start();
        }
        assertTrue(finished.await(5, TimeUnit.SECONDS));

        // Assert
        assertTrue(failures.isEmpty(), () -> "Unexpected failures: " + failures);
        assertEquals(5, successes.get());
        assertEquals(0, inventory.availableQuantity("product-1"));
    }
}
