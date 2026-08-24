package com.codewalnut.orderflow.core.service.customer;

import com.codewalnut.orderflow.core.domain.customer.Customer;
import com.codewalnut.orderflow.core.domain.customer.CustomerType;
import com.codewalnut.orderflow.core.exception.CustomerNotFoundException;
import com.codewalnut.orderflow.core.exception.DuplicateCustomerException;
import com.codewalnut.orderflow.core.exception.InvalidCustomerDataException;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class CustomerDirectory {
    // Seeded before workers start; do not register customers while orders are processing.
    private final Map<String, Customer> customersById = new HashMap<>();
    private final Map<String, String> customerIdsByNormalizedEmail = new HashMap<>();

    public void register(Customer customer) {
        Objects.requireNonNull(customer, "customer must not be null");
        if (customersById.containsKey(customer.getId())) {
            throw new DuplicateCustomerException("Customer " + customer.getId() + " already exists");
        }
        String normalizedEmail = customer.normalizedEmail();
        if (customerIdsByNormalizedEmail.containsKey(normalizedEmail)) {
            throw new DuplicateCustomerException(
                    "Customer email " + customer.getEmail() + " already exists");
        }
        customersById.put(customer.getId(), customer);
        customerIdsByNormalizedEmail.put(normalizedEmail, customer.getId());
    }

    public Customer findById(String customerId) {
        Customer customer = customersById.get(customerId);
        if (customer == null) {
            throw new CustomerNotFoundException(customerId);
        }
        return customer;
    }

    public void updateNameAndEmail(String customerId, String name, String email) {
        Customer existing = findById(customerId);
        Customer replacement = new Customer(existing.getId(), name, email, existing.getType());
        String normalizedEmail = replacement.normalizedEmail();
        String ownerId = customerIdsByNormalizedEmail.get(normalizedEmail);
        if (ownerId != null && !ownerId.equals(customerId)) {
            throw new DuplicateCustomerException(
                    "Customer email " + replacement.getEmail() + " already exists");
        }

        customerIdsByNormalizedEmail.remove(existing.normalizedEmail());
        customersById.put(customerId, replacement);
        customerIdsByNormalizedEmail.put(normalizedEmail, customerId);
    }

    public List<Customer> findByType(CustomerType type) {
        if (type == null) {
            throw new InvalidCustomerDataException("Customer type query must not be null");
        }
        return customersById.values().stream()
                .filter(customer -> customer.getType() == type)
                .sorted(Comparator.comparing(Customer::getId))
                .toList();
    }

    public List<Customer> sortedByName() {
        return customersById.values().stream()
                .sorted(Comparator.comparing(Customer::getName, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(Customer::getId))
                .toList();
    }
}
