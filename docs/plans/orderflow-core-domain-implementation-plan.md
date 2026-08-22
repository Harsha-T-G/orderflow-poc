# OrderFlow Core Domain Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> `superpowers:subagent-driven-development` (recommended) or
> `superpowers:executing-plans` to implement this plan task-by-task. Every
> behavior follows RED → GREEN → REFACTOR.

**Status:** Implemented and reviewed — 2026-08-21; native JDK 21 verification pending

**Goal:** Implement `REQ-010`–`REQ-050` and `AC-010`–`AC-030`: products,
inventory administration, customers, order creation and transitions, validation,
and discounts.

**Architecture:** Models live in `core.domain`; operations live in
`core.service`; errors live in `core.exception`. Domain objects protect their
own invariants; concrete in-memory services own uniqueness and mutable indexes.

**Tech stack:** Java 21, Maven Wrapper, JUnit 5, JDK collections and functional
interfaces only.

## Global constraints

- Read `SPEC.md`, `CONTEXT.md`, and
  `docs/specs/orderflow/01-core-domain.md` before each task.
- Production code uses descriptive names instead of comments that repeat code.
- Tests use Given-When-Then names and `// Arrange`, `// Act`, `// Assert`.
- Money uses `BigDecimal`, scale 2, and `RoundingMode.HALF_UP`.
- Return immutable collection snapshots.
- Do not introduce Spring, Lombok, persistence, networking, or new dependencies.
- Do not implement fulfilment workers, payment, notifications, reporting, or
  audit in this plan.
- Do not commit unless the user explicitly requests a commit.

## Planned file structure

```text
src/main/java/com/codewalnut/orderflow/
├── catalog/
│   ├── Product.java
│   ├── ProductCatalog.java
│   └── ProductStatus.java
├── customer/
│   ├── Customer.java
│   ├── CustomerDirectory.java
│   └── CustomerType.java
├── exception/
│   ├── OrderFlowException.java
│   ├── InvalidMonetaryValueException.java
│   ├── InvalidProductDataException.java
│   ├── DuplicateProductException.java
│   ├── ProductNotFoundException.java
│   ├── InvalidCustomerDataException.java
│   ├── DuplicateCustomerException.java
│   ├── CustomerNotFoundException.java
│   ├── InvalidOrderException.java
│   └── InvalidOrderStatusTransitionException.java
├── inventory/
│   └── Inventory.java
├── order/
│   ├── Order.java
│   ├── OrderFactory.java
│   ├── OrderItem.java
│   ├── OrderRequest.java
│   ├── OrderStatus.java
│   ├── RequestedProduct.java
│   └── validation/
│       ├── OrderValidationContext.java
│       ├── OrderValidationPipeline.java
│       ├── OrderValidationRule.java
│       └── ValidationResult.java
└── pricing/
    ├── DiscountContext.java
    ├── DiscountEngine.java
    ├── DiscountResult.java
    └── DiscountRule.java

src/test/java/com/codewalnut/orderflow/
├── catalog/ProductTest.java
├── catalog/ProductCatalogTest.java
├── customer/CustomerDirectoryTest.java
├── inventory/InventoryTest.java
├── order/OrderFactoryTest.java
├── order/OrderTest.java
├── order/validation/OrderValidationPipelineTest.java
└── pricing/DiscountEngineTest.java
```

Do not create this tree upfront. Each task creates only the files needed for its
first failing test.

## TDD execution rule

For every test listed below:

1. Add only that test.
2. Run its focused Maven command and confirm RED for the expected reason.
3. Add the smallest production change that makes it GREEN.
4. Run the focused test again.
5. Refactor names/duplication only while GREEN.
6. Record the RED and GREEN commands/outcomes in `AI_USAGE.md`.

Do not batch all tests before implementation.

---

## Task 1 — TASK-001: Product invariants and immutable metadata

**Traceability:** `REQ-010`, `AC-010`

**Files:**

- Create `catalog/ProductStatus.java`
- Create `catalog/Product.java`
- Create `exception/OrderFlowException.java`
- Create `exception/InvalidProductDataException.java`
- Create `exception/InvalidMonetaryValueException.java`
- Create `catalog/ProductTest.java`

**Behavior sequence:**

