package com.codewalnut.orderflow;

public record DemonstrationResult(
        int productCount,
        int categoryCount,
        int customerCount,
        int attemptedOrderCount,
        int submittedOrderCount,
        int completedOrderCount,
        int failedOrderCount,
        int invalidCreationCount,
        boolean processorShutdown) {
}
