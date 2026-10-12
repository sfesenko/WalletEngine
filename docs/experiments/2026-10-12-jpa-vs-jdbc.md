# JPA vs Spring Data JDBC — WalletEngine experiment

Date: 2026-10-12 · Branch: `experiment/spring-data-jdbc` (worktree) off `master` @ `430d1af` ·
Design: `docs/superpowers/specs/2026-10-12-jpa-vs-jdbc-design.md` ·
Plan: `docs/superpowers/plans/2026-10-12-jpa-vs-jdbc-migration.md`

## Question and criteria

What does an *idiomatic* Spring Data JDBC implementation of WalletEngine look like next to the existing JPA
one — and which is **simpler / less complex** (primary criterion) and **faster** (secondary)?

Both sides are idiomatic for their framework: `master` is the hand-tuned JPA implementation; the branch is a
JDBC rewrite written the way Spring Data JDBC wants it (not a line-by-line port). The test suite is the
fairness anchor and was kept byte-identical.

## Setup and correctness

- Persistence swap: `spring-boot-starter-data-jpa(-test)` → `spring-boot-starter-data-jdbc(-test)`;
  `spring.jpa.*` properties removed; the 4 entities and 4 repositories adapted; `WalletService` changed a
  single call (`saveAndFlush` → `save`). The publisher, lock manager, gRPC layer, and outbox orchestration
  needed **zero** logic changes.
- **Tests unchanged**: `git diff master...HEAD -- src/test` is empty.
- **77/77 green on both sides.** The branch passed the full targeted smoke (23 tests) and then the full suite
  (including `slow`-tagged concurrency/stress tests) on the *first* run — no fixes required. The migration
  commit (`751d77b`) is the only functional commit.
- All six measurement runs were individually verified to execute exactly 77 tests with 0 failures.

## Size and complexity evidence

| Metric | JPA (master) | Spring Data JDBC (branch) |
|---|---|---|
| Files touched by the swap | — | 11 (`+82 / −167` lines) |
| Production Java LOC (`src/main`) | 1412 | 1331 (**−81**) |
| Mapping annotations in the 4 entity classes | 51 | 10 |
| Framework import lines in the 4 entities | 30 | 14 |
| Persistence-context escape hatches (`clearAutomatically`, `flushAutomatically`, `saveAndFlush`, `nativeQuery`) | 9 | **0** |
| JPQL query strings | 4 | 0 (all SQL) |
| Test files/resources changed | — | **0** |

## Concern-by-concern comparison

| Concern | JPA today | Spring Data JDBC | Verdict |
|---|---|---|---|
| Entity mapping | `@Entity`/`@Table` + `@Column` on every field (`name`, `nullable`) | `@Table` + `@Id`; columns via snake_case convention (`@Column` only where the schema deviates — exactly one case: `error_code`) | **JDBC**: 51 → 10 annotations; conventions replace repetition |
| Newness detection | `Persistable` + `isNew` field + `@PostLoad`/`@PostPersist` callbacks | `Persistable` with constant `isNew() == true` (every `save()` is an insert; guarded UPDATE queries handle all mutations) | **JDBC**: 3 fields, 4 callbacks, all `@Transient` gone; a stray `save()` on an existing row now fails loudly on the PK instead of silently merging |
| Timestamps | Hibernate `@CreationTimestamp` (set in memory) | `Instant.now()` in the constructor | **Tie**: JPA is shorter per field; JDBC is explicit and framework-free |
| DB-generated `seq` (bigserial) | `insertable=false, updatable=false` | `@ReadOnlyProperty` | **Tie**: same intent, comparable cost |
| Enums | `@Enumerated(EnumType.STRING)` ×2 | none — enums map to names by default | **JDBC**: annotation deleted, behavior identical (verified) |
| Queries | mixed JPQL (`@Query`) + raw SQL (`nativeQuery=true`) | all raw SQL (`@Query` is SQL by definition) | **JDBC**: no dual dialect; for an already SQL-shaped workload JPQL was translation, not abstraction |
| Modifying queries | `@Modifying(clearAutomatically, flushAutomatically)` ×5 | `@Modifying` ×5 | **JDBC**: flush/clear flags exist only because a persistence context exists |
| Constraint-check timing | `saveAndFlush` needed to surface PK violations inside the transaction | `save` does it inherently (statement hits the DB immediately) | **JDBC**: workaround disappears |
| Post-update reads | `existsById` after bulk UPDATE relied on `clearAutomatically` for freshness | no cache — every statement sees the DB | **JDBC**: a class of subtle staleness bugs is structurally absent |
| Transactions / lock orchestration | `TransactionTemplate`, `@Transactional` | identical | **Tie**: same Spring semantics |
| Repository interfaces | `JpaRepository` | `ListCrudRepository` | **Tie** (same footprint; only the `saveAndFlush` call site changed) |
| Schema safety | `ddl-auto=validate` checks entities against the schema at startup | nothing — schema is Flyway-only | **JPA**: the swap loses a startup consistency check |
| Tooling | Hibernate SQL logging, JPQL, metamodel, large ecosystem | plain JDBC; fewer moving parts | **JPA** on depth, **JDBC** on simplicity |

## Friction log — what the migration actually cost

Kept live during the migration. Summary:

