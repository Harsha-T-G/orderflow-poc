package com.codewalnut.orderflow.core.service.order.validation;

import com.codewalnut.orderflow.core.domain.catalog.Product;
import com.codewalnut.orderflow.core.service.catalog.ProductCatalog;
import com.codewalnut.orderflow.core.domain.customer.Customer;
import com.codewalnut.orderflow.core.service.customer.CustomerDirectory;
import com.codewalnut.orderflow.core.domain.customer.CustomerType;
import com.codewalnut.orderflow.core.service.inventory.Inventory;
import com.codewalnut.orderflow.core.domain.order.OrderRequest;
import com.codewalnut.orderflow.core.domain.order.RequestedProduct;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.codewalnut.orderflow.core.domain.catalog.ProductStatus;

class OrderValidationPipelineTest {

    @Test
    void givenNoRequestedProducts_whenValidated_thenReturnsNamedFailure() {
        // Arrange
        Inventory inventory = new Inventory();
        ProductCatalog catalog = new ProductCatalog(inventory);
        CustomerDirectory customers = new CustomerDirectory();
        customers.register(new Customer(
                "C-100",
                "Alice Example",
                "alice@example.com",
                CustomerType.REGULAR));
        OrderRequest request = new OrderRequest("C-100", List.of());
        OrderValidationContext context = new OrderValidationContext(
                request, customers, catalog, inventory);
        OrderValidationPipeline pipeline = new OrderValidationPipeline(
                List.of(OrderValidationRule.nonEmptyRequest()));

        // Act
        List<ValidationResult> results = pipeline.evaluate(context);

        // Assert
        assertEquals(1, results.size());
        ValidationResult result = results.getFirst();
        assertEquals("Non-empty request", result.ruleName());
        assertFalse(result.passed());
        assertEquals("Order request must contain at least one product", result.failureMessage());
    }

    @Test
    void givenNonPositiveQuantity_whenValidated_thenReturnsNamedFailure() {
        // Arrange
        Inventory inventory = new Inventory();
        ProductCatalog catalog = new ProductCatalog(inventory);
        CustomerDirectory customers = new CustomerDirectory();
        customers.register(new Customer(
                "C-100",
                "Alice Example",
                "alice@example.com",
                CustomerType.REGULAR));
        OrderRequest request = new OrderRequest(
                "C-100",
                List.of(new RequestedProduct("P-1", 0), new RequestedProduct("P-2", -3)));
        OrderValidationContext context = new OrderValidationContext(
                request, customers, catalog, inventory);
        OrderValidationPipeline pipeline = new OrderValidationPipeline(
                List.of(OrderValidationRule.positiveQuantities()));

        // Act
        List<ValidationResult> results = pipeline.evaluate(context);

        // Assert
        assertEquals(1, results.size());
        ValidationResult result = results.getFirst();
        assertEquals("Positive quantities", result.ruleName());
        assertFalse(result.passed());
        assertEquals(
                "Requested quantities must be positive; invalid entries: P-1=0, P-2=-3",
                result.failureMessage());
    }

    @Test
    void givenUnknownCustomer_whenValidated_thenReturnsNamedFailure() {
        // Arrange
        Inventory inventory = new Inventory();
        ProductCatalog catalog = new ProductCatalog(inventory);
        CustomerDirectory customers = new CustomerDirectory();
        OrderRequest request = new OrderRequest(
                "missing-customer",
                List.of(new RequestedProduct("P-1", 1)));
        OrderValidationContext context = new OrderValidationContext(
                request, customers, catalog, inventory);
        OrderValidationPipeline pipeline = new OrderValidationPipeline(
                List.of(OrderValidationRule.customerExists()));

        // Act
        List<ValidationResult> results = pipeline.evaluate(context);

        // Assert
        assertEquals(1, results.size());
        ValidationResult result = results.getFirst();
        assertEquals("Customer exists", result.ruleName());
        assertFalse(result.passed());
        assertEquals("Customer missing-customer was not found", result.failureMessage());
    }

    @Test
    void givenUnknownProduct_whenValidated_thenReturnsNamedFailure() {
        // Arrange
        Inventory inventory = new Inventory();
        ProductCatalog catalog = new ProductCatalog(inventory);
        CustomerDirectory customers = new CustomerDirectory();
        customers.register(new Customer(
                "C-100",
                "Alice Example",
                "alice@example.com",
                CustomerType.REGULAR));
        OrderRequest request = new OrderRequest(
                "C-100",
                List.of(new RequestedProduct("missing-product", 2)));
        OrderValidationContext context = new OrderValidationContext(
                request, customers, catalog, inventory);
        OrderValidationPipeline pipeline = new OrderValidationPipeline(
                List.of(OrderValidationRule.productExists()));

        // Act
        List<ValidationResult> results = pipeline.evaluate(context);

        // Assert
        assertEquals(1, results.size());
        ValidationResult result = results.getFirst();
        assertEquals("Product exists", result.ruleName());
        assertFalse(result.passed());
        assertEquals("Unknown products: missing-product", result.failureMessage());
    }

