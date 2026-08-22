package com.codewalnut.orderflow.core.service.order.validation;

public final class ValidationResult {
    private final String ruleName;
    private final boolean passed;
    private final String failureMessage;

    private ValidationResult(String ruleName, boolean passed, String failureMessage) {
        if (ruleName == null || ruleName.isBlank()) {
            throw new IllegalArgumentException("Validation rule name must not be blank");
        }
        this.ruleName = ruleName;
        this.passed = passed;
        this.failureMessage = failureMessage;
    }

    public static ValidationResult pass(String ruleName) {
        return new ValidationResult(ruleName, true, "");
    }

    public static ValidationResult fail(String ruleName, String failureMessage) {
        if (failureMessage == null || failureMessage.isBlank()) {
            throw new IllegalArgumentException("Validation failure message must not be blank");
        }
        return new ValidationResult(ruleName, false, failureMessage);
    }

    public String ruleName() {
        return ruleName;
    }

    public boolean passed() {
        return passed;
    }

    public String failureMessage() {
        return failureMessage;
    }
}
