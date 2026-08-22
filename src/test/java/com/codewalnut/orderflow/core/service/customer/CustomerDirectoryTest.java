package com.codewalnut.orderflow.core.service.customer;

import com.codewalnut.orderflow.core.exception.CustomerNotFoundException;
import com.codewalnut.orderflow.core.exception.DuplicateCustomerException;
import com.codewalnut.orderflow.core.exception.InvalidCustomerDataException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import com.codewalnut.orderflow.core.domain.customer.Customer;
import com.codewalnut.orderflow.core.domain.customer.CustomerType;

class CustomerDirectoryTest {

    @Test
    void givenValidCustomer_whenRegistered_thenCustomerCanBeFoundById() {
        // Arrange
        CustomerDirectory directory = new CustomerDirectory();
        Customer customer = new Customer(
                "C-100",
                "Alice Example",
                "Alice@Example.com",
                CustomerType.REGULAR);

        // Act
        directory.register(customer);

        // Assert
        assertSame(customer, directory.findById("C-100"));
        assertEquals("Alice@Example.com", directory.findById("C-100").getEmail());
    }

    @Test
    void givenBlankRequiredField_whenRegistered_thenThrowsInvalidCustomerDataException() {
        // Arrange
        CustomerDirectory directory = new CustomerDirectory();

        // Act
        InvalidCustomerDataException blankId = assertThrows(
                InvalidCustomerDataException.class,
                () -> directory.register(
                        new Customer("  ", "Alice Example", "alice@example.com", CustomerType.REGULAR)));
        InvalidCustomerDataException blankName = assertThrows(
                InvalidCustomerDataException.class,
                () -> directory.register(
                        new Customer("C-100", "\t", "alice@example.com", CustomerType.REGULAR)));
        InvalidCustomerDataException blankEmail = assertThrows(
                InvalidCustomerDataException.class,
                () -> directory.register(
                        new Customer("C-100", "Alice Example", " ", CustomerType.REGULAR)));
        InvalidCustomerDataException nullType = assertThrows(
                InvalidCustomerDataException.class,
                () -> directory.register(
                        new Customer("C-100", "Alice Example", "alice@example.com", null)));

        // Assert
        assertEquals("Customer id must not be blank", blankId.getMessage());
        assertEquals("Customer name must not be blank", blankName.getMessage());
        assertEquals("Customer email must not be blank", blankEmail.getMessage());
        assertEquals("Customer type must not be null", nullType.getMessage());
        assertThrows(CustomerNotFoundException.class, () -> directory.findById("C-100"));
    }

    @Test
    void givenInvalidEmail_whenRegistered_thenThrowsInvalidCustomerDataException() {
        // Arrange
        CustomerDirectory directory = new CustomerDirectory();

        // Act
        InvalidCustomerDataException missingAt = assertThrows(
                InvalidCustomerDataException.class,
                () -> directory.register(
                        new Customer("C-100", "Alice Example", "alice.example.com", CustomerType.REGULAR)));
        InvalidCustomerDataException missingDomain = assertThrows(
                InvalidCustomerDataException.class,
                () -> directory.register(
                        new Customer("C-100", "Alice Example", "alice@", CustomerType.REGULAR)));
        InvalidCustomerDataException missingLocal = assertThrows(
                InvalidCustomerDataException.class,
                () -> directory.register(
                        new Customer("C-100", "Alice Example", "@example.com", CustomerType.REGULAR)));
        InvalidCustomerDataException missingDot = assertThrows(
                InvalidCustomerDataException.class,
                () -> directory.register(
                        new Customer("C-100", "Alice Example", "alice@example", CustomerType.REGULAR)));

        // Assert
        assertEquals("Customer email must be a reasonable email address: alice.example.com",
                missingAt.getMessage());
        assertEquals("Customer email must be a reasonable email address: alice@",
                missingDomain.getMessage());
        assertEquals("Customer email must be a reasonable email address: @example.com",
                missingLocal.getMessage());
        assertEquals("Customer email must be a reasonable email address: alice@example",
                missingDot.getMessage());
        assertThrows(CustomerNotFoundException.class, () -> directory.findById("C-100"));
    }

