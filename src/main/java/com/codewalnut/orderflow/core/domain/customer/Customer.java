package com.codewalnut.orderflow.core.domain.customer;

import com.codewalnut.orderflow.core.exception.InvalidCustomerDataException;

import java.util.Locale;

public final class Customer {
    private static final int NEXT_LINE_CODE_POINT = 0x0085;

    private final String id;
    private final String name;
    private final String email;
    private final String normalizedEmail;
    private final CustomerType type;

    public Customer(String id, String name, String email, CustomerType type) {
        validateId(id);
        validateName(name);
        String displayEmail = normalizeDisplayEmail(email);
        validateEmail(displayEmail);
        validateType(type);
        this.id = id;
        this.name = name;
        this.email = displayEmail;
        this.normalizedEmail = displayEmail.toLowerCase(Locale.ROOT);
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

    public String normalizedEmail() {
        return normalizedEmail;
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
        if (containsUnicodeEmailSpace(email) || !hasReasonableEmailShape(email)) {
            throw new InvalidCustomerDataException(
                    "Customer email must be a reasonable email address: " + email);
        }
    }

    private static String normalizeDisplayEmail(String email) {
        if (email == null) {
            return null;
        }
        int firstCharacterIndex = 0;
        int lastCharacterIndex = email.length();
        while (firstCharacterIndex < lastCharacterIndex) {
            int codePoint = email.codePointAt(firstCharacterIndex);
            if (!isUnicodeEmailSpace(codePoint)) {
                break;
            }
            firstCharacterIndex += Character.charCount(codePoint);
        }
        while (firstCharacterIndex < lastCharacterIndex) {
            int codePoint = email.codePointBefore(lastCharacterIndex);
            if (!isUnicodeEmailSpace(codePoint)) {
                break;
            }
            lastCharacterIndex -= Character.charCount(codePoint);
        }
        return email.substring(firstCharacterIndex, lastCharacterIndex);
    }

    private static boolean containsUnicodeEmailSpace(String email) {
        for (int characterIndex = 0; characterIndex < email.length(); ) {
            int codePoint = email.codePointAt(characterIndex);
            if (isUnicodeEmailSpace(codePoint)) {
                return true;
            }
            characterIndex += Character.charCount(codePoint);
        }
        return false;
    }

    private static boolean isUnicodeEmailSpace(int codePoint) {
        return codePoint == NEXT_LINE_CODE_POINT
                || Character.isWhitespace(codePoint)
                || Character.isSpaceChar(codePoint);
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
