package com.codewalnut.orderflow.core.domain.order;

import com.codewalnut.orderflow.core.exception.InvalidOrderException;

import java.util.List;

public final class OrderRequest {
    private final String customerId;
    private final List<RequestedProduct> requestedProducts;

    public OrderRequest(String customerId, List<RequestedProduct> requestedProducts) {
        if (customerId == null) {
            throw new InvalidOrderException("Order request customer id must not be null");
        }
        this.customerId = customerId;
        this.requestedProducts = requestedProducts == null ? List.of() : List.copyOf(requestedProducts);
    }

    public String getCustomerId() {
        return customerId;
    }

    public List<RequestedProduct> getRequestedProducts() {
        return requestedProducts;
    }
}
