package com.codewalnut.orderflow.core.service.inventory;

import com.codewalnut.orderflow.core.exception.InvalidProductDataException;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InventoryTest {

    @Test
    void givenProductAndInitialQuantity_whenInventoryIsRegistered_thenQuantityIsAvailable() {
        // Arrange
        Inventory inventory = new Inventory();

        // Act
        inventory.register("product-1", 10);

        // Assert
        assertEquals(10, inventory.availableQuantity("product-1"));
    }

    @Test
    void givenNegativeInitialQuantity_whenInventoryIsRegistered_thenThrowsInvalidProductDataException() {
        // Arrange
        Inventory inventory = new Inventory();

        // Act
        InvalidProductDataException exception = assertThrows(
                InvalidProductDataException.class,
                () -> inventory.register("product-1", -1));

        // Assert
        assertTrue(exception.getMessage().contains("product-1"));
        assertTrue(exception.getMessage().contains("-1"));
    }

    @Test
    void givenRegisteredProduct_whenInventoryIsRegisteredAgain_thenThrowsAndPreservesQuantity() {
        // Arrange
        Inventory inventory = new Inventory();
        inventory.register("product-1", 10);

        // Act
        InvalidProductDataException exception = assertThrows(
                InvalidProductDataException.class,
                () -> inventory.register("product-1", 20));

        // Assert
        assertTrue(exception.getMessage().contains("product-1"));
        assertTrue(exception.getMessage().contains("20"));
        assertEquals(10, inventory.availableQuantity("product-1"));
    }

    @Test
    void givenNullOrBlankProductId_whenInventoryIsRegistered_thenThrowsInvalidProductDataException() {
        // Arrange
        Inventory inventory = new Inventory();

        // Act
        InvalidProductDataException nullIdException = assertThrows(
                InvalidProductDataException.class,
                () -> inventory.register(null, 10));
        InvalidProductDataException blankIdException = assertThrows(
                InvalidProductDataException.class,
                () -> inventory.register(" ", 10));

        // Assert
        assertTrue(nullIdException.getMessage().contains("null"));
        assertTrue(nullIdException.getMessage().contains("10"));
        assertTrue(blankIdException.getMessage().contains("blank"));
        assertTrue(blankIdException.getMessage().contains("10"));
        assertTrue(inventory.snapshot().isEmpty());
    }

    @Test
    void givenNullOrBlankProductId_whenAvailableQuantityIsRequested_thenThrowsInvalidProductDataException() {
        // Arrange
        Inventory inventory = new Inventory();

        // Act
        InvalidProductDataException nullIdException = assertThrows(
                InvalidProductDataException.class,
                () -> inventory.availableQuantity(null));
        InvalidProductDataException blankIdException = assertThrows(
                InvalidProductDataException.class,
                () -> inventory.availableQuantity(" "));

        // Assert
        assertTrue(nullIdException.getMessage().contains("null"));
        assertTrue(nullIdException.getMessage().contains("available quantity lookup"));
        assertTrue(blankIdException.getMessage().contains("blank"));
        assertTrue(blankIdException.getMessage().contains("available quantity lookup"));
    }

    @Test
    void givenUnregisteredProduct_whenAvailableQuantityIsRequested_thenThrowsInvalidProductDataException() {
        // Arrange
        Inventory inventory = new Inventory();

        // Act
        InvalidProductDataException exception = assertThrows(
                InvalidProductDataException.class,
                () -> inventory.availableQuantity("product-1"));

        // Assert
        assertTrue(exception.getMessage().contains("product-1"));
        assertTrue(exception.getMessage().contains("unregistered"));
    }

    @Test
    void givenPositiveIncrease_whenStockIsAdded_thenAvailableQuantityIncreases() {
        // Arrange
        Inventory inventory = new Inventory();
        inventory.register("product-1", 10);

        // Act
        inventory.addStock("product-1", 5);

        // Assert
        assertEquals(15, inventory.availableQuantity("product-1"));
    }

    @Test
    void givenNullOrBlankProductId_whenStockIsAdded_thenThrowsInvalidProductDataException() {
        // Arrange
        Inventory inventory = new Inventory();

        // Act
        InvalidProductDataException nullIdException = assertThrows(
                InvalidProductDataException.class,
                () -> inventory.addStock(null, 5));
        InvalidProductDataException blankIdException = assertThrows(
                InvalidProductDataException.class,
                () -> inventory.addStock(" ", 5));

        // Assert
        assertTrue(nullIdException.getMessage().contains("null"));
        assertTrue(nullIdException.getMessage().contains("5"));
        assertTrue(blankIdException.getMessage().contains("blank"));
        assertTrue(blankIdException.getMessage().contains("5"));
        assertTrue(inventory.snapshot().isEmpty());
    }

    @Test
    void givenUnregisteredProduct_whenStockIsAdded_thenThrowsAndPreservesInventory() {
        // Arrange
        Inventory inventory = new Inventory();

        // Act
        InvalidProductDataException exception = assertThrows(
                InvalidProductDataException.class,
                () -> inventory.addStock("product-1", 5));

        // Assert
        assertTrue(exception.getMessage().contains("product-1"));
        assertTrue(exception.getMessage().contains("5"));
        assertTrue(inventory.snapshot().isEmpty());
    }

    @Test
    void givenQuantityAtIntegerMaximum_whenStockIsAdded_thenThrowsAndPreservesQuantity() {
        // Arrange
        Inventory inventory = new Inventory();
        inventory.register("product-1", Integer.MAX_VALUE);

        // Act
        InvalidProductDataException exception = assertThrows(
                InvalidProductDataException.class,
                () -> inventory.addStock("product-1", 1));

        // Assert
        assertTrue(exception.getMessage().contains("product-1"));
        assertTrue(exception.getMessage().contains(String.valueOf(Integer.MAX_VALUE)));
        assertTrue(exception.getMessage().contains("1"));
        assertEquals(Integer.MAX_VALUE, inventory.availableQuantity("product-1"));
    }

    @Test
    void givenNonPositiveIncrease_whenStockIsAdded_thenThrowsInvalidProductDataException() {
        // Arrange
        Inventory inventory = new Inventory();
        inventory.register("product-1", 10);

        // Act
        InvalidProductDataException zeroQuantityException = assertThrows(
                InvalidProductDataException.class,
                () -> inventory.addStock("product-1", 0));
        InvalidProductDataException negativeQuantityException = assertThrows(
                InvalidProductDataException.class,
                () -> inventory.addStock("product-1", -1));

        // Assert
        assertTrue(zeroQuantityException.getMessage().contains("product-1"));
        assertTrue(zeroQuantityException.getMessage().contains("0"));
        assertTrue(negativeQuantityException.getMessage().contains("product-1"));
        assertTrue(negativeQuantityException.getMessage().contains("-1"));
        assertEquals(10, inventory.availableQuantity("product-1"));
    }

    @Test
    void givenRegisteredProducts_whenInventorySnapshotIsReturned_thenCallerCannotMutateIt() {
        // Arrange
        Inventory inventory = new Inventory();
        inventory.register("product-1", 10);
        Map<String, Integer> snapshot = inventory.snapshot();

        // Act
        assertThrows(
                UnsupportedOperationException.class,
                () -> snapshot.put("product-1", 20));

        // Assert
        assertEquals(10, snapshot.get("product-1"));
        assertEquals(10, inventory.availableQuantity("product-1"));
    }
}
