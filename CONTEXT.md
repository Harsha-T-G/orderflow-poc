# OrderFlow Domain Context

This glossary gives each term one stable meaning. Product behavior belongs in
`SPEC.md`; implementation details belong in code and tests.

## Source ranking

1. Current user instructions.
2. Human-approved `SPEC.md` and its linked `docs/specs/orderflow/` contract
   chunks. `SPEC.md` owns their shared approval status.
3. `docs/requirements/orderflow-poc-brief.txt` (original project brief).
4. This glossary.
5. Tests and implementation as evidence of current behavior.
6. AI notes and prior-session claims, which are untrusted until verified.

While the contract is draft, the original brief remains the product authority.

## Terms

- **Product** — catalog identity and mutable merchandising metadata. Product
  identity is immutable. Available quantity is controlled only by Inventory.
- **Inventory level** — the current available quantity and reorder threshold for
  one product. It is shared mutable state and must change atomically.
- **Customer** — a registered buyer with a unique ID and email and one customer
  type: Regular, Premium, or Corporate.
- **Order request** — caller input containing a customer ID and requested
  product quantities. Duplicate product entries are normalized before order
  creation.
- **Order** — the aggregate that owns immutable identity, item snapshots,
  financial outcome, failure reason, and valid status transitions.
- **Order item** — an immutable snapshot of product ID, product name, unit price,
  quantity, and line total at order creation.
- **Order submission** — the one-time acceptance of an order ID into the
  processing system. Submission is distinct from order creation.
- **Reservation** — a thread-safe decrement of inventory tied to one order.
  Reservations must be either compensated or retained by a completed order.
- **Compensation** — exact release of quantities reserved for an order after a
  later processing failure, especially payment failure.
- **Validation rule** — an independently testable function returning a named
  pass/failure result; it does not mutate order or inventory state.
- **Discount rule** — an independently testable function contributing an
  eligible discount. The engine caps the combined discount at 25%.
- **Payment gateway** — replaceable boundary that runs only after reservation.
- **Notification channel** — replaceable asynchronous boundary whose failure is
  audited/logged but cannot reverse a completed or failed order.
- **Audit event** — immutable evidence of an order-processing step, including
  timestamp and thread name. Audit is not application logging.
- **Final order state** — `COMPLETED`, `FAILED`, or `CANCELLED`. An accepted
  order reaches at most one final state.

## Important non-equivalences

- Validation success is not inventory reservation success; stock can change
  between those steps.
- Order creation is not queue submission.
- Payment failure is not notification failure.
- Audit history is not a mutable internal event list exposed to callers.
- A concurrent collection does not by itself make a multi-step workflow atomic.