1. `givenValidData_whenProductIsCreated_thenExposesNormalizedImmutableState`
2. `givenBlankId_whenProductIsCreated_thenThrowsInvalidProductDataException`
3. `givenBlankName_whenProductIsCreated_thenThrowsInvalidProductDataException`
4. `givenBlankCategory_whenProductIsCreated_thenThrowsInvalidProductDataException`
5. `givenNonPositivePrice_whenProductIsCreated_thenThrowsInvalidMonetaryValueException`
6. `givenNegativeReorderLevel_whenProductIsCreated_thenThrowsInvalidProductDataException`
7. `givenMutableTags_whenProductIsCreated_thenStoresImmutableTagCopy`
8. `givenProduct_whenDetailsAreUpdated_thenIdRemainsUnchanged`
9. `givenProduct_whenActivatedOrDeactivated_thenStatusChangesThroughDomainOperations`

**Focused command:**

```bash
./mvnw -Dtest=ProductTest test
```

**Acceptance:** Product identity is immutable; mutable fields change only
through validated operations; price is normalized; tags cannot be mutated by
callers.

---

## Task 2 — TASK-002: Inventory administration

**Traceability:** `REQ-010`, `AC-010`

**Files:**

- Create `inventory/Inventory.java`
- Create `inventory/InventoryTest.java`

**Behavior sequence:**

1. `givenProductAndInitialQuantity_whenInventoryIsRegistered_thenQuantityIsAvailable`
2. `givenNegativeInitialQuantity_whenInventoryIsRegistered_thenThrowsInvalidProductDataException`
3. `givenPositiveIncrease_whenStockIsAdded_thenAvailableQuantityIncreases`
4. `givenNonPositiveIncrease_whenStockIsAdded_thenThrowsInvalidProductDataException`
5. `givenRegisteredProducts_whenInventorySnapshotIsReturned_thenCallerCannotMutateIt`

Use `ConcurrentHashMap<String, Integer>` from the beginning so the fulfilment
plan can add atomic reservation with `compute` without replacing storage.

**Focused command:**

```bash
./mvnw -Dtest=InventoryTest test
```

**Acceptance:** Available quantity never becomes negative through
administrative operations, callers cannot set stock directly, and snapshots are
immutable.

---

## Task 3 — TASK-003: Product catalog uniqueness, updates, and queries

**Traceability:** `REQ-010`, `AC-010`

**Files:**

- Create `catalog/ProductCatalog.java`
- Create `exception/DuplicateProductException.java`
- Create `exception/ProductNotFoundException.java`
- Create `catalog/ProductCatalogTest.java`

`ProductCatalog` owns product lookup and coordinates initial quantity and stock
increases through `Inventory`; it never exposes the mutable inventory map.

**Behavior sequence:**

1. `givenNewProduct_whenAdded_thenProductAndInitialInventoryAreStored`
2. `givenExistingProductId_whenAddedAgain_thenThrowsDuplicateProductException`
3. `givenUnknownProductId_whenFound_thenThrowsProductNotFoundException`
4. `givenProductId_whenDetailsAreUpdated_thenControlledFieldsChange`
5. `givenProducts_whenFilteredByCategory_thenMatchingImmutableListIsReturned`
6. `givenMixedCaseTag_whenProductsAreSearched_thenMatchingProductsAreReturned`
7. `givenProducts_whenSortedByNamePriceOrQuantity_thenRequestedOrderIsReturned`
8. `givenProductsAtOrBelowReorderLevel_whenLowStockIsQueried_thenSortedMatchesAreReturned`

**Focused command:**

```bash
./mvnw -Dtest=ProductCatalogTest test
```

**Acceptance:** ID uniqueness, controlled updates, case-insensitive tags,
sorting, low-stock selection, and immutable query results match `REQ-010`.

---

## Task 4 — TASK-004: Customer invariants and directory indexes

**Traceability:** `REQ-020`, `AC-010`

**Files:**

- Create `customer/CustomerType.java`
- Create `customer/Customer.java`
- Create `customer/CustomerDirectory.java`
- Create `exception/InvalidCustomerDataException.java`
- Create `exception/DuplicateCustomerException.java`
- Create `exception/CustomerNotFoundException.java`
- Create `customer/CustomerDirectoryTest.java`

Keep `Customer` immutable. `CustomerDirectory` replaces a customer during
controlled updates so the normalized email index cannot drift.

