# Wallet Engine — Design

## Requirement analysis

Needs to implement simple transaction processing engine, and maintain invariants:

- wallet balance >= 0, need to check balance for each operation
- wallets processing must be independent, to not block each other - high-throughput requirement
- processing must be robust - data integrity is top priority
- no requirements for `amount` and `currency` - free to choose better (simplest) option
- duplicate `transaction_id` must return the original result, not re-execute the movement
- immutable transaction log `wallet_ledger` may be enforced via DB constraints.

While requirements doesn't mention how wallets get created, may consider possibilities

- create wallet on demand (1st usage)
    - PROS: system will work as all wallets already present with 0 balance
    - CONS: system behaviour may be less explicit
- introduce additional api for wallet creation
    - PROS: explicit system behaviour
    - CONS: more complex api
- just rely on pre-defined wallets, that already present in DB
    - PROS: the simplest solution
    - CONS: not flexible approach

Separate api - is what real system should use, so let's implement that approach.

## Assumptions

- assume there is single currency, no currency per wallet / transaction
- integer amounts - int64 must be enough for any reasonable amount
- no ID generation - all requests will contain `wallet_id` / `transaction_id`
- only successful movements recorded; rejected requests leave no trace
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

For each operation, it'll require to calculate wallet's balance. While it's still possible to just sum(amount) for all
wallet's transaction, that approach won't scale. So, to guarantee O(1) for balance calculation, it'll require to
maintain wallet balance.
There are 2 options, where it's possible to keep wallet balance:

- Redis
    - PROS:
        - cheap access
    - CONS:
        - still need to sum(amount) for all wallet's transaction for the 1st balance calculation
        - hard to guarantee stable performance due to balance lazy-loading
- DB (Postgres)
    - PROS:
        - scalable approach
    - CONS:
        - probably slower than Redis
- Hybrid: DB + Redis
  May provide better from both approaches, but at cost of higher complexity

So usage of DB (Postgres) here looks like the most pragmatic solution.
Also it fits nicely into a DDD approach:

- use transaction to maintain aggregate invariant (transaction + wallet's balance)
- use domain events for workflows connection (RabbitMQ event publishing)

While it may be tempting to use transaction (`wallet_ledger`) table as domain event itself, it will introduce
unnecessary abstraction leaks and may affect implementation quality. So it would be better to use separate entity for
domain event storage.

So, for each transaction processing it'll need

- adjust and check wallet's balance
- persist transaction
- persist event

Also, since our app implements single use-case, it's not necessary to make infrastructure generic (able to process
different events), so may just "cut corners" and implement "dedicated event processing" - just for processing wallet's
transactions.

For processing domain events may implement outbox pattern:

- events get persisted along with aggregate (wallet) mutation
- event get processed separately, outside of aggregate (wallet's transaction) processing

This approach allows us to separate transaction processing and RabbitMQ event publishing, and should lead to more robust
solution.

### Parallel wallet processing

To maintain wallet's balance correctly, all transactions must be applied sequentially, so balance may be verified before
each operation. And to achieve high-throughput, implementation must allow to process different wallets in parallel, so
1st wallet won't block 2nd.
There are multiple options how to implement that:

1. DB Pessimistic lock
2. Redis lock
3. Separate processing queue for each wallet

While 3rd option promises the better performance (because of lock-free) require the most complicated configuration -
Load-Aware Load Balancing (otherwise it'll fallback to "Redis Lock")

DB pessimistic lock require DB connection, so without knowing actual load, it may be possible to introduce connection
leak. Since Redis is present in stack it fits here more naturally.
Locking will be used around `wallet_id`, to process transactions for wallet sequentially, but also needs to check if 2
concurrent requests use same `transaction_id`. (if request will use different wallets - they bypass locking)

The lock provides ordering, and must outlive transaction, otherwise sequential processing is not guaranteed. so it'll
require to check lock state before commit.

### Idempotency

`transaction_id` is the primary key of `wallet_ledger` so each request is re-checked before touching the balance.
Duplicated `transaction_id` means no balance adjustment - same wallet returns the saved state (amount is not
re-checked); different wallet is a conflicting reuse and reports an error.

### Outbox implementation

Let's choose "traditional" (RDBMS-centric) approach for outbox implementation

- read events with lock from DB (select .. for update skip locked)
- update status - that allow to "exclude" events from processing by other nodes
- publish events outside DB transaction
- update status of processed events
    - remove events only after an acked confirm with no returned message (publisher confirms + mandatory returns);
      everything else counts as failed
    - increase counter for failed events
        - this allows to have multiple attempt to process each event
        - when MAX_ATTEMPTS counter is reached, event may be moved into DLQ storage (or just left in table as is for our
          case)
- crash-stuck `processing` events may be reset to `pending` by a scheduled reclaimer once they are older than the
  processing timeout
- to handle possible RabbitMQ downtime may implement simple circuit-breaker, that will increase publishing time, if
  publishing was failed
    - this allow to not "burn" retry limit in case of service downtime
