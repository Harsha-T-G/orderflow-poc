package com.codewalnut.orderflow;

import com.codewalnut.orderflow.core.domain.catalog.Product;
import com.codewalnut.orderflow.core.domain.customer.Customer;
import com.codewalnut.orderflow.core.domain.customer.CustomerType;
import com.codewalnut.orderflow.core.domain.order.Order;
import com.codewalnut.orderflow.core.domain.order.OrderRequest;
import com.codewalnut.orderflow.core.domain.order.OrderStatus;
import com.codewalnut.orderflow.core.domain.order.RequestedProduct;
import com.codewalnut.orderflow.core.domain.pricing.DiscountRule;
import com.codewalnut.orderflow.core.exception.InvalidCustomerDataException;
import com.codewalnut.orderflow.core.exception.InvalidOrderException;
import com.codewalnut.orderflow.core.service.audit.AuditLog;
import com.codewalnut.orderflow.core.service.catalog.ProductCatalog;
import com.codewalnut.orderflow.core.service.customer.CustomerDirectory;
import com.codewalnut.orderflow.core.service.inventory.Inventory;
import com.codewalnut.orderflow.core.service.notification.ConsoleNotificationChannel;
import com.codewalnut.orderflow.core.service.notification.EmailNotificationChannel;
import com.codewalnut.orderflow.core.service.order.OrderFactory;
import com.codewalnut.orderflow.core.service.order.validation.OrderValidationPipeline;
import com.codewalnut.orderflow.core.service.order.validation.OrderValidationRule;
import com.codewalnut.orderflow.core.service.payment.AlwaysSuccessfulPaymentGateway;
import com.codewalnut.orderflow.core.service.pricing.DiscountEngine;
import com.codewalnut.orderflow.core.service.processing.OrderProcessor;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Reader;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

public final class OrderFlowShopSession {

    private static final Duration PROCESSING_TIMEOUT = Duration.ofSeconds(15);

    private final BufferedReader input;
    private final PrintWriter output;
    private final CustomerDirectory customers;
    private final AtomicInteger customerSequence = new AtomicInteger(1);
    private final AtomicInteger orderSequence = new AtomicInteger(1);

    public OrderFlowShopSession(Reader input, PrintWriter output) {
        this(input, output, new CustomerDirectory());
    }

    OrderFlowShopSession(Reader input, PrintWriter output, CustomerDirectory customers) {
        this.input = new BufferedReader(Objects.requireNonNull(input, "input must not be null"));
        this.output = Objects.requireNonNull(output, "output must not be null");
        this.customers = Objects.requireNonNull(customers, "customers must not be null");
    }

    public void run() {
        Inventory inventory = new Inventory();
        ProductCatalog catalog = new ProductCatalog(inventory);
        OrderValidationPipeline pipeline = new OrderValidationPipeline(List.of(
                OrderValidationRule.nonEmptyRequest(),
                OrderValidationRule.positiveQuantities(),
                OrderValidationRule.customerExists(),
                OrderValidationRule.productExists(),
                OrderValidationRule.activeProducts(),
                OrderValidationRule.availableStock()));
        AuditLog auditLog = new AuditLog();
        OrderFactory factory = new OrderFactory(customers, catalog, inventory, pipeline, auditLog);
        DiscountEngine discounts = new DiscountEngine(List.of(
                DiscountRule.regularCustomer(),
                DiscountRule.premiumCustomer(),
                DiscountRule.corporateCustomer(),
                DiscountRule.bulkQuantity(),
                DiscountRule.highValue()));
        OrderFlowCatalogSeed.seed(catalog);
        OrderProcessor processor = new OrderProcessor(
                catalog,
                customers,
                inventory,
                pipeline,
                discounts,
                new AlwaysSuccessfulPaymentGateway(),
                List.of(new ConsoleNotificationChannel(), new EmailNotificationChannel()),
                auditLog);
        try {
            output.println("OrderFlow shop");
            output.println("Products are ready. Enter your details to start shopping.");
            output.println();
            Customer shopper = resolveCustomer();
            boolean placeAnotherOrder = true;
            while (placeAnotherOrder) {
                checkout(processor, factory, catalog, shopper);
                placeAnotherOrder = askYesNo("Place another order? (y/n): ");
                output.println();
            }
            output.println("Thank you for shopping with OrderFlow.");
        } finally {
            processor.shutdown();
        }
        output.flush();
    }

    private Customer resolveCustomer() {
        String email = readRequiredLine("Email: ");
        Optional<Customer> existingCustomer = customers.findByEmail(email);
        if (existingCustomer.isPresent()) {
            Customer customer = existingCustomer.get();
            output.println("Welcome back, " + customer.getName() + " (" + customer.getId() + ").");
            return customer;
        }
        String name = readRequiredLine("Name: ");
        CustomerType type = readCustomerType();
        String customerId = nextCustomerId();
        Customer customer = new Customer(customerId, name, email, type);
        customers.register(customer);
        output.println("Registered customer " + customerId + " (" + type + ").");
        output.println();
        return customer;
    }

