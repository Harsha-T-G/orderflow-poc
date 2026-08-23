package com.codewalnut.orderflow.core.domain.customer;

import com.codewalnut.orderflow.core.exception.InvalidCustomerDataException;

public final class Customer {
    private final String id;
    private final String name;
    private final String email;
    private final CustomerType type;

    public Customer(String id, String name, String email, CustomerType type) {
        validateId(id);
        validateName(name);
        String canonicalEmail = canonicalEmail(email);
        validateEmail(canonicalEmail);
        validateType(type);
        this.id = id;
        this.name = name;
        this.email = canonicalEmail;
        this.type = type;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getEmail() {
        return email;
    }

    public CustomerType getType() {
        return type;
    }

    private static void validateId(String id) {
        if (id == null || id.isBlank()) {
            throw new InvalidCustomerDataException("Customer id must not be blank");
        }
    }

    private static void validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new InvalidCustomerDataException("Customer name must not be blank");
        }
    }

    private static void validateEmail(String email) {
        if (email == null || email.isBlank()) {
            throw new InvalidCustomerDataException("Customer email must not be blank");
        }
        if (containsWhitespace(email) || !hasReasonableEmailShape(email)) {
            throw new InvalidCustomerDataException(
                    "Customer email must be a reasonable email address: " + email);
        }
    }

    private static String canonicalEmail(String email) {
        return email == null ? null : email.trim();
    }

    private static boolean containsWhitespace(String email) {
        for (int i = 0; i < email.length(); i++) {
            if (Character.isWhitespace(email.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasReasonableEmailShape(String email) {
        int atIndex = email.indexOf('@');
        if (atIndex <= 0 || atIndex != email.lastIndexOf('@')) {
            return false;
        }
        String localPart = email.substring(0, atIndex);
        String domainPart = email.substring(atIndex + 1);
        if (localPart.isBlank() || domainPart.isBlank()) {
            return false;
        }
        int lastDot = domainPart.lastIndexOf('.');
        return lastDot > 0 && lastDot < domainPart.length() - 1;
    }

    private static void validateType(CustomerType type) {
        if (type == null) {
            throw new InvalidCustomerDataException("Customer type must not be null");
        }
    }
}