**Behavior sequence:**

1. `givenValidCustomer_whenRegistered_thenCustomerCanBeFoundById`
2. `givenBlankRequiredField_whenRegistered_thenThrowsInvalidCustomerDataException`
3. `givenInvalidEmail_whenRegistered_thenThrowsInvalidCustomerDataException`
4. `givenExistingCustomerId_whenRegisteredAgain_thenThrowsDuplicateCustomerException`
5. `givenEmailWithDifferentCase_whenRegisteredAgain_thenThrowsDuplicateCustomerException`
6. `givenCustomer_whenNameOrEmailIsUpdated_thenIndexesRemainConsistent`
7. `givenCustomers_whenFilteredByType_thenImmutableMatchesAreReturned`
8. `givenCustomers_whenSortedByName_thenAlphabeticalImmutableListIsReturned`

Normalize uniqueness with `email.toLowerCase(Locale.ROOT)`.

**Focused command:**

```bash
./mvnw -Dtest=CustomerDirectoryTest test
```

**Acceptance:** ID and case-insensitive email uniqueness remain correct before
and after controlled updates.

---

## Task 5 — TASK-005: Reusable order-validation pipeline

**Traceability:** `REQ-030`, `REQ-040`, `AC-020`

**Files:**

- Create `order/RequestedProduct.java`
- Create `order/OrderRequest.java`
- Create `order/validation/OrderValidationContext.java`
- Create `order/validation/OrderValidationRule.java`
- Create `order/validation/ValidationResult.java`
- Create `order/validation/OrderValidationPipeline.java`
- Create `exception/InvalidOrderException.java`
- Create `order/validation/OrderValidationPipelineTest.java`

`OrderRequest` is boundary input and may contain invalid values for validation.
Rules are named functions from `OrderValidationContext` to `ValidationResult`;
the pipeline evaluates an ordered immutable list without knowing concrete rule
types.

**Behavior sequence:**

1. `givenNoRequestedProducts_whenValidated_thenReturnsNamedFailure`
2. `givenNonPositiveQuantity_whenValidated_thenReturnsNamedFailure`
3. `givenUnknownCustomer_whenValidated_thenReturnsNamedFailure`
4. `givenUnknownProduct_whenValidated_thenReturnsNamedFailure`
5. `givenInactiveProduct_whenValidated_thenReturnsNamedFailure`
6. `givenInsufficientCurrentStock_whenValidated_thenReturnsNamedFailure`
7. `givenMultipleRules_whenPipelineRuns_thenResultsRemainOrderedAndImmutable`

**Focused command:**

```bash
./mvnw -Dtest=OrderValidationPipelineTest test
```

**Acceptance:** Validation is side-effect free, produces named immutable
results, and accepts new rules without changing pipeline code.

---

## Task 6 — TASK-006: Order creation, snapshots, and status transitions

**Traceability:** `REQ-030`, `REQ-040`, `AC-020`

**Files:**

- Create `order/OrderStatus.java`
- Create `order/OrderItem.java`
- Create `order/Order.java`
- Create `order/OrderFactory.java`
- Create `exception/InvalidOrderStatusTransitionException.java`
- Create `order/OrderFactoryTest.java`
- Create `order/OrderTest.java`

The aggregate owns status, original amount, discount amount, final amount, and
failure reason. `OrderFactory` runs the approved validation pipeline, combines
duplicate requests, and snapshots product name and price before constructing
the aggregate. Financial completion/failure methods remain minimal until the
pricing and fulfilment plans need them.

**Behavior sequence:**

1. `givenDuplicateProductRequests_whenOrderIsCreated_thenQuantitiesAreCombined`
2. `givenProductPriceChangesAfterCreation_whenOrderItemIsRead_thenSnapshotIsUnchanged`
3. `givenValidationFailure_whenOrderIsCreated_thenNoOrderIsProduced`
4. `givenValidItems_whenOrderIsCreated_thenStatusIsCreatedAndOriginalAmountIsCalculated`
5. `givenCreatedOrder_whenQueued_thenStatusBecomesQueued`
6. `givenQueuedOrder_whenProcessingStarts_thenStatusBecomesProcessing`
7. `givenProcessingOrder_whenCompleted_thenFinancialValuesBecomeImmutable`
8. `givenProcessingOrder_whenFailed_thenFailureReasonIsRecorded`
9. `givenCreatedOrQueuedOrder_whenCancelled_thenStatusBecomesCancelled`
10. `givenUnsupportedTransition_whenAttempted_thenThrowsAndLeavesStatusUnchanged`
11. `givenMutableItemList_whenOrderIsCreated_thenOrderStoresImmutableCopy`

