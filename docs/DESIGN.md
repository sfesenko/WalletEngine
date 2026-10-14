# Wallet Engine — Design

## Requirement analysis

Needs to implement simple transaction processing engine, and maintain invariants:

- wallet balance >= 0, need to check balance for each operation
- wallets processing must be independent, to not block each other - high-throughput requirement
- processing must be robust - data integrity is top priority
- no requirements for `amount` / `currency` - free to choose better (simplest) option
- duplicate `transaction_id` must return the original result, not re-execute the movement
- immutable transaction log `wallet_ledger` may be enforced via DB constraints.
- response idempotency: the task requires that for any `transaction_id` that was already processed the original result is returned, so failed `Debit` transaction cannot be reapplied later. 
  - Burning an idempotency key by its first attempt is common for payment processing
  - re-applying transient rejections is common for game wallets
  - the app implements both approaches behind a configuration flag, strict is the default.

While the requirements don't mention how wallets are created, consider possible options:

- create wallet on demand (1st usage)
    - PROS: system will work as all wallets already present with 0 balance
    - CONS: system behaviour may be less explicit
- introduce additional api for wallet creation
    - PROS: explicit system behaviour
    - CONS: more complex api
- just rely on pre-defined wallets that are already present in DB
    - PROS: the simplest solution
    - CONS: not flexible approach

A separate API is what a real system would use, so that's the chosen approach.

## Assumptions

- assume there is single currency, no currency per wallet / transaction
- integer amounts - int64 must be enough for any reasonable amount
  - also because 2^63 is too big, no overflow check is implemented (makes sense only for crypto)
- no ID generation - all requests will contain `wallet_id` / `transaction_id`
- idempotency level is a runtime option (`wallet.idempotency.strict`), default strict:
  - false -> only successful movements recorded; rejected requests may be repeated later
  - true -> failed transactions are recorded; duplicates always replay the original result
- transactions are all-or-nothing; no partial amounts
- wallets are never deleted
- no authentication - api endpoints are open

## Stack

- Java 21, Maven
- DB: PostgreSQL 17, Flyway migrations
- Redis 8: distributed lock
- RabbitMQ 4
- Spring Boot 4.1.1
    - gRPC
    - Spring Data JPA (personal preference, probably Spring Data JDBC would be better fit)
- docker-compose as development env

## Design

Each operation needs the wallet's current balance. While it is still possible to just sum(amount) over all of the
wallet's transactions, that approach won't scale. So, to guarantee O(1) for balance calculation, the wallet balance
must be maintained.
Also to guarantee O(1) processing time, needs to store wallet's amount after each transaction, so in case of repeated request, the same wallet's amount may be provided. 

There are 2 options where the current wallet balance can be kept:

- Redis
    - PROS:
        - cheap access
    - CONS:
        - possible scalability issue: need to sum(amount) over all of the wallet's transactions for the 1st balance calculation, O(n)
- DB (Postgres)
    - PROS:
        - scalable approach (all operations are O(1))
    - CONS:
        - probably slower than Redis
- Hybrid: DB + Redis
    - may provide the better parts of both approaches, but at the cost of higher complexity

So the usage of DB (Postgres) looks like the most pragmatic solution.
It also fits nicely into a DDD approach:

- use a transaction to maintain the aggregate invariant (transaction + wallet's balance)
- use domain events to connect workflows (RabbitMQ event publishing)

While it may be tempting to use the transaction (`wallet_ledger`) table as the domain event itself, it will introduce
unnecessary abstraction leaks and may affect implementation quality. So it is better to use a separate entity for
domain event storage.

So each transaction processing needs:

- adjust and check the wallet's balance
- persist the transaction
- persist the event

Also, since the app implements a single use-case, it is not necessary to make the infrastructure generic (able to
process different events), so it may just "cut corners" and implement "dedicated event processing" - just for
processing wallet transactions.

For processing domain events, the outbox pattern is used:

- events are persisted along with the aggregate (wallet) mutation
- events are processed separately, outside of the aggregate (wallet's transaction) processing

This approach separates transaction processing from RabbitMQ event publishing and should lead to a more robust
solution.

### Parallel wallet processing

To maintain the wallet's balance correctly, all transactions must be applied sequentially, so the balance can be
verified before each operation. To achieve high-throughput, the implementation must allow different wallets to be
processed in parallel, so the 1st wallet doesn't block the 2nd.
There are multiple options how to implement that:

1. DB Pessimistic lock
2. Redis lock
3. Separate processing queue for each wallet

While the 3rd option promises the best performance (because it is lock-free), it requires the most complicated
configuration - Load-Aware Load Balancing (otherwise it falls back to "Redis Lock").

A DB pessimistic lock requires a DB connection, so without knowing the actual load, it may be possible to introduce a
connection leak. Since Redis is present in the stack, it fits here more naturally.
Locking is used around `wallet_id`, to process transactions for a wallet sequentially, but it also needs to check if 2
concurrent requests use the same `transaction_id`. (if the requests use different wallets - they bypass locking)

The lock provides ordering and must outlive the transaction, otherwise sequential processing is not guaranteed, so the
lock state is re-checked before commit. 

Correctness does not depend on the Redis lock: 
 - the conditional `UPDATE` (which can never drive the balance below zero) and the primary-key guards are enforced by the database 
 - the Redis lock keeps same-wallet requests from piling up on DB row locks and preserves processing order.

### Idempotency

`transaction_id` is the primary key of `wallet_ledger`, so every request is re-checked before updating the balance.
If strict idempotency is enforced, 
    - each request processed only once
    - same response will be provided each time
otherwise
    - rejected request is not recorded and may be re-applied later
    - so response for the same request will depends on wallet state
In both cases, if request reuse transaction_id that was already recorded in system (all requests or successful requests) system will report error

A replay returns the stored movement together with the wallet balance recorded after that movement was applied.

### Outbox implementation

Let's choose the "traditional" (RDBMS-centric) approach for outbox implementation:

- read events with a lock from DB (`select ... for update skip locked`) and mark them `processing` -
  that excludes the events from processing by other nodes
- publish events outside the DB transaction
- update the status of processed events
    - remove events only after an acked confirm with no returned message (publisher confirms + mandatory returns);
      everything else counts as failed
    - increase the counter for failed events
        - this allows multiple attempts to process each event
        - rows are claimed only while `attempts < maxAttempts`; when the limit is reached, the event is left in the
          table as-is (with a warning) - no DLQ in this demo
- crash-stuck `processing` events are reset to `pending` by a scheduled reclaimer once they have been claimed for
  longer than the processing timeout (`claimed_at`); rows claimed before the column existed fall back to `created_at`
- to handle possible RabbitMQ downtime a simple circuit-breaker pauses publishing for a fixed delay after a
  whole-batch failure (`wallet.outbox.retry-delay`)
    - this slows down burning of the retry limit: with the defaults (10 attempts, 1 minute pause) a broker outage
      of roughly 10 minutes is survived
    - longer outages park the remaining rows with a warning 
    - no DQL implementation
- delivery is at-least-once (rows are deleted only after an ack), so consumers must dedupe on `transaction_id`
