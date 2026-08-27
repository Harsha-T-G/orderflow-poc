package com.codewalnut.orderflow.core.service.inventory;

import com.codewalnut.orderflow.core.domain.inventory.Reservation;
import com.codewalnut.orderflow.core.exception.InsufficientStockException;
import com.codewalnut.orderflow.core.exception.InvalidProductDataException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public final class Inventory {

    private final ConcurrentHashMap<String, Integer> quantitiesByProductId = new ConcurrentHashMap<>();

    public void register(String productId, int initialQuantity) {
        validateProductId(productId, "registration with initial quantity " + initialQuantity);
        if (initialQuantity < 0) {
            throw new InvalidProductDataException(
                    "Initial quantity for product " + productId + " cannot be negative: " + initialQuantity);
        }
        Integer existingQuantity = quantitiesByProductId.putIfAbsent(productId, initialQuantity);
        if (existingQuantity != null) {
            throw new InvalidProductDataException(
                    "Product " + productId + " is already registered; rejected initial quantity: " + initialQuantity);
        }
    }

    public int availableQuantity(String productId) {
        validateProductId(productId, "available quantity lookup");
        Integer availableQuantity = quantitiesByProductId.get(productId);
        if (availableQuantity == null) {
            throw new InvalidProductDataException(
                    "Cannot find available quantity for unregistered product " + productId);
        }
        return availableQuantity;
    }

    public void addStock(String productId, int quantityToAdd) {
        validateProductId(productId, "stock increase of " + quantityToAdd);
        if (quantityToAdd <= 0) {
            throw new InvalidProductDataException(
                    "Stock increase for product " + productId + " must be positive: " + quantityToAdd);
        }
        quantitiesByProductId.compute(productId, (ignoredProductId, availableQuantity) ->
                increasedQuantityForRegisteredProduct(productId, availableQuantity, quantityToAdd));
    }

    public Map<String, Integer> snapshot() {
        return Map.copyOf(quantitiesByProductId);
    }

    public Reservation reserve(String orderId, Map<String, Integer> requestedQuantities) {
        Objects.requireNonNull(requestedQuantities, "requestedQuantities must not be null");
        if (requestedQuantities.isEmpty()) {
            throw new InvalidProductDataException(
                    "Reservation for order " + orderId + " must request at least one product");
        }
        List<String> productIdsInReservationOrder = requestedQuantities.keySet().stream()
                .sorted()
                .toList();
        Map<String, Integer> reservationJournal = new LinkedHashMap<>();
        try {
            for (String productId : productIdsInReservationOrder) {
                int quantityToReserve = requestedQuantities.get(productId);
                decrementForReservation(orderId, productId, quantityToReserve);
                reservationJournal.put(productId, quantityToReserve);
            }
            return new Reservation(orderId, reservationJournal);
        } catch (RuntimeException exception) {
            releaseJournal(reservationJournal);
            throw exception;
        }
    }

    public void release(Reservation reservation) {
        Objects.requireNonNull(reservation, "reservation must not be null");
        releaseJournal(reservation.reservedQuantities());
    }

    private void decrementForReservation(String orderId, String productId, int quantityToReserve) {
        validateProductId(productId, "reservation for order " + orderId);
        if (quantityToReserve <= 0) {
            throw new InvalidProductDataException(
                    "Reservation quantity for product " + productId + " must be positive: " + quantityToReserve);
        }
        quantitiesByProductId.compute(productId, (ignoredProductId, availableQuantity) -> {
            if (availableQuantity == null) {
                throw new InvalidProductDataException(
                        "Cannot reserve unregistered product " + productId + " for order " + orderId);
            }
            if (availableQuantity < quantityToReserve) {
                throw new InsufficientStockException(productId, quantityToReserve, availableQuantity);
            }
            return availableQuantity - quantityToReserve;
        });
    }

    private void releaseJournal(Map<String, Integer> reservedQuantities) {
        reservedQuantities.forEach((productId, reservedQuantity) ->
                quantitiesByProductId.compute(productId, (ignoredProductId, availableQuantity) ->
                        increasedQuantityForRegisteredProduct(productId, availableQuantity, reservedQuantity)));
    }

    private void validateProductId(String productId, String operationContext) {
        if (productId == null) {
            throw new InvalidProductDataException("Product ID cannot be null for " + operationContext);
        }
        if (productId.isBlank()) {
            throw new InvalidProductDataException(
                    "Product ID cannot be blank for " + operationContext + ": '" + productId + "'");
        }
    }

    private int increasedQuantityForRegisteredProduct(
            String productId,
            Integer availableQuantity,
            int quantityToAdd) {
        if (availableQuantity == null) {
            throw new InvalidProductDataException(
                    "Cannot add " + quantityToAdd + " stock to unregistered product " + productId);
        }
        try {
            return Math.addExact(availableQuantity, quantityToAdd);
        } catch (ArithmeticException exception) {
            throw new InvalidProductDataException(
                    "Stock increase for product " + productId + " exceeds the supported quantity; current quantity: "
                            + availableQuantity + ", increase: " + quantityToAdd);
        }
    }
}