**Focused command:**

```bash
./mvnw -Dtest=OrderTest,OrderFactoryTest test
```

**Acceptance:** Only approved transitions succeed, rejected transitions leave
state unchanged, and financial/item state cannot be mutated externally.

---

## Task 7 — TASK-007: Extensible discount engine

**Traceability:** `REQ-050`, `AC-030`

**Files:**

- Create `pricing/DiscountContext.java`
- Create `pricing/DiscountRule.java`
- Create `pricing/DiscountResult.java`
- Create `pricing/DiscountEngine.java`
- Create `pricing/DiscountEngineTest.java`

Represent each named rule as a `DiscountRule`. The engine accepts an immutable
list of rules and never switches on rule type. Customer-type, bulk, and
high-value rules may be named lambdas/factory methods; do not create one class
per percentage unless behavior needs independent state.

**Behavior sequence:**

1. `givenRegularCustomerWithoutOtherEligibility_whenDiscounted_thenNoCustomerDiscountApplies`
2. `givenPremiumCustomer_whenDiscounted_thenFivePercentApplies`
3. `givenCorporateCustomer_whenDiscounted_thenTenPercentApplies`
4. `givenAtLeastTenItems_whenDiscounted_thenBulkDiscountApplies`
5. `givenOriginalAmountAtLeastTenThousand_whenDiscounted_thenHighValueDiscountApplies`
6. `givenMultipleEligibleRules_whenDiscounted_thenDiscountsStack`
7. `givenRulesAboveTwentyFivePercent_whenDiscounted_thenDiscountIsCapped`
8. `givenAnyEligibleRules_whenDiscounted_thenFinalAmountIsNeverNegative`
9. `givenDiscountResult_whenRuleNamesAreRead_thenResultIsImmutable`

**Focused command:**

```bash
./mvnw -Dtest=DiscountEngineTest test
```

**Acceptance:** Every required discount is independently testable, combinations
are correct to two decimal places, the cap is enforced, and adding a rule does
not modify the engine.

---

## Task 8 — TASK-008: Core-domain contract verification

**Traceability:** `REQ-010`–`REQ-050`, `AC-010`–`AC-030`

**Files:**

- Update `AI_USAGE.md` with RED/GREEN evidence and accepted/rejected suggestions
- Update `README.md` only if implemented behavior changes its component overview

**Steps:**

1. Run all core-domain tests:

   ```bash
   ./mvnw -Dtest='ProductTest,InventoryTest,ProductCatalogTest,CustomerDirectoryTest,OrderValidationPipelineTest,OrderFactoryTest,OrderTest,DiscountEngineTest' test
   ```

2. Run the complete gate:

   ```bash
   ./mvnw clean verify
   ```

3. Inspect status and the complete diff. Confirm no implementation from
   `02-fulfilment.md` or `03-reporting-quality.md` was introduced.
4. Map each `REQ-010`–`REQ-050` and `AC-010`–`AC-030` item to at least one test
   in the plan evidence.
5. Run `verify-feature-readiness` and report remaining risk.

**Acceptance:** The core-domain contract is green, traceable, documented, and
contains no unrelated fulfilment/reporting work.

## Dependency order

```text
TASK-001 Product
    ↓
TASK-002 Inventory
    ↓
TASK-003 Product Catalog

TASK-004 Customer ─────────┐
                          ↓
TASK-005 Validation + Order Creation
                          ↓
TASK-006 Order Aggregate

TASK-001 + TASK-004 + TASK-006
                          ↓
TASK-007 Discount Engine
                          ↓
TASK-008 Verification
```

`TASK-001` and `TASK-004` may be implemented independently. All other tasks
should follow the dependency order shown.

## Plan approval gate

Do not implement this plan until the human approves:

- the file/package map;
- task boundaries and dependency order;
- the Product/Inventory ownership split;
- immutable `Customer` replacement during updates;
- named-lambda discount rules instead of one class per rule.