    @Test
    void givenInactiveProduct_whenValidated_thenReturnsNamedFailure() {
        // Arrange
        Inventory inventory = new Inventory();
        ProductCatalog catalog = new ProductCatalog(inventory);
        CustomerDirectory customers = new CustomerDirectory();
        customers.register(new Customer(
                "C-100",
                "Alice Example",
                "alice@example.com",
                CustomerType.REGULAR));
        catalog.add(
                new Product("P-1", "Widget", "Tools", new BigDecimal("10.00"), Set.of("metal"), 2),
                5);
        catalog.deactivate("P-1");
        OrderRequest request = new OrderRequest(
                "C-100",
                List.of(new RequestedProduct("P-1", 1)));
        OrderValidationContext context = new OrderValidationContext(
                request, customers, catalog, inventory);
        OrderValidationPipeline pipeline = new OrderValidationPipeline(
                List.of(OrderValidationRule.activeProducts()));

        // Act
        List<ValidationResult> results = pipeline.evaluate(context);

        // Assert
        assertEquals(1, results.size());
        ValidationResult result = results.getFirst();
        assertEquals("Active products", result.ruleName());
        assertFalse(result.passed());
        assertEquals("Inactive products cannot be ordered: P-1", result.failureMessage());
    }

    @Test
    void givenInsufficientCurrentStock_whenValidated_thenReturnsNamedFailure() {
        // Arrange
        Inventory inventory = new Inventory();
        ProductCatalog catalog = new ProductCatalog(inventory);
        CustomerDirectory customers = new CustomerDirectory();
        customers.register(new Customer(
                "C-100",
                "Alice Example",
                "alice@example.com",
                CustomerType.REGULAR));
        catalog.add(
                new Product("P-1", "Widget", "Tools", new BigDecimal("10.00"), Set.of("metal"), 2),
                3);
        OrderRequest request = new OrderRequest(
                "C-100",
                List.of(new RequestedProduct("P-1", 2), new RequestedProduct("P-1", 2)));
        OrderValidationContext context = new OrderValidationContext(
                request, customers, catalog, inventory);
        OrderValidationPipeline pipeline = new OrderValidationPipeline(
                List.of(OrderValidationRule.availableStock()));

        // Act
        List<ValidationResult> results = pipeline.evaluate(context);

        // Assert
        assertEquals(1, results.size());
        ValidationResult result = results.getFirst();
        assertEquals("Available stock", result.ruleName());
        assertFalse(result.passed());
        assertEquals(
                "Insufficient available stock for products: P-1 requested=4 available=3",
                result.failureMessage());
        assertEquals(3, inventory.availableQuantity("P-1"));
    }

    @Test
    void givenDuplicateRequestsOverflowingIntAggregation_whenAvailableStockValidated_thenReturnsNamedFailure() {
        // Arrange
        Inventory inventory = new Inventory();
        ProductCatalog catalog = new ProductCatalog(inventory);
        CustomerDirectory customers = new CustomerDirectory();
        customers.register(new Customer(
                "C-100",
                "Alice Example",
                "alice@example.com",
                CustomerType.REGULAR));
        catalog.add(
                new Product("P-1", "Widget", "Tools", new BigDecimal("10.00"), Set.of("metal"), 2),
                5);
        OrderRequest request = new OrderRequest(
                "C-100",
                List.of(
                        new RequestedProduct("P-1", Integer.MAX_VALUE),
                        new RequestedProduct("P-1", 1)));
        OrderValidationContext context = new OrderValidationContext(
                request, customers, catalog, inventory);
        OrderValidationPipeline pipeline = new OrderValidationPipeline(
                List.of(OrderValidationRule.availableStock()));

        // Act
        List<ValidationResult> results = pipeline.evaluate(context);

        // Assert
        assertEquals(1, results.size());
        ValidationResult result = results.getFirst();
        assertEquals("Available stock", result.ruleName());
        assertFalse(result.passed());
        assertEquals(
                "Requested quantity overflow for products: P-1 quantities="
                        + Integer.MAX_VALUE + "+1",
                result.failureMessage());
        assertEquals(5, inventory.availableQuantity("P-1"));
    }

    @Test
    void givenMultipleRules_whenPipelineRuns_thenResultsRemainOrderedAndImmutable() {
        // Arrange
        Inventory inventory = new Inventory();
        ProductCatalog catalog = new ProductCatalog(inventory);
        CustomerDirectory customers = new CustomerDirectory();
        OrderRequest request = new OrderRequest(
                "missing-customer",
                List.of(new RequestedProduct("missing-product", 0)));
        OrderValidationContext context = new OrderValidationContext(
                request, customers, catalog, inventory);
        OrderValidationRule customRule = validationContext -> ValidationResult.fail(
                "Custom rule",
                "Custom rule always fails for composition check");
        List<OrderValidationRule> composedRules = new ArrayList<>();
        composedRules.add(OrderValidationRule.customerExists());
        composedRules.add(OrderValidationRule.nonEmptyRequest());
        composedRules.add(OrderValidationRule.positiveQuantities());
        composedRules.add(OrderValidationRule.productExists());
        composedRules.add(OrderValidationRule.activeProducts());
        composedRules.add(OrderValidationRule.availableStock());
        composedRules.add(customRule);
        OrderValidationPipeline pipeline = new OrderValidationPipeline(composedRules);

        // Act
        List<ValidationResult> results = pipeline.evaluate(context);

        // Assert
        assertEquals(7, results.size());
        assertEquals(
                List.of(
                        "Customer exists",
                        "Non-empty request",
                        "Positive quantities",
                        "Product exists",
                        "Active products",
                        "Available stock",
                        "Custom rule"),
                results.stream().map(ValidationResult::ruleName).toList());
        assertFalse(results.get(0).passed());
        assertTrue(results.get(1).passed());
        assertFalse(results.get(2).passed());
        assertFalse(results.get(3).passed());
        assertTrue(results.get(4).passed());
        assertFalse(results.get(5).passed());
        assertFalse(results.get(6).passed());
        assertThrows(UnsupportedOperationException.class, () -> results.add(
                ValidationResult.pass("Must remain immutable")));
    }
}
