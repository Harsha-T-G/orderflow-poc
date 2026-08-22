package com.codewalnut.orderflow.core.service.catalog;

import com.codewalnut.orderflow.core.service.inventory.Inventory;
import com.codewalnut.orderflow.core.exception.DuplicateProductException;
import com.codewalnut.orderflow.core.exception.InvalidProductDataException;
import com.codewalnut.orderflow.core.exception.ProductNotFoundException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.codewalnut.orderflow.core.domain.catalog.Product;
import com.codewalnut.orderflow.core.domain.catalog.ProductStatus;

class ProductCatalogTest {

    @Test
    void givenNewProduct_whenAdded_thenProductAndInitialInventoryAreStored() {
        // Arrange
        Inventory inventory = new Inventory();
        ProductCatalog catalog = new ProductCatalog(inventory);
        Product product = product("P-100", "Keyboard", "Accessories", "79.99", Set.of("wireless"), 4);

        // Act
        catalog.add(product, 12);

        // Assert
        assertSame(product, catalog.findById("P-100"));
        assertEquals(12, inventory.availableQuantity("P-100"));
    }

    @Test
    void givenExistingProductId_whenAddedAgain_thenThrowsDuplicateProductException() {
        // Arrange
        Inventory inventory = new Inventory();
        ProductCatalog catalog = new ProductCatalog(inventory);
        Product original = product("P-100", "Keyboard", "Accessories", "79.99", Set.of(), 4);
        Product duplicate = product("P-100", "Different", "Other", "10.00", Set.of(), 1);
        catalog.add(original, 12);

        // Act
        DuplicateProductException exception = assertThrows(
                DuplicateProductException.class,
                () -> catalog.add(duplicate, 99));

        // Assert
        assertEquals("Product P-100 already exists", exception.getMessage());
        assertSame(original, catalog.findById("P-100"));
        assertEquals(12, inventory.availableQuantity("P-100"));
    }

    @Test
    void givenUnknownProductId_whenFound_thenThrowsProductNotFoundException() {
        // Arrange
        ProductCatalog catalog = new ProductCatalog(new Inventory());

        // Act
        ProductNotFoundException exception = assertThrows(
                ProductNotFoundException.class,
                () -> catalog.findById("missing-42"));

        // Assert
        assertEquals("Product missing-42 was not found", exception.getMessage());
    }

    @Test
    void givenProductId_whenDetailsAreUpdated_thenControlledFieldsChange() {
        // Arrange
        ProductCatalog catalog = new ProductCatalog(new Inventory());
        Product product = product("P-100", "Keyboard", "Accessories", "79.99", Set.of("wireless"), 4);
        catalog.add(product, 12);

        // Act
        catalog.updateDetails(
                "P-100",
                "Mechanical Keyboard",
                "Peripherals",
                new BigDecimal("99.995"),
                Set.of("mechanical", "rgb"),
                7);

        // Assert
        Product updated = catalog.findById("P-100");
        assertEquals("Mechanical Keyboard", updated.getName());
        assertEquals("Peripherals", updated.getCategory());
        assertEquals(new BigDecimal("100.00"), updated.getPrice());
        assertEquals(Set.of("mechanical", "rgb"), updated.getTags());
        assertEquals(7, updated.getReorderLevel());
    }

    @Test
    void givenProducts_whenFilteredByCategory_thenMatchingImmutableListIsReturned() {
        // Arrange
        ProductCatalog catalog = new ProductCatalog(new Inventory());
        Product keyboard = product("P-100", "Keyboard", "Accessories", "79.99", Set.of(), 4);
        Product mouse = product("P-200", "Mouse", "accessories", "29.99", Set.of(), 3);
        Product monitor = product("P-300", "Monitor", "Displays", "199.99", Set.of(), 2);
        catalog.add(keyboard, 12);
        catalog.add(mouse, 8);
        catalog.add(monitor, 5);

        // Act
        List<Product> matches = catalog.findByCategory("ACCESSORIES");

        // Assert
        assertEquals(Set.of(keyboard, mouse), Set.copyOf(matches));
        assertThrows(UnsupportedOperationException.class, () -> matches.add(monitor));
    }

