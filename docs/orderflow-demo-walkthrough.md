# OrderFlow demo walkthrough

Guide for explaining what happens when you run `OrderFlowApplication` — from
`main` through create, submit, reserve, pay, notify, shutdown, and the printed
summary. Use this for reviews, debugging, or talking through the PoC out loud.

**Run:**

```bash
./mvnw clean verify
java -cp target/classes com.codewalnut.orderflow.OrderFlowApplication
java -cp target/classes com.codewalnut.orderflow.OrderFlowApplication --shop
```

**Entry point:** `OrderFlowApplication.main` → demo by default, or
`OrderFlowShopSession` with `--shop`.

There is no web UI. The shop is in-memory on the JVM.

---

## ID naming

| Prefix | Meaning | Example |
| --- | --- | --- |
| `P-` | Product | `P-01` = first product, price `1.99` |
| `C-` | Customer | `C-03` = third customer |
| `O-` | Order | `O-13` = thirteenth order attempt |

Product price for `P-nn` is seeded as `nn.99` (so `P-13` is `13.99`).

---

## What gets seeded (before any order)

Constants in `OrderFlowDemonstration`:

| Item | Count | Notes |
| --- | --- | --- |
| Products | 15 | Four categories: Tools, Garden, Kitchen, Sports |
| Customers | 10 | Types cycle Regular → Premium → Corporate |
| Order attempts | 50 | `O-01` … `O-50` |
| **P-01 stock** | **5** | `CONTENDED_PRODUCT_QUANTITY` — scarce on purpose |
| Other products | 40 each | `DEFAULT_PRODUCT_QUANTITY` |
| Payment fail ids | `O-48`, `O-49` | `ConfigurableFailurePaymentGateway` always declines these |

**Why P-01 = 5?** The brief requires “multiple orders competing for limited
stock.” With 13 carts asking for P-01 and only 5 units, some complete and some
fail at reservation — without overselling.

**Why O-48 / O-49?** So every run has a payment-failure story: reserve → pay
declines → stock released → `FAILED`.

Catalog and customer directories are plain `HashMap`s. Seed them **before**
workers start; do not register products or customers while orders are processing.

---

## How each order cart is built (`requestFor`)

`createOrders` loops `orderIndex = 1 … 50` and calls
`factory.create(orderId(orderIndex), requestFor(orderIndex))`.

| Sequence | Order id | Customer | Product | Qty | Purpose |
| --- | --- | --- | --- | --- | --- |
| 1 | O-01 | C-01 | *(empty)* | — | Invalid create — empty cart |
| 2 | O-02 | `missing-customer` | P-02 | 1 | Invalid create — bad customer |
| 3–12 | O-03 … O-12 | C-01 | P-01 | 1 | P-01 stock fight (10 orders) |
| 13–50 | O-13 … O-50 | `C-((n-1) % 10 + 1)` | `P-((n-1) % 15 + 1)` | 1 | Rotating “normal” carts |

**Example O-13** (first order using the rotating formula):

```
customer = C-03     because (13 - 1) % 10 + 1 = 3
product  = P-13     because (13 - 1) % 15 + 1 = 13
qty      = 1
```

C-03 is **Corporate** (10% off). P-13 at 13.99 → pay **12.59** when completed.

Three more rotating orders also hit P-01: **O-16**, **O-31**, **O-46**. Total
**13** orders compete for P-01 (10 from O-03…O-12 plus those three).

---

## End-to-end run flow (debug order)

### Phase 0 — `main`

```
OrderFlowApplication.main()
  └─ OrderFlowDemonstration.run()
```

Thread: **`main`**.

### Phase 1 — Wire the shop (`run`)

