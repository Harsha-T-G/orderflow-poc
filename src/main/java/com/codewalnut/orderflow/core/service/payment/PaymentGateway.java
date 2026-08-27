package com.codewalnut.orderflow.core.service.payment;

import com.codewalnut.orderflow.core.domain.order.Order;

import java.math.BigDecimal;

@FunctionalInterface
public interface PaymentGateway {
    void charge(Order order, BigDecimal amount);
}
