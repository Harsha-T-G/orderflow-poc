package com.codewalnut.orderflow.core.service.catalog;

import com.codewalnut.orderflow.core.domain.catalog.Product;
import com.codewalnut.orderflow.core.exception.DuplicateProductException;
import com.codewalnut.orderflow.core.exception.InvalidProductDataException;
import com.codewalnut.orderflow.core.exception.ProductNotFoundException;
import com.codewalnut.orderflow.core.service.inventory.Inventory;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class ProductCatalog {
    private final Inventory inventory;
    private final Map<String, Product> productsById = new HashMap<>();

    public ProductCatalog(Inventory inventory) {
        this.inventory = Objects.requireNonNull(inventory, "inventory must not be null");
    }

    public synchronized void add(Product product, int initialQuantity) {
        Objects.requireNonNull(product, "product must not be null");
        if (productsById.containsKey(product.getId())) {
            throw new DuplicateProductException(product.getId());
        }
        inventory.register(product.getId(), initialQuantity);
        productsById.put(product.getId(), product);
    }

    public Product findById(String productId) {
        Product product = productsById.get(productId);
        if (product == null) {
            throw new ProductNotFoundException(productId);
        }
        return product;
    }

    public void updateDetails(
            String productId,
            String name,
            String category,
            BigDecimal price,
            Set<String> tags,
            int reorderLevel) {
        findById(productId).updateDetails(name, category, price, tags, reorderLevel);
    }

    public void activate(String productId) {
        findById(productId).activate();
    }

    public void deactivate(String productId) {
        findById(productId).deactivate();
    }

    public void addStock(String productId, int quantityToAdd) {
        findById(productId);
        inventory.addStock(productId, quantityToAdd);
    }

    public int availableQuantity(String productId) {
        findById(productId);
        return inventory.availableQuantity(productId);
    }

    public List<Product> findByCategory(String category) {
        requireNonBlankQuery(category, "category");
        return productsById.values().stream()
                .filter(product -> product.getCategory().equalsIgnoreCase(category))
                .toList();
    }

    public List<Product> findByTag(String tag) {
        requireNonBlankQuery(tag, "tag");
        return productsById.values().stream()
                .filter(product -> product.getTags().stream().anyMatch(value -> value.equalsIgnoreCase(tag)))
                .toList();
    }

    public List<Product> sortedByName() {
        return productsById.values().stream()
                .sorted(Comparator.comparing(Product::getName, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(Product::getId))
                .toList();
    }

    public List<Product> sortedByPrice() {
        return productsById.values().stream()
                .sorted(Comparator.comparing(Product::getPrice)
                        .thenComparing(Product::getId))
                .toList();
    }

    public List<Product> sortedByAvailableQuantity() {
        return productsById.values().stream()
                .sorted(Comparator.comparingInt((Product product) -> inventory.availableQuantity(product.getId()))
                        .thenComparing(Product::getId))
                .toList();
    }

    public List<Product> findLowStockProducts() {
        Comparator<Product> byQuantityThenNameThenId = Comparator
                .comparingInt((Product product) -> inventory.availableQuantity(product.getId()))
                .thenComparing(Product::getName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(Product::getId);
        return productsById.values().stream()
                .filter(product -> inventory.availableQuantity(product.getId()) <= product.getReorderLevel())
                .sorted(byQuantityThenNameThenId)
                .toList();
    }

    private static void requireNonBlankQuery(String value, String queryType) {
        if (value == null) {
            throw new InvalidProductDataException("Product " + queryType + " query must not be null");
        }
        if (value.isBlank()) {
            throw new InvalidProductDataException("Product " + queryType + " query must not be blank");
        }
    }
}