1. `new Inventory()`, `ProductCatalog`, `CustomerDirectory`
2. Validation pipeline (6 rules) + `OrderFactory` + `DiscountEngine`
3. `seedCatalog()` — P-01=5, others=40
4. `seedCustomers()` — C-01 … C-10
5. `new OrderProcessor(...)` — starts **3 workers** (`order-worker-1/2/3`),
   payment pool (`order-payment-*`), notification pool (`order-notification-*`),
   ingress queue capacity **256**

Workers poll the queue immediately (still empty).

### Phase 2 — Create 50 carts (`createOrders`, `main`)

For each order: `OrderFactory.create(id, request)`.

**On success:**

- Six validation rules run (customer, products, stock at create — advisory)
- Duplicate product lines merged; name/price **snapshotted**
- Status **`CREATED`**, audit **`CREATED`**
- **Inventory unchanged**

**On failure** (`InvalidOrderException`): O-01, O-02 — not added to
`acceptedOrders`. Never queued.

Typical result: **48 accepted**, **2 invalid at create**.

### Phase 3 — Submit 48 orders (`submitAcceptedOrders`)

- Fixed pool of **8** submitter threads (`pool-1-thread-*`)
- Each calls `processor.submit(order)`

**Submit (`OrderProcessor.submit`):**

1. Reject if shutdown, duplicate id, or ingress queue full (256)
2. Register id, track work, audit **`QUEUED`**
3. `order.queue()` → **`CREATED` → `QUEUED`**
4. `queuedOrders.offer(order)`

Full queue rejects **before** mutating the order — stays **`CREATED`**, retryable.

Main waits: `submitted.await()` then `processor.awaitIdle(15s)`.

### Phase 4 — Worker processing (`order-worker-*`)

Worker dequeues order → `processQueuedOrder`:

| Step | Action | Order status | Stock |
| --- | --- | --- | --- |
| 1 | `startProcessing()` | `QUEUED` → `PROCESSING` | unchanged |
| 2 | Re-validate (6 rules) | — | unchanged |
| 3 | `DiscountEngine` | — | unchanged |
| 4 | `inventory.reserve(...)` | — | **decremented** |
| 5 | `paymentCoordinator.charge(...)` | async | reserved |
| 6a | Pay OK → `order.complete(...)` | → **`COMPLETED`** | kept |
| 6b | Pay fail / reserve fail → `fail(...)` | → **`FAILED`** | released if reserved |

**Create ≠ submit ≠ reserve.** Stock moves only at **reserve** (step 4).

### Phase 5 — Notify (`order-notification-*`)

After final `COMPLETED` or `FAILED`, `NotificationDispatcher` runs console +
email. Notification failure is audited but **cannot** change the final state.

### Phase 6 — Shutdown + print (`main`)

`processor.shutdown()` — one **10s** global budget. Then `printResults`:
walkthrough cases, totals, inventory snapshot, reports, sample audit for O-13
and O-48.

---

## Worked example: O-13 (happy path)

| Step | Thread | Event |
| --- | --- | --- |
| Create | `main` | CREATED, original 13.99, P-13 stock still 40 |
| Submit | `pool-1-thread-*` | QUEUED |
| Process | `order-worker-1` | PROCESSING, VALIDATION passed |
| Price | `order-worker-1` | Corporate 10% → discount 1.40, final 12.59 |
| Reserve | `order-worker-1` | P-13: 40 → 39, audit RESERVATION |
| Pay | `order-payment-*` | Payment succeeded |
| Complete | worker / payment callback | COMPLETED, audit COMPLETED |
| Notify | `order-notification-*` | Console + email NOTIFICATION |

No `RELEASE` line — stock stays at 39.

---

## Worked example: O-48 (payment failure + compensation)

Same path through **RESERVATION**, then:

| Step | Audit |
| --- | --- |
| Pay | PAYMENT failed for order O-48 |
| Compensate | RELEASE — reservation released after payment failure |
| Final | FAILED |
| Notify | NOTIFICATION still succeeds (both channels) |

Stock taken during reserve is **returned**. Order stays **FAILED**.

---