    @Test
    void givenNullCategory_whenProductsAreFiltered_thenThrowsContextualInvalidProductDataException() {
        // Arrange
        ProductCatalog catalog = new ProductCatalog(new Inventory());

        // Act
        InvalidProductDataException exception = assertThrows(
                InvalidProductDataException.class,
                () -> catalog.findByCategory(null));

        // Assert
        assertEquals("Product category query must not be null", exception.getMessage());
    }

    @Test
    void givenBlankCategory_whenProductsAreFiltered_thenThrowsContextualInvalidProductDataException() {
        // Arrange
        ProductCatalog catalog = new ProductCatalog(new Inventory());

        // Act
        InvalidProductDataException exception = assertThrows(
                InvalidProductDataException.class,
                () -> catalog.findByCategory("   "));

        // Assert
        assertEquals("Product category query must not be blank", exception.getMessage());
    }

    @Test
    void givenMixedCaseTag_whenProductsAreSearched_thenMatchingProductsAreReturned() {
        // Arrange
        ProductCatalog catalog = new ProductCatalog(new Inventory());
        Product keyboard = product("P-100", "Keyboard", "Accessories", "79.99", Set.of("Wireless"), 4);
        Product mouse = product("P-200", "Mouse", "Accessories", "29.99", Set.of("WIRELESS", "compact"), 3);
        Product monitor = product("P-300", "Monitor", "Displays", "199.99", Set.of("desktop"), 2);
        catalog.add(keyboard, 12);
        catalog.add(mouse, 8);
        catalog.add(monitor, 5);

        // Act
        List<Product> matches = catalog.findByTag("wireLESS");

        // Assert
        assertEquals(Set.of(keyboard, mouse), Set.copyOf(matches));
        assertThrows(UnsupportedOperationException.class, () -> matches.add(monitor));
    }

    @Test
    void givenNullTag_whenProductsAreSearched_thenThrowsContextualInvalidProductDataException() {
        // Arrange
        ProductCatalog catalog = new ProductCatalog(new Inventory());

        // Act
        InvalidProductDataException exception = assertThrows(
                InvalidProductDataException.class,
                () -> catalog.findByTag(null));

        // Assert
        assertEquals("Product tag query must not be null", exception.getMessage());
    }

    @Test
    void givenBlankTag_whenProductsAreSearched_thenThrowsContextualInvalidProductDataException() {
        // Arrange
        ProductCatalog catalog = new ProductCatalog(new Inventory());

        // Act
        InvalidProductDataException exception = assertThrows(
                InvalidProductDataException.class,
                () -> catalog.findByTag("\t"));

        // Assert
        assertEquals("Product tag query must not be blank", exception.getMessage());
    }

    @Test
    void givenProductsWithMatchingNames_whenSortedByName_thenOrderedByNameThenIdImmutably() {
        // Arrange
        ProductCatalog catalog = new ProductCatalog(new Inventory());
        Product sameC = product("id-c", "Same", "Accessories", "10.00", Set.of(), 1);
        Product apple = product("id-apple", "Apple", "Accessories", "20.00", Set.of(), 1);
        Product sameA = product("id-a", "same", "Accessories", "30.00", Set.of(), 1);
        Product sameB = product("id-b", "SAME", "Accessories", "40.00", Set.of(), 1);
        Product sameD = product("id-d", "Same", "Accessories", "50.00", Set.of(), 1);
        catalog.add(sameC, 1);
        catalog.add(apple, 1);
        catalog.add(sameA, 1);
        catalog.add(sameB, 1);
        catalog.add(sameD, 1);

        // Act
        List<Product> byName = catalog.sortedByName();

        // Assert
        assertEquals(List.of(apple, sameA, sameB, sameC, sameD), byName);
        assertThrows(UnsupportedOperationException.class, () -> byName.add(apple));
    }