**Infrastructure (not the framework).** The git worktree derives a different docker-compose project name
(`walletengine-jdbc`) than the running stack and collides on host ports when Spring Boot starts dev services;
pinning `COMPOSE_PROJECT_NAME=walletengine` fixed it (Boot 4.1 has no `spring.docker.compose.project`
property). This was the only real blocker of the whole exercise.

**Framework risks — all resolved by design before writing code, none needed a fallback:**

- entity materialization **without setters** (field access + the single domain constructor; no
  `@PersistenceCreator` needed),
- enum parameters in `@Modifying` SQL bind as their names (SpEL fallback `:#{#status.name()}` prepared but
  unused),
- `in (:ids)` collection expansion, `for update skip locked` in `@Query`, and `int` return counts all work,
- `@ReadOnlyProperty` cleanly models the bigserial `seq` (never written, read on load),
- `@CreationTimestamp` has no JDBC counterpart → constructor timestamps; DB defaults remain as fallback,
- test getters (`getAmount()` etc.) pinned the entity shape — records were excluded on purpose.

**Net effect:** the swap deleted ~85 lines (comments included) while keeping every behavior the tests pin.
`WalletOutboxPublisher`, `LockManager`, `WalletGrpcService(Advice)`, and the service's transaction/idempotency
logic are untouched.

## Performance

Protocol: identical command (`mvn test`, full suite including slow concurrency/stress tests), same machine,
same Docker stack, runs strictly serialized and interleaved (JDBC→JPA→JDBC→JPA→JDBC) to cancel drift.
Per-test times from surefire XML; medians per branch; no benchmark harness added — the measurement apparatus
is identical on both sides.

| Run | Wall time | Sum of test times |
|---|---|---|
| JPA baseline (pre-migration) | 69 s | 54.8 s |
| JPA run 2 | 76 s | 60.3 s |
| JPA run 3 | 66 s | 51.1 s |
| **JPA median** | **69 s** | **54.8 s** |
| JDBC run 1 | 67 s | 55.5 s |
| JDBC run 2 | 66 s | 52.4 s |
| JDBC run 3 | 64 s | 50.2 s |
| **JDBC median** | **66 s** | **52.4 s** |

Per-class medians (seconds):

| Test class | JPA | JDBC | Δ |
|---|---|---|---|
| `WalletConcurrencyTest` (slow stress; dominates the suite) | 37.56 | 34.87 | **−7.2 %** |
| `WalletOutboxPublisherTest` | 7.16 | 7.16 | ~0 |
| `WalletOutboxPublisherConcurrencyTest` (slow) | 3.21 | 3.22 | ~0 |
| `LockManagerFunctionalTest` | 3.24 | 3.24 | ~0 |
| `WalletGrpcFunctionalTest` | 2.65 | 2.58 | −2.6 % |
| `WalletEngineApplicationTests` (context startup) | 0.42 | 0.59 | +39 % (0.2 s absolute) |
| `WalletMovementTest` | 0.24 | 0.25 | +4 % |
| `WalletIdempotencyStrictTest` | 0.14 | 0.14 | 0 |
| `WalletOutboxPublisherFailureTest` | 0.25 | 0.25 | 0 |
| `WalletOutboxMessageRepositoryTest` | 0.11 | 0.10 | −14 % (0.01 s absolute) |

**Reading:** the two implementations are performance-equivalent within noise (JPA's `results` spread alone is
51–60 s run to run). The only >5 % signal — JDBC ~7 % faster on the concurrency stress test — is plausible
(no persistence-context/flush machinery on the write path) but not conclusive at n=3; treat it as "no
regression, possibly slight edge". Caveats: single machine, shared database state, JIT/context-cache jitter;
one master-checkout run directory contained a stale surefire XML from a previous build that was excluded from
the analysis — every run was re-verified to execute exactly 77 tests before numbers were taken.

## Recommendation

For this codebase — anemic rows, explicit SQL, no object graph, values created once and mutated only through
guarded UPDATE statements — **Spring Data JDBC is the simpler and cleaner fit**:

- the mapping layer collapses (51 → 10 annotations; conventions replace repetitive `@Column`),
- the persistence-context escape hatches (flush/clear flags, `saveAndFlush`, entity callbacks) disappear,
  removing a class of subtle bugs rather than adding one,
- behavior is identical: the unchanged 77-test suite passes, concurrency stress included,
- performance is equivalent (within noise) — the hot path was already an explicit UPDATE in both.

What JPA still offers that this project uses or could use: startup schema validation (`ddl-auto=validate`),
richer tooling, and a more common contributor skill set. If the direction is taken, the follow-ups are:
replace the lost schema validation with a lightweight check (e.g. a schema-asserting test or migration lint),
update `README.md`/`docs` stack mentions, and team alignment. A "keep JPA" decision is defensible only if the
roadmap moves toward real object graphs, lazy loading, or JPQL-driven querying — none of which this domain
needs; an append-only ledger wants plain SQL.

**Recommendation: adopt Spring Data JDBC if simplicity matters more than Hibernate's tooling; it is a strict
simplification here, with one safety net (schema validation) to consciously replace.**

## Reproducing

```bash
# branch checkout = this worktree; JPA side = the master checkout
COMPOSE_PROJECT_NAME=walletengine mvn test        # full suite, both checkouts
# measurement harness: /tmp/opencode/jpa-jdbc-perf/run.sh <label> <checkout-dir>
```
