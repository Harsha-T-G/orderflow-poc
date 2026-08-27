package com.codewalnut.orderflow.core.service.payment;

import com.codewalnut.orderflow.core.domain.order.Order;

import java.math.BigDecimal;
import java.util.logging.Logger;

public final class AlwaysSuccessfulPaymentGateway implements PaymentGateway {
    private static final Logger LOGGER = Logger.getLogger(AlwaysSuccessfulPaymentGateway.class.getName());

    @Override
    public void charge(Order order, BigDecimal amount) {
        LOGGER.info(() -> "Charged order " + order.getId() + " amount " + amount);
    }
}
