# WalletEngine: Spring Data JPA → Spring Data JDBC experiment

Date: 2026-10-12
Status: design approved in chat; spec under review

## Context

WalletEngine persists through Spring Data JPA (Hibernate). The persistence surface is small and
already written in a query-driven, anemic style:

- 4 entities: `Wallet`, `WalletTransaction`, `WalletFailedTransaction`, `WalletOutboxMessage`
  (assigned UUID ids, no relations, no lazy loading).
- 4 repositories consumed by `WalletService` (movements, idempotency) and `WalletOutboxPublisher`
  (claim → publish → ack), with an explicit `@Modifying` balance UPDATE and a native
  `claim(... order by seq for update skip locked)`.

Question: what does an idiomatic Spring Data JDBC implementation of the same workload look like,
and which of the two implementations is simpler, less complex, and how do they compare on runtime
performance?

## Goal and success criteria

- Build a complete, idiomatic Spring Data JDBC implementation on a branch.
- **Correctness bar:** the existing test suite passes unchanged; tests stay the arbiter of the
  contract, not the comparison subject.
- **Comparison criteria:** (1) code simplicity and complexity — primary; (2) performance — secondary.
- **Deliverable:** experiment branch in a git worktree + a comparison report.

## Constraints

- Flyway schema (`V1`, `V2`) unchanged.
- gRPC contract and observable service semantics unchanged (responses must still carry non-null
  `updatedAt` / `createdAt` timestamps).
- Tests unchanged. They pin the API contract:
  - repository methods used directly: `claim`, `markFailed`, `markProcessing`,
    `deleteByTransactionIds`, `resetStaleProcessing`, `findById`, `findAllById`;
  - entity constructors and `getX()` getters (this rules out record entities);
  - `WalletGrpcService` maps `updatedAt` into gRPC responses for freshly created/saved instances,
    so timestamps must be populated in memory, not only by DB defaults.
- `master` stays untouched; all experiment commits land on `experiment/spring-data-jdbc`
  (worktree `alpeca/WalletEngine-jdbc`, created from `430d1af`).
- No merge; the merge/keep decision is out of scope and belongs to the user afterwards.

## JDBC design (idiomatic, behavior-preserving)

| Concern | JPA today | JDBC side |
|---|---|---|
| Entities | `@Entity` + jakarta annotations | `@Table`/`@Id` from `org.springframework.data.relational.core.mapping`; same classes, getters, constructors |
| Newness | `Persistable` + `@PostLoad`/`@PostPersist` + `isNew` flag | `Persistable.isNew()` without lifecycle callbacks: every row is created once and saved once; the PK guards duplicates |
| Timestamps | Hibernate `@CreationTimestamp` (in-memory) | `Instant.now()` in constructors; balance UPDATE keeps `CURRENT_TIMESTAMP` |
| `seq` bigserial | `insertable=false, updatable=false` | `@ReadOnlyProperty` (never written, read on load) |
| Enums | `@Enumerated(EnumType.STRING)` | stored as strings by default; drop the annotation |
| Repositories | `JpaRepository`, JPQL | `ListCrudRepository`, SQL `@Query` (+ JDBC `@Modifying`); native `claim` unchanged |
| Service | `saveAndFlush` | `save` (JDBC inserts immediately; constraint violations surface synchronously inside the same catch block) |
| Transactions | persistence context, flush/clear flags | none — statements hit the DB immediately; `TransactionTemplate`/`@Transactional` unchanged |
| Config | `spring.jpa.*` | removed |

Known behavior deltas to document in the report:

- No Hibernate schema validation (`ddl-auto=validate`) — that safety net is lost with JDBC.
- A stray `save()` on an already-persisted row now fails loudly on the PK instead of silently
  merging (JPA merge could silently reset a balance — JDBC behavior is arguably safer).
- No first-level cache; `existsById` after `updateWallet` reads fresh DB state by construction
  (JPA needed `clearAutomatically`/`flushAutomatically` for the same outcome).

A running "friction log" of concrete gotchas (annotation/API mismatches, query rewrites, mapping
surprises) is kept during the migration and included in the report.

## Performance protocol

- Same machine, same Docker infra (Postgres/Redis/RabbitMQ), runs serialized (never concurrent).
- One initial JPA confirmation run (pre-migration, also sanity-checks the baseline is green).
- Then interleaved JPA/JDBC full-suite runs, 3 per branch; alternating order to cancel drift
  (J→D, J→D, J→D as far as the initial run allows).
- Each run records: wall-clock duration, `mvn test` exit status, and a copy of
  `target/surefire-reports/*.xml` for per-test timings.
- Comparison focuses on: total wall time medians; the slow concurrency/stress tests; the outbox
  publisher tests. Noise caveats (JIT, DB state, machine load) are documented with the results.
- No benchmark harness is added — the measurement apparatus stays identical on both sides.

## Report

Committed on the experiment branch at `docs/experiments/2026-10-12-jpa-vs-jdbc.md`:

1. Setup and correctness results (suite green, unchanged tests).
2. Diff statistics vs `master` (files, ±LOC, production code only plus pom/config).
3. Concern-by-concern comparison table (as above, with a verdict per row).
4. Friction log — what each framework fights you on for this workload.
5. Performance table with caveats.
6. Recommendation, including where a hybrid (JPA here / JDBC there) would land.

## Non-goals

- No schema changes, no new features, no observable behavior changes.
- No commits to `master`, no merge, no tag.
- No decision about the future of the JPA or JDBC implementation.
