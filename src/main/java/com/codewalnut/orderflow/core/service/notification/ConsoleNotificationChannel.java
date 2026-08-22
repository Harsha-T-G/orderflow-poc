package com.codewalnut.orderflow.core.service.notification;

import com.codewalnut.orderflow.core.domain.order.Order;

import java.util.logging.Logger;

public final class ConsoleNotificationChannel implements NotificationChannel {
    private static final Logger LOGGER = Logger.getLogger(ConsoleNotificationChannel.class.getName());

    @Override
    public void notify(Order order) {
        LOGGER.info(() -> "Console notification for order " + order.getId() + " status " + order.getStatus());
    }
}
