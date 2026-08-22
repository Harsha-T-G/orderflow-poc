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
        assertTrue(result.processorShutdown());
        assertTrue(output.toString().contains("Completed revenue"));
        assertTrue(output.toString().contains("Audit events:"));
        assertTrue(output.toString().contains("CREATED"));
        assertTrue(output.toString().contains("QUEUED"));
        assertTrue(result.processorShutdown());
    }
}
