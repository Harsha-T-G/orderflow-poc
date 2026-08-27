package com.codewalnut.orderflow.core.service.customer;

import com.codewalnut.orderflow.core.domain.customer.Customer;
import com.codewalnut.orderflow.core.domain.customer.CustomerType;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CustomerDirectoryFindByEmailTest {

    @Test
    void givenRegisteredEmail_whenFindByEmail_thenReturnsCustomerIgnoringCase() {
        // Arrange
        CustomerDirectory directory = new CustomerDirectory();
        directory.register(new Customer("C-1", "Alice", "Alice@Example.com", CustomerType.PREMIUM));

        // Act
        Optional<Customer> found = directory.findByEmail("alice@example.com");

        // Assert
        assertTrue(found.isPresent());
        assertEquals("C-1", found.get().getId());
        assertEquals(CustomerType.PREMIUM, found.get().getType());
    }

    @Test
    void givenUnknownEmail_whenFindByEmail_thenReturnsEmpty() {
        // Arrange
        CustomerDirectory directory = new CustomerDirectory();

        // Act
        Optional<Customer> found = directory.findByEmail("nobody@example.com");

        // Assert
        assertTrue(found.isEmpty());
    }
}