    @Test
    void givenProductsWithMatchingPrices_whenSortedByPrice_thenOrderedByPriceThenIdImmutably() {
        // Arrange
        ProductCatalog catalog = new ProductCatalog(new Inventory());
        Product pricey = product("id-pricey", "Pricey", "Accessories", "50.00", Set.of(), 1);
        Product midC = product("id-c", "MidC", "Accessories", "20.00", Set.of(), 1);
        Product midA = product("id-a", "MidA", "Accessories", "20.00", Set.of(), 1);
        Product midB = product("id-b", "MidB", "Accessories", "20.00", Set.of(), 1);
        Product midD = product("id-d", "MidD", "Accessories", "20.00", Set.of(), 1);
        Product cheap = product("id-cheap", "Cheap", "Accessories", "10.00", Set.of(), 1);
        catalog.add(pricey, 1);
        catalog.add(midC, 1);
        catalog.add(midA, 1);
        catalog.add(midB, 1);
        catalog.add(midD, 1);
        catalog.add(cheap, 1);

        // Act
        List<Product> byPrice = catalog.sortedByPrice();

        // Assert
        assertEquals(List.of(cheap, midA, midB, midC, midD, pricey), byPrice);
        assertThrows(UnsupportedOperationException.class, () -> byPrice.add(cheap));
    }

    @Test
    void givenProductsWithMatchingQuantities_whenSortedByAvailableQuantity_thenOrderedByQuantityThenIdImmutably() {
        // Arrange
        Inventory inventory = new Inventory();
        ProductCatalog catalog = new ProductCatalog(inventory);
        Product high = product("id-high", "High", "Accessories", "10.00", Set.of(), 1);
        Product midC = product("id-c", "MidC", "Accessories", "20.00", Set.of(), 1);
        Product midA = product("id-a", "MidA", "Accessories", "30.00", Set.of(), 1);
        Product midB = product("id-b", "MidB", "Accessories", "40.00", Set.of(), 1);
        Product midD = product("id-d", "MidD", "Accessories", "50.00", Set.of(), 1);
        Product low = product("id-low", "Low", "Accessories", "60.00", Set.of(), 1);
        catalog.add(high, 9);
        catalog.add(midC, 5);
        catalog.add(midA, 5);
        catalog.add(midB, 5);
        catalog.add(midD, 5);
        catalog.add(low, 1);

        // Act
        List<Product> byQuantity = catalog.sortedByAvailableQuantity();

        // Assert
        assertEquals(List.of(low, midA, midB, midC, midD, high), byQuantity);
        assertThrows(UnsupportedOperationException.class, () -> byQuantity.add(low));
    }

    @Test
    void givenProductsAtOrBelowReorderLevel_whenLowStockIsQueried_thenSortedMatchesAreReturned() {
        // Arrange
        ProductCatalog catalog = new ProductCatalog(new Inventory());
        Product mouse = product("P-100", "Mouse", "Accessories", "29.99", Set.of(), 2);
        Product monitor = product("P-200", "Monitor", "Displays", "199.99", Set.of(), 3);
        Product keyboard = product("P-300", "Keyboard", "Accessories", "79.99", Set.of(), 5);
        Product dock = product("P-400", "Dock", "Accessories", "149.99", Set.of(), 7);
        Product twinB = product("id-b", "Twin", "Accessories", "15.00", Set.of(), 2);
        Product twinA = product("id-a", "twin", "Accessories", "16.00", Set.of(), 2);
        catalog.add(mouse, 2);
        catalog.add(monitor, 2);
        catalog.add(keyboard, 5);
        catalog.add(dock, 8);
        catalog.add(twinB, 2);
        catalog.add(twinA, 2);

        // Act
        List<Product> lowStock = catalog.findLowStockProducts();

        // Assert
        assertEquals(List.of(monitor, mouse, twinA, twinB, keyboard), lowStock);
        assertThrows(UnsupportedOperationException.class, () -> lowStock.add(dock));
    }

    @Test
    void givenInactiveProduct_whenActivated_thenStatusBecomesActive() {
        // Arrange
        ProductCatalog catalog = new ProductCatalog(new Inventory());
        Product product = product("P-100", "Keyboard", "Accessories", "79.99", Set.of(), 4);
        catalog.add(product, 12);
        catalog.deactivate("P-100");

        // Act
        catalog.activate("P-100");

        // Assert
        assertEquals(ProductStatus.ACTIVE, catalog.findById("P-100").getStatus());
    }