## P-01 stock fight

| Metric | Value |
| --- | --- |
| Orders wanting P-01 | 13 (O-03…O-12, O-16, O-31, O-46) |
| Starting stock | 5 |
| Completed (won reserve + pay) | 5 |
| Failed (insufficient stock at reserve) | 8 |
| Remaining P-01 | 0 |

Validation at create can pass when stock is 5; **reservation** is authoritative.
Payment never runs for the 8 losers.

---

## Reconciling the totals

```
50 order attempts
 − 2 invalid at create (O-01, O-02)
 = 48 accepted

48 accepted
 − 8 failed (P-01 stock losers)
 − 2 failed (O-48, O-49 payment)
 = 38 completed
```

**Common mistakes:**

- O-48 and O-49 **are** accepted — they fail at **payment**, not at create.
- O-01 and O-02 are **not** in the 48 — they never submit.
- Not all O-03…O-12 fail — **5** orders win the P-01 race across all 13 fighters.

---

## Where reservation is called

Production call site (only one):

```text
OrderProcessor.processQueuedOrder
  → reserveAndCharge
    → inventory.reserve(orderId, requestedQuantities)
```

Release on payment failure:

```text
OrderProcessor.releaseSafely
  → inventory.release(attempt.reservation())
```

Implementation: `Inventory.reserve` uses `ConcurrentHashMap.compute` per
product, sorted product ids, journal + exact partial compensation on failure.

---

## Legal status transitions

```text
CREATED → QUEUED
QUEUED → PROCESSING
PROCESSING → COMPLETED | FAILED
CREATED | QUEUED → CANCELLED   (shutdown)
```

An accepted order reaches at most one final state: `COMPLETED`, `FAILED`, or
`CANCELLED`.

---

## Suggested debugger breakpoints

| # | Class | Method | Condition (optional) |
| --- | --- | --- | --- |
| 1 | `OrderFlowApplication` | `main` | — |
| 2 | `OrderFlowDemonstration` | `run` | — |
| 3 | `OrderFlowDemonstration` | `requestFor` | `sequence == 13` |
| 4 | `OrderFactory` | `create` | `orderId.equals("O-13")` |
| 5 | `OrderProcessor` | `submit` | — |
| 6 | `OrderProcessor` | `processQueuedOrder` | — |
| 7 | `OrderProcessor` | `reserveAndCharge` | — |
| 8 | `Inventory` | `reserve` | — |
| 9 | `ConfigurableFailurePaymentGateway` | `charge` | — |
| 10 | `OrderProcessor` | `settleReservedSuccess` / `settleReservedFailure` | — |

**Watch expressions:** `order.getStatus()`, `order.getCustomerId()`,
`inventory.availableQuantity("P-13")`, `order.getFinalAmount()`.

---

## Reading the printed output

| Section | Meaning |
| --- | --- |
| **Setup** | Describes seeded constants (not runtime input) |
| **Rejected at create** | O-01, O-02 — never queued |
| **Stock fight on P-01** | Winners vs losers at **reserve** |
| **Payment failure** | O-48, O-49 — reserve then pay fail then release |
| **Completed example** | Usually O-13 — first clean rotating success |
| **Totals** | 50 attempted, 48 accepted, 38 completed, 10 failed |
| **Inventory snapshot** | After full run; `remaining = start − sold on COMPLETED` |
| **Reports** | Completed revenue uses **final** amounts; failed excluded |
| **Audit events** | Full log has hundreds of events; print shows O-13 + O-48 only |

---

## Related docs

- `docs/diagrams/orderflow-processing-sequence.md` — sequence diagram
- `docs/diagrams/domain-vs-service.excalidraw` — domain vs services
- `docs/diagrams/arjun-order-example.excalidraw` — one premium order story
- `CONTEXT.md` — vocabulary (create vs submit, reservation vs validation)
- `SPEC.md` — approved product contract
