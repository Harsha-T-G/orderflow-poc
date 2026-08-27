package com.codewalnut.orderflow.core.service.payment;

import com.codewalnut.orderflow.core.domain.order.Order;
import com.codewalnut.orderflow.core.exception.PaymentFailedException;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Logger;

public final class ConfigurableFailurePaymentGateway implements PaymentGateway {
    private static final Logger LOGGER = Logger.getLogger(ConfigurableFailurePaymentGateway.class.getName());

    private final Set<String> failingOrderIds;

    public ConfigurableFailurePaymentGateway(Set<String> failingOrderIds) {
        this.failingOrderIds = Set.copyOf(Objects.requireNonNull(failingOrderIds, "failingOrderIds must not be null"));
    }

    @Override
    public void charge(Order order, BigDecimal amount) {
        if (failingOrderIds.contains(order.getId())) {
            LOGGER.warning(() -> "Configured payment failure for order " + order.getId());
            throw new PaymentFailedException(order.getId());
        }
        LOGGER.info(() -> "Charged order " + order.getId() + " amount " + amount);
    }
}
