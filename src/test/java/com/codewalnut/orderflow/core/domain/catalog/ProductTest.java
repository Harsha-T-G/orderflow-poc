package com.codewalnut.orderflow.core.domain.catalog;

import com.codewalnut.orderflow.core.exception.InvalidMonetaryValueException;
import com.codewalnut.orderflow.core.exception.InvalidProductDataException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProductTest {

    @Test
    void givenValidData_whenProductIsCreated_thenExposesNormalizedImmutableState() {
        // Arrange
        Set<String> tags = new HashSet<>(Set.of("office", "wireless"));

        // Act
        Product product = new Product(
                "product-1",
                "Wireless Keyboard",
                "Accessories",
                new BigDecimal("99.995"),
                tags,
                5);

        // Assert
        assertEquals("product-1", product.getId());
        assertEquals("Wireless Keyboard", product.getName());
        assertEquals("Accessories", product.getCategory());
        assertEquals(new BigDecimal("100.00"), product.getPrice());
        assertEquals(Set.of("office", "wireless"), product.getTags());
        assertEquals(5, product.getReorderLevel());
        assertEquals(ProductStatus.ACTIVE, product.getStatus());
    }

    @Test
    void givenBlankId_whenProductIsCreated_thenThrowsInvalidProductDataException() {
        // Arrange
        String blankId = " ";

        // Act
        InvalidProductDataException exception = assertThrows(
                InvalidProductDataException.class,
                () -> new Product(
                        blankId,
                        "Wireless Keyboard",
                        "Accessories",
                        new BigDecimal("99.99"),
                        Set.of("office"),
                        5));

        // Assert
        assertEquals("Product id must not be blank", exception.getMessage());
    }

    @Test
    void givenBlankName_whenProductIsCreated_thenThrowsInvalidProductDataException() {
        // Arrange
        String blankName = "";

        // Act
        InvalidProductDataException exception = assertThrows(
                InvalidProductDataException.class,
                () -> new Product(
                        "product-1",
                        blankName,
                        "Accessories",
                        new BigDecimal("99.99"),
                        Set.of("office"),
                        5));

        // Assert
        assertEquals("Product name must not be blank", exception.getMessage());
    }

    @Test
    void givenBlankCategory_whenProductIsCreated_thenThrowsInvalidProductDataException() {
        // Arrange
        String blankCategory = "\t";

        // Act
        InvalidProductDataException exception = assertThrows(
                InvalidProductDataException.class,
                () -> new Product(
                        "product-1",
                        "Wireless Keyboard",
                        blankCategory,
                        new BigDecimal("99.99"),
                        Set.of("office"),
                        5));

        // Assert
        assertEquals("Product category must not be blank", exception.getMessage());
    }

    @Test
    void givenNonPositivePrice_whenProductIsCreated_thenThrowsInvalidMonetaryValueException() {
        // Arrange
        BigDecimal nonPositivePrice = BigDecimal.ZERO;

        // Act
        InvalidMonetaryValueException exception = assertThrows(
                InvalidMonetaryValueException.class,
                () -> new Product(
                        "product-1",
                        "Wireless Keyboard",
                        "Accessories",
                        nonPositivePrice,
                        Set.of("office"),
                        5));

        // Assert
        assertEquals("Product price must be positive: 0", exception.getMessage());
    }

    @Test
    void givenNegativeReorderLevel_whenProductIsCreated_thenThrowsInvalidProductDataException() {
        // Arrange
        int negativeReorderLevel = -1;

        // Act
        InvalidProductDataException exception = assertThrows(
                InvalidProductDataException.class,
                () -> new Product(
                        "product-1",
                        "Wireless Keyboard",
                        "Accessories",
                        new BigDecimal("99.99"),
                        Set.of("office"),
                        negativeReorderLevel));

        // Assert
        assertEquals("Product reorder level must not be negative: -1", exception.getMessage());
    }

    @Test
    void givenMutableTags_whenProductIsCreated_thenStoresImmutableTagCopy() {
        // Arrange
        Set<String> mutableTags = new HashSet<>(Set.of("office"));
        Product product = new Product(
                "product-1",
                "Wireless Keyboard",
                "Accessories",
                new BigDecimal("99.99"),
                mutableTags,
                5);

        // Act
        mutableTags.add("modified-externally");

        // Assert
        assertFalse(product.getTags().contains("modified-externally"));
        assertThrows(UnsupportedOperationException.class, () -> product.getTags().add("another"));
    }

    @Test
    void givenNullTagCollection_whenProductIsCreated_thenThrowsInvalidProductDataException() {
        // Arrange
        Set<String> nullTags = null;

        // Act
        InvalidProductDataException exception = assertThrows(
                InvalidProductDataException.class,
                () -> new Product(
                        "product-1",
                        "Wireless Keyboard",
                        "Accessories",
                        new BigDecimal("99.99"),
                        nullTags,
                        5));

        // Assert
        assertEquals("Product tags must not be null", exception.getMessage());
    }

    @Test
    void givenProduct_whenDetailsAreUpdated_thenIdRemainsUnchanged() {
        // Arrange
        Product product = new Product(
                "product-1",
                "Wireless Keyboard",
                "Accessories",
                new BigDecimal("99.99"),
                Set.of("office"),
                5);

        // Act
        product.updateDetails(
                "Mechanical Keyboard",
                "Peripherals",
                new BigDecimal("149.995"),
                Set.of("gaming"),
                8);

        // Assert
        assertEquals("product-1", product.getId());
        assertEquals("Mechanical Keyboard", product.getName());
        assertEquals("Peripherals", product.getCategory());
        assertEquals(new BigDecimal("150.00"), product.getPrice());
        assertEquals(Set.of("gaming"), product.getTags());
        assertEquals(8, product.getReorderLevel());
    }

    @Test
    void givenNullTagElement_whenProductDetailsAreUpdated_thenThrowsAndLeavesStateUnchanged() {
        // Arrange
        Product product = new Product(
                "product-1",
                "Wireless Keyboard",
                "Accessories",
                new BigDecimal("99.99"),
                Set.of("office"),
                5);
        product.deactivate();
        Set<String> tagsContainingNull = new HashSet<>();
        tagsContainingNull.add("gaming");
        tagsContainingNull.add(null);

        // Act
        InvalidProductDataException exception = assertThrows(
                InvalidProductDataException.class,
                () -> product.updateDetails(
                        "Mechanical Keyboard",
                        "Peripherals",
                        new BigDecimal("149.99"),
                        tagsContainingNull,
                        8));

        // Assert
        assertEquals("Product tags must not contain null elements", exception.getMessage());
        assertEquals("product-1", product.getId());
        assertEquals("Wireless Keyboard", product.getName());
        assertEquals("Accessories", product.getCategory());
        assertEquals(new BigDecimal("99.99"), product.getPrice());
        assertEquals(Set.of("office"), product.getTags());
        assertEquals(5, product.getReorderLevel());
        assertEquals(ProductStatus.INACTIVE, product.getStatus());
    }

    @Test
    void givenProduct_whenActivatedOrDeactivated_thenStatusChangesThroughDomainOperations() {
        // Arrange
        Product product = new Product(
                "product-1",
                "Wireless Keyboard",
                "Accessories",
                new BigDecimal("99.99"),
                Set.of("office"),
                5);

        // Act
        product.deactivate();
        ProductStatus deactivatedStatus = product.getStatus();
        product.activate();
        ProductStatus activatedStatus = product.getStatus();

        // Assert
        assertEquals(ProductStatus.INACTIVE, deactivatedStatus);
        assertEquals(ProductStatus.ACTIVE, activatedStatus);
    }
}
