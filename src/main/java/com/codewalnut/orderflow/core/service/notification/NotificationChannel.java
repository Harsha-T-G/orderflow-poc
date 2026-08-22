package com.codewalnut.orderflow.core.service.notification;

import com.codewalnut.orderflow.core.domain.order.Order;

@FunctionalInterface
public interface NotificationChannel {
    void notify(Order order);
}
