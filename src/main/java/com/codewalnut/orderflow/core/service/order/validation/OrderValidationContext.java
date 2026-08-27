package com.codewalnut.orderflow.core.service.order.validation;

import com.codewalnut.orderflow.core.service.catalog.ProductCatalog;
import com.codewalnut.orderflow.core.service.customer.CustomerDirectory;
import com.codewalnut.orderflow.core.service.inventory.Inventory;
import com.codewalnut.orderflow.core.domain.order.OrderRequest;

import java.util.Objects;

public final class OrderValidationContext {
    private final OrderRequest request;
    private final CustomerDirectory customers;
    private final ProductCatalog catalog;
    private final Inventory inventory;

    public OrderValidationContext(
            OrderRequest request,
            CustomerDirectory customers,
            ProductCatalog catalog,
            Inventory inventory) {
        this.request = Objects.requireNonNull(request, "request must not be null");
        this.customers = Objects.requireNonNull(customers, "customers must not be null");
        this.catalog = Objects.requireNonNull(catalog, "catalog must not be null");
        this.inventory = Objects.requireNonNull(inventory, "inventory must not be null");
    }

    public OrderRequest getRequest() {
        return request;
    }

    public CustomerDirectory getCustomers() {
        return customers;
    }

    public ProductCatalog getCatalog() {
        return catalog;
    }

    public Inventory getInventory() {
        return inventory;
    }
}
