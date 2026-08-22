package com.codewalnut.orderflow.core.domain.order;

import java.util.List;

public final class OrderRequest {
    private final String customerId;
    private final List<RequestedProduct> requestedProducts;

    public OrderRequest(String customerId, List<RequestedProduct> requestedProducts) {
        this.customerId = customerId;
        this.requestedProducts = requestedProducts == null ? null : List.copyOf(requestedProducts);
    }

    public String getCustomerId() {
        return customerId;
    }

    public List<RequestedProduct> getRequestedProducts() {
        return requestedProducts;
    }
}
