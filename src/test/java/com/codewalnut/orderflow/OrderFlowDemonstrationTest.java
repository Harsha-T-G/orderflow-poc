package com.codewalnut.orderflow;

import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderFlowDemonstrationTest {

    @Test
    void givenDemonstrationData_whenWorkflowRuns_thenVolumeRequirementsAreMetAndExecutorsShutDown() {
        // Arrange
        StringWriter output = new StringWriter();
        OrderFlowDemonstration demonstration = new OrderFlowDemonstration(new PrintWriter(output, true));

        // Act
        DemonstrationResult result = demonstration.run();

        // Assert
        assertTrue(result.productCount() >= 15);
        assertTrue(result.categoryCount() >= 4);
        assertTrue(result.customerCount() >= 10);
        assertTrue(result.attemptedOrderCount() >= 50);
        assertTrue(result.submittedOrderCount() >= 40);
        assertTrue(result.completedOrderCount() >= 1);
        assertTrue(result.failedOrderCount() >= 1);
        assertTrue(result.invalidCreationCount() >= 1);
        assertTrue(result.isProcessorShutdown());
        assertTrue(output.toString().contains("Completed revenue"));
        assertTrue(output.toString().contains("Audit events:"));
        assertTrue(output.toString().contains("CREATED"));
        assertTrue(output.toString().contains("QUEUED"));
    }

    @Test
    void givenDemonstrationData_whenWorkflowRuns_thenWalkthroughExplainsAFewNamedScenarios() {
        // Arrange
        StringWriter output = new StringWriter();
        OrderFlowDemonstration demonstration = new OrderFlowDemonstration(new PrintWriter(output, true));

        // Act
        DemonstrationResult result = demonstration.run();
        String printed = output.toString();
        long printedLineCount = printed.lines().count();

        // Assert
        assertTrue(result.attemptedOrderCount() >= 50);
        assertTrue(printed.contains("OrderFlow walkthrough"));
        assertTrue(printed.contains("O-01"));
        assertTrue(printed.contains("empty"));
        assertTrue(printed.contains("O-02"));
        assertTrue(printed.contains("P-01"));
        assertTrue(printed.contains("O-48"));
        assertTrue(printed.contains("payment"));
        assertTrue(printedLineCount <= 160, () -> "Walkthrough should stay short, was " + printedLineCount);
    }
}