    @Test
    void givenActiveProduct_whenDeactivated_thenStatusBecomesInactive() {
        // Arrange
        ProductCatalog catalog = new ProductCatalog(new Inventory());
        Product product = product("P-100", "Keyboard", "Accessories", "79.99", Set.of(), 4);
        catalog.add(product, 12);

        // Act
        catalog.deactivate("P-100");

        // Assert
        assertEquals(ProductStatus.INACTIVE, catalog.findById("P-100").getStatus());
    }

    @Test
    void givenKnownProduct_whenStockIsIncreased_thenAvailableQuantityIncreases() {
        // Arrange
        Inventory inventory = new Inventory();
        ProductCatalog catalog = new ProductCatalog(inventory);
        catalog.add(product("P-100", "Keyboard", "Accessories", "79.99", Set.of(), 4), 12);

        // Act
        catalog.addStock("P-100", 5);

        // Assert
        assertEquals(17, inventory.availableQuantity("P-100"));
    }

    @Test
    void givenUnknownProductId_whenControlledOperationsRun_thenThrowsContextualProductNotFoundException() {
        // Arrange
        ProductCatalog catalog = new ProductCatalog(new Inventory());

        // Act
        ProductNotFoundException activateException = assertThrows(
                ProductNotFoundException.class,
                () -> catalog.activate("missing-42"));
        ProductNotFoundException stockException = assertThrows(
                ProductNotFoundException.class,
                () -> catalog.addStock("missing-42", 3));

        // Assert
        assertEquals("Product missing-42 was not found", activateException.getMessage());
        assertEquals("Product missing-42 was not found", stockException.getMessage());
    }

    @Test
    void givenNonPositiveStockIncrease_whenStockIsAdded_thenQuantityIsUnchanged() {
        // Arrange
        Inventory inventory = new Inventory();
        ProductCatalog catalog = new ProductCatalog(inventory);
        catalog.add(product("P-100", "Keyboard", "Accessories", "79.99", Set.of(), 4), 12);

        // Act
        InvalidProductDataException exception = assertThrows(
                InvalidProductDataException.class,
                () -> catalog.addStock("P-100", 0));

        // Assert
        assertEquals("Stock increase for product P-100 must be positive: 0", exception.getMessage());
        assertEquals(12, inventory.availableQuantity("P-100"));
    }

    @Test
    void givenNegativeInitialQuantity_whenProductIsAdded_thenCatalogAndInventoryRemainWithoutProduct() {
        // Arrange
        Inventory inventory = new Inventory();
        ProductCatalog catalog = new ProductCatalog(inventory);
        Product product = product("P-100", "Keyboard", "Accessories", "79.99", Set.of(), 4);

        // Act
        InvalidProductDataException exception = assertThrows(
                InvalidProductDataException.class,
                () -> catalog.add(product, -1));

        // Assert
        assertEquals("Initial quantity for product P-100 cannot be negative: -1", exception.getMessage());
        assertThrows(ProductNotFoundException.class, () -> catalog.findById("P-100"));
        assertFalse(inventory.snapshot().containsKey("P-100"));
    }

    @Test
    void givenRegisteredProduct_whenCatalogAvailableQuantityIsRead_thenValueComesFromInventory() {
        // Arrange
        Inventory inventory = new Inventory();
        ProductCatalog catalog = new ProductCatalog(inventory);
        catalog.add(product("P-100", "Keyboard", "Accessories", "79.99", Set.of(), 4), 7);

        // Act
        int availableQuantity = catalog.availableQuantity("P-100");
        catalog.addStock("P-100", 3);

        // Assert
        assertEquals(7, availableQuantity);
        assertEquals(10, catalog.availableQuantity("P-100"));
        assertEquals(10, inventory.availableQuantity("P-100"));
        assertTrue(java.util.Arrays.stream(Product.class.getDeclaredFields())
                .noneMatch(field -> field.getName().toLowerCase().contains("quantity")));
    }

    private Product product(
            String id,
            String name,
            String category,
            String price,
            Set<String> tags,
            int reorderLevel) {
        return new Product(id, name, category, new BigDecimal(price), tags, reorderLevel);
    }
}
