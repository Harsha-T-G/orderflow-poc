# OrderFlow Core Domain Contract

**Status:** Governed by `SPEC.md`
**Covers:** `REQ-010`–`REQ-050`, `AC-010`–`AC-030`

Read `SPEC.md` first for assumptions, scope, architecture, source precedence,
and approval status.

## Requirements

### REQ-010: Manage products and inventory

The catalog shall add uniquely identified valid products; find by ID; update
name, category, price, tags, and reorder level through controlled operations;
activate/deactivate products; increase stock; query by category or
case-insensitive tag; sort by name, price, or available quantity; and report
low-stock products. Price must be positive; quantities and reorder levels cannot
be negative; inactive products cannot be ordered; stock cannot become negative.
Returned collections must be immutable.

### REQ-020: Manage customers

The customer component shall register customers with unique ID and
case-normalized email, validate required fields and reasonable email form,
update name/email through controlled operations, find by ID or type, and sort by
name. Customer type is required. Returned collections must be immutable.

### REQ-030: Create orders and protect state transitions

An order shall reference an existing customer and at least one valid requested
quantity. Duplicate product requests shall merge. Every product must exist and
be active. Items shall snapshot product name and price. The order shall compute
original amount and start at `CREATED`. Only these transitions are valid:

- `CREATED → QUEUED`
- `QUEUED → PROCESSING`
- `PROCESSING → COMPLETED`
- `PROCESSING → FAILED`
- `CREATED|QUEUED → CANCELLED`

Unsupported transitions must fail without changing state. IDs, item snapshots,
and completed financial values are immutable.

### REQ-040: Compose validation rules

Validation shall use `Predicate` or a focused functional interface and return
immutable named results. Rules cover customer existence, non-empty order,
positive quantities, product existence, active products, and currently
available stock. Rules are combined without a single monolithic validator.
Adding a rule must not require changing the processing service. Appropriate
`Predicate`, `Function`, `Consumer`, `Supplier`, method references, and
`Optional` shall be demonstrated without forcing Optional onto required fields.

### REQ-050: Calculate discounts

Independent rules shall provide 0% Regular, 5% Premium, 10% Corporate, 5% for
total quantity at least 10, and 5% for original amount at least 10,000. All
eligible rules apply, total discount is capped at 25%, and final amount cannot
be negative. The immutable result includes applied rule names, original amount,
discount amount, and final amount. Adding a discount rule must not change order
processing.

## Acceptance criteria

### AC-010: Product and customer invariants

**Given** valid and invalid product/customer inputs, **when** catalog or customer
operations run, **then** valid state is stored/queryable, duplicates and invalid
values raise contextual exceptions, and callers cannot mutate returned
collections or directly set stock.

### AC-020: Order snapshots and transitions

**Given** valid requests containing duplicate product entries, **when** an order
is created and product price later changes, **then** entries are combined, item
snapshots and original total remain unchanged, and only documented status
transitions succeed.

### AC-030: Discount behavior

**Given** each customer type, bulk threshold, high-value threshold, and combined
eligibility, **when** discounts are evaluated, **then** rule names and financial
amounts are exact to two decimal places, capped at 25%, non-negative, and each
rule is testable independently.

## Testing focus

- Constructor/factory invariants and controlled updates
- Product/customer uniqueness and immutable query results
- Order snapshots, duplicate-item normalization, and every status transition
- Independent validation/discount rules, combined discounts, and the 25% cap
- Contextual exceptions and unchanged state after rejection