    @Test
    void givenExistingCustomerId_whenRegisteredAgain_thenThrowsDuplicateCustomerException() {
        // Arrange
        CustomerDirectory directory = new CustomerDirectory();
        Customer original = new Customer(
                "C-100",
                "Alice Example",
                "alice@example.com",
                CustomerType.REGULAR);
        Customer duplicate = new Customer(
                "C-100",
                "Other Person",
                "other@example.com",
                CustomerType.PREMIUM);
        directory.register(original);

        // Act
        DuplicateCustomerException exception = assertThrows(
                DuplicateCustomerException.class,
                () -> directory.register(duplicate));

        // Assert
        assertEquals("Customer C-100 already exists", exception.getMessage());
        assertSame(original, directory.findById("C-100"));
        assertEquals("alice@example.com", directory.findById("C-100").getEmail());
    }

    @Test
    void givenEmailWithDifferentCase_whenRegisteredAgain_thenThrowsDuplicateCustomerException() {
        // Arrange
        CustomerDirectory directory = new CustomerDirectory();
        Customer original = new Customer(
                "C-100",
                "Alice Example",
                "Alice@Example.com",
                CustomerType.REGULAR);
        Customer duplicateEmail = new Customer(
                "C-200",
                "Bob Example",
                "alice@example.com",
                CustomerType.PREMIUM);
        directory.register(original);

        // Act
        DuplicateCustomerException exception = assertThrows(
                DuplicateCustomerException.class,
                () -> directory.register(duplicateEmail));

        // Assert
        assertEquals("Customer email alice@example.com already exists", exception.getMessage());
        assertSame(original, directory.findById("C-100"));
        assertThrows(CustomerNotFoundException.class, () -> directory.findById("C-200"));
    }

    @Test
    void givenCustomer_whenNameOrEmailIsUpdated_thenIndexesRemainConsistent() {
        // Arrange
        CustomerDirectory directory = new CustomerDirectory();
        Customer original = new Customer(
                "C-100",
                "Alice Example",
                "Alice@Example.com",
                CustomerType.REGULAR);
        Customer other = new Customer(
                "C-200",
                "Bob Example",
                "bob@example.com",
                CustomerType.PREMIUM);
        directory.register(original);
        directory.register(other);

        // Act
        directory.updateNameAndEmail("C-100", "Alice Updated", "New.Alice@Example.com");

        // Assert
        Customer updated = directory.findById("C-100");
        assertEquals("C-100", updated.getId());
        assertEquals("Alice Updated", updated.getName());
        assertEquals("New.Alice@Example.com", updated.getEmail());
        assertEquals(CustomerType.REGULAR, updated.getType());
        assertThrows(
                DuplicateCustomerException.class,
                () -> directory.register(
                        new Customer("C-300", "Carol", "new.alice@example.com", CustomerType.CORPORATE)));
        directory.register(new Customer("C-300", "Carol", "Alice@Example.com", CustomerType.CORPORATE));
        assertEquals("Alice@Example.com", directory.findById("C-300").getEmail());
        assertEquals("bob@example.com", directory.findById("C-200").getEmail());

        DuplicateCustomerException duplicateEmail = assertThrows(
                DuplicateCustomerException.class,
                () -> directory.updateNameAndEmail("C-100", "Alice Again", "bob@example.com"));
        assertEquals("Customer email bob@example.com already exists", duplicateEmail.getMessage());
        assertEquals("New.Alice@Example.com", directory.findById("C-100").getEmail());

        InvalidCustomerDataException blankName = assertThrows(
                InvalidCustomerDataException.class,
                () -> directory.updateNameAndEmail("C-100", " ", "still.valid@example.com"));
        assertEquals("Customer name must not be blank", blankName.getMessage());
        assertEquals("Alice Updated", directory.findById("C-100").getName());
        assertEquals("New.Alice@Example.com", directory.findById("C-100").getEmail());
    }