    private void checkout(OrderProcessor processor, OrderFactory factory, ProductCatalog catalog, Customer shopper) {
        List<Product> products = catalog.sortedByName();
        Map<String, Integer> cartQuantities = new LinkedHashMap<>();
        output.println("Your cart for " + shopper.getName() + ":");
        boolean checkoutRequested = false;
        while (!checkoutRequested) {
            printProductMenu(catalog, products);
            output.println("Enter <product-number> <quantity>, or 0 to checkout.");
            String line = readRequiredLine("> ");
            if ("0".equals(line.trim())) {
                checkoutRequested = true;
                continue;
            }
            String[] parts = line.trim().split("\\s+");
            if (parts.length != 2) {
                output.println("Use format: <product-number> <quantity>, or 0 to checkout.");
                continue;
            }
            try {
                int productNumber = Integer.parseInt(parts[0]);
                int quantity = Integer.parseInt(parts[1]);
                if (productNumber < 1 || productNumber > products.size()) {
                    output.println("Choose a product number between 1 and " + products.size() + ".");
                    continue;
                }
                if (quantity <= 0) {
                    output.println("Quantity must be positive.");
                    continue;
                }
                Product product = products.get(productNumber - 1);
                int available = catalog.availableQuantity(product.getId());
                if (quantity > available) {
                    output.println("Only " + available + " units of " + product.getName() + " are available.");
                    continue;
                }
                cartQuantities.merge(product.getId(), quantity, Math::addExact);
                output.println("Added " + quantity + " x " + product.getName() + " to cart.");
            } catch (NumberFormatException exception) {
                output.println("Use format: <product-number> <quantity>, or 0 to checkout.");
            } catch (ArithmeticException exception) {
                output.println("That quantity is too large to combine in the cart.");
            }
        }
        if (cartQuantities.isEmpty()) {
            output.println("Cart is empty. Add items before checkout.");
            return;
        }
        List<RequestedProduct> requestedProducts = cartQuantities.entrySet().stream()
                .map(entry -> new RequestedProduct(entry.getKey(), entry.getValue()))
                .toList();
        String orderId = nextOrderId();
        try {
            Order order = factory.create(orderId, new OrderRequest(shopper.getId(), requestedProducts));
            processor.submit(order);
            waitForProcessing(processor);
            printOrderOutcome(orderId, processor);
        } catch (InvalidOrderException exception) {
            output.println("Could not create order: " + exception.getMessage());
        } catch (RuntimeException exception) {
            output.println("Could not submit order: " + exception.getMessage());
        }
    }

    private void waitForProcessing(OrderProcessor processor) {
        try {
            processor.awaitIdle(PROCESSING_TIMEOUT);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for order processing", exception);
        }
    }

    private void printOrderOutcome(String orderId, OrderProcessor processor) {
        Optional<Order> processedOrder = processor.snapshotOrders().stream()
                .filter(order -> orderId.equals(order.getId()))
                .findFirst();
        if (processedOrder.isEmpty()) {
            output.println("Order " + orderId + " was not found after processing.");
            return;
        }
        Order order = processedOrder.get();
        if (order.getStatus() == OrderStatus.COMPLETED) {
            output.println("Order placed successfully.");
            output.println("Reference " + order.getId() + " — COMPLETED — paid "
                    + order.getFinalAmount().orElseThrow());
            return;
        }
        output.println("Order could not be completed.");
        output.println("Reference " + order.getId() + " — " + order.getStatus()
                + order.getFailureReason().map(reason -> " — " + reason).orElse(""));
    }

    private void printProductMenu(ProductCatalog catalog, List<Product> products) {
        output.println();
        output.println("Products:");
        for (int productIndex = 0; productIndex < products.size(); productIndex++) {
            Product product = products.get(productIndex);
            int available = catalog.availableQuantity(product.getId());
            output.println("  " + (productIndex + 1) + ". "
                    + product.getName()
                    + " (" + product.getId() + ")"
                    + "  " + product.getCategory()
                    + "  " + product.getPrice()
                    + "  in stock: " + available);
        }
    }

    private CustomerType readCustomerType() {
        output.println("Customer type: 1=Regular, 2=Premium, 3=Corporate");
        while (true) {
            String line = readRequiredLine("> ");
            switch (line.trim()) {
                case "1", "REGULAR" -> {
                    return CustomerType.REGULAR;
                }
                case "2", "PREMIUM" -> {
                    return CustomerType.PREMIUM;
                }
                case "3", "CORPORATE" -> {
                    return CustomerType.CORPORATE;
                }
                default -> output.println("Enter 1, 2, or 3.");
            }
        }
    }

    private boolean askYesNo(String prompt) {
        while (true) {
            String answer = readRequiredLine(prompt).trim();
            if (answer.equalsIgnoreCase("y") || answer.equalsIgnoreCase("yes")) {
                return true;
            }
            if (answer.equalsIgnoreCase("n") || answer.equalsIgnoreCase("no")) {
                return false;
            }
            output.println("Please enter y or n.");
        }
    }

    private String readRequiredLine(String prompt) {
        while (true) {
            output.print(prompt);
            output.flush();
            try {
                String line = input.readLine();
                if (line == null) {
                    throw new IllegalStateException("Input ended unexpectedly");
                }
                if (line.isBlank()) {
                    output.println("Input must not be blank.");
                    continue;
                }
                return line.trim();
            } catch (IOException exception) {
                throw new IllegalStateException("Failed to read input", exception);
            }
        }
    }

    private String nextCustomerId() {
        return "C-SHOP-" + String.format("%03d", customerSequence.getAndIncrement());
    }

    private String nextOrderId() {
        return "O-SHOP-" + String.format("%03d", orderSequence.getAndIncrement());
    }
}
