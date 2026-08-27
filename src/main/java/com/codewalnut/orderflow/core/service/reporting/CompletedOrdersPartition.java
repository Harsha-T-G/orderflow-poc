package com.codewalnut.orderflow.core.service.reporting;

import com.codewalnut.orderflow.core.domain.order.Order;

import java.util.List;

public record CompletedOrdersPartition(
        List<Order> completedOrders,
        List<Order> nonCompletedOrders) {

    public CompletedOrdersPartition {
        completedOrders = List.copyOf(completedOrders);
        nonCompletedOrders = List.copyOf(nonCompletedOrders);
    }
}
