package com.codewalnut.orderflow.core.service.notification;

import com.codewalnut.orderflow.core.domain.order.Order;

import java.util.logging.Logger;

public final class EmailNotificationChannel implements NotificationChannel {
    private static final Logger LOGGER = Logger.getLogger(EmailNotificationChannel.class.getName());

    @Override
    public void notify(Order order) {
        LOGGER.info(() -> "Email notification for order " + order.getId() + " status " + order.getStatus());
    }
}
