package com.codewalnut.orderflow.core.service.order.validation;

import java.util.List;
import java.util.Objects;

public final class OrderValidationPipeline {
    private final List<OrderValidationRule> rules;

    public OrderValidationPipeline(List<OrderValidationRule> rules) {
        Objects.requireNonNull(rules, "rules must not be null");
        this.rules = List.copyOf(rules);
    }

    public List<ValidationResult> evaluate(OrderValidationContext context) {
        Objects.requireNonNull(context, "context must not be null");
        return rules.stream()
                .map(rule -> rule.validate(context))
                .toList();
    }
}