    @Test
    void givenCustomers_whenFilteredByType_thenImmutableMatchesAreReturned() {
        // Arrange
        CustomerDirectory directory = new CustomerDirectory();
        Customer alice = new Customer("C-100", "Alice", "alice@example.com", CustomerType.PREMIUM);
        Customer bob = new Customer("C-200", "Bob", "bob@example.com", CustomerType.REGULAR);
        Customer carol = new Customer("C-300", "Carol", "carol@example.com", CustomerType.PREMIUM);
        Customer dave = new Customer("C-400", "Dave", "dave@example.com", CustomerType.CORPORATE);
        directory.register(alice);
        directory.register(bob);
        directory.register(carol);
        directory.register(dave);

        // Act
        List<Customer> matches = directory.findByType(CustomerType.PREMIUM);

        // Assert
        assertEquals(List.of(alice, carol), matches);
        assertThrows(UnsupportedOperationException.class, () -> matches.add(dave));
    }

    @Test
    void givenCustomers_whenSortedByName_thenAlphabeticalImmutableListIsReturned() {
        // Arrange
        CustomerDirectory directory = new CustomerDirectory();
        Customer sameC = new Customer("id-c", "Same", "same-c@example.com", CustomerType.REGULAR);
        Customer apple = new Customer("id-apple", "Apple", "apple@example.com", CustomerType.PREMIUM);
        Customer sameA = new Customer("id-a", "same", "same-a@example.com", CustomerType.CORPORATE);
        Customer sameB = new Customer("id-b", "SAME", "same-b@example.com", CustomerType.REGULAR);
        Customer sameD = new Customer("id-d", "Same", "same-d@example.com", CustomerType.PREMIUM);
        directory.register(sameC);
        directory.register(apple);
        directory.register(sameA);
        directory.register(sameB);
        directory.register(sameD);

        // Act
        List<Customer> byName = directory.sortedByName();

        // Assert
        assertEquals(List.of(apple, sameA, sameB, sameC, sameD), byName);
        assertThrows(UnsupportedOperationException.class, () -> byName.add(apple));
    }

    @Test
    void givenUnknownCustomerId_whenFound_thenThrowsCustomerNotFoundException() {
        // Arrange
        CustomerDirectory directory = new CustomerDirectory();

        // Act
        CustomerNotFoundException exception = assertThrows(
                CustomerNotFoundException.class,
                () -> directory.findById("missing-42"));

        // Assert
        assertEquals("Customer missing-42 was not found", exception.getMessage());
    }

    @Test
    void givenUnknownCustomerId_whenNameOrEmailIsUpdated_thenDirectoryStateRemainsUnchanged() {
        // Arrange
        CustomerDirectory directory = new CustomerDirectory();
        Customer existing = new Customer(
                "C-100",
                "Alice Example",
                "Alice@Example.com",
                CustomerType.REGULAR);
        directory.register(existing);

        // Act
        CustomerNotFoundException exception = assertThrows(
                CustomerNotFoundException.class,
                () -> directory.updateNameAndEmail(
                        "missing-42",
                        "Should Not Persist",
                        "should.not@example.com"));

        // Assert
        assertEquals("Customer missing-42 was not found", exception.getMessage());
        assertSame(existing, directory.findById("C-100"));
        assertEquals("Alice Example", directory.findById("C-100").getName());
        assertEquals("Alice@Example.com", directory.findById("C-100").getEmail());
        assertThrows(CustomerNotFoundException.class, () -> directory.findById("missing-42"));
        assertThrows(
                DuplicateCustomerException.class,
                () -> directory.register(
                        new Customer(
                                "C-200",
                                "Other",
                                "alice@example.com",
                                CustomerType.PREMIUM)));
    }

    @Test
    void givenNullType_whenCustomersAreFiltered_thenThrowsContextualInvalidCustomerDataException() {
        // Arrange
        CustomerDirectory directory = new CustomerDirectory();
        Customer existing = new Customer(
                "C-100",
                "Alice Example",
                "alice@example.com",
                CustomerType.REGULAR);
        directory.register(existing);

        // Act
        InvalidCustomerDataException exception = assertThrows(
                InvalidCustomerDataException.class,
                () -> directory.findByType(null));

        // Assert
        assertEquals("Customer type query must not be null", exception.getMessage());
        assertSame(existing, directory.findById("C-100"));
    }
}
