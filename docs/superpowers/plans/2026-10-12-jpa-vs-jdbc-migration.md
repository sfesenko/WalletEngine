# JPA → Spring Data JDBC Migration (WalletEngine) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A complete, idiomatic Spring Data JDBC implementation of WalletEngine on `experiment/spring-data-jdbc`, passing the unchanged test suite, with JPA-vs-JDBC comparison data for simplicity and performance.

**Architecture:** Swap the persistence mechanics only — same classes, same packages, same SQL semantics, same transactions. JPA-specific lifecycle (`@CreationTimestamp`, `Persistable` callbacks, JPQL, `saveAndFlush`) is replaced by Spring Data JDBC conventions (constructor timestamps, convention-based column mapping, SQL `@Query`, direct inserts). JPA-idiomatic design was approved for the *comparison basis*; JDBC side is written in JDBC-idiomatic style (immutability-friendly, no persistence context).

**Tech Stack:** Java 21, Spring Boot 4.1.1, `spring-boot-starter-data-jdbc`, PostgreSQL 17, Flyway, Testcontainers-free docker-compose dev services.

**Spec:** `docs/superpowers/specs/2026-10-12-jpa-vs-jdbc-design.md` (on this branch)

## Global Constraints

- Test sources and test resources are NOT modified (they are the fairness anchor and the API contract).
- No schema/Flyway changes; no behavioral changes; no gRPC contract changes.
- All commits on `experiment/spring-data-jdbc` only. `master` untouched.
- All test runs export `COMPOSE_PROJECT_NAME=walletengine` (worktree would otherwise create a conflicting compose project).
- Worktree: `/home/sfesenko/Projects/test/alpeca/WalletEngine-jdbc`; JPA-side perf re-runs execute in `/home/sfesenko/Projects/test/alpeca/WalletEngine` (master checkout, same commit).
- Perf harness: `/tmp/opencode/jpa-jdbc-perf/run.sh <label> <project-dir>`; results in `/tmp/opencode/jpa-jdbc-perf/<label>/` (duration, mvn log, surefire XMLs).

## Review Focus

- **Timestamp non-null on freshly saved entities** (gRPC responses dereference `updatedAt`/`createdAt`; `@CreationTimestamp` is gone) — `WalletGrpcFunctionalTest`, `WalletMovementTest.getBalanceReturnsSeededWallet` assert this.
- **`claim` result mapping** (native `select *`, `@ReadOnlyProperty seq`, enum status) — `WalletOutboxMessageRepositoryTest` asserts seq ordering + status transitions.
- **Enum parameters in `@Modifying` queries** (markProcessing/markFailed take `Status`; DB column is varchar) — `WalletOutboxMessageRepositoryTest` + publisher tests.
- **Entity materialization via domain constructor + field population** (entities have no setters; two constructors each) — exercised by every repository read.
- **Duplicate-insert exception type** must remain `DataIntegrityViolationException` (service catches it for idempotency) — `WalletMovementTest`, `WalletIdempotencyStrictTest`, `WalletConcurrencyTest`.

---

### Task 1: Baseline capture (JPA)

**Files:**
- No repository files. Harness: `/tmp/opencode/jpa-jdbc-perf/run.sh` (already created; pins `COMPOSE_PROJECT_NAME=walletengine`).

**Interfaces:**
- Produces: baseline artifacts `/tmp/opencode/jpa-jdbc-perf/jpa-baseline1/{duration-seconds.txt,exit-code.txt,mvn.log,surefire-reports/}` consumed by Task 5.

- [ ] **Step 1: Run full suite on unmodified code** (already launched in background)

```bash
bash /tmp/opencode/jpa-jdbc-perf/run.sh jpa-baseline1 /home/sfesenko/Projects/test/alpeca/WalletEngine-jdbc
```

Expected: `rc=0`, ~77 tests, 0 failures. (First attempt failed on compose project name mismatch; fixed via `COMPOSE_PROJECT_NAME`; a single-class verification run then passed 5/5.)

- [ ] **Step 2: Confirm baseline green**

Check `exit-code.txt` == `0` and no `[ERROR]` failures in `mvn.log`. If not green: stop and report — a broken baseline invalidates the experiment.

---

### Task 2: Swap persistence machinery to Spring Data JDBC

**Files:**
- Modify: `pom.xml`
- Modify: `src/main/resources/application.properties`
- Modify: `src/main/java/com/github/walletengine/wallet/Wallet.java`
- Modify: `src/main/java/com/github/walletengine/wallet/WalletTransaction.java`
- Modify: `src/main/java/com/github/walletengine/wallet/WalletFailedTransaction.java`
- Modify: `src/main/java/com/github/walletengine/publisher/WalletOutboxMessage.java`
- Modify: `src/main/java/com/github/walletengine/wallet/WalletRepository.java`
- Modify: `src/main/java/com/github/walletengine/wallet/WalletTransactionRepository.java`
- Modify: `src/main/java/com/github/walletengine/wallet/WalletFailedTransactionRepository.java`
- Modify: `src/main/java/com/github/walletengine/publisher/WalletOutboxMessageRepository.java`
- Modify: `src/main/java/com/github/walletengine/wallet/WalletService.java`

**Interfaces:**
- Produces: unchanged public signatures —
  - `WalletRepository.updateWallet(UUID walletId, long amount): int` (guarded balance UPDATE),
  - `WalletOutboxMessageRepository.claim(String status, int maxAttempts, int size): List<WalletOutboxMessage>`,
  - `markProcessing(Collection<UUID>, Status): int`, `markFailed(UUID, String, Status): int`, `deleteByTransactionIds(Collection<UUID>): int`, `resetStaleProcessing(long): int`,
  - entity constructors/getters unchanged (`new Wallet(UUID)`, `new WalletTransaction(UUID,UUID,long)`, `new WalletFailedTransaction(UUID,UUID,long,Error)`, `new WalletOutboxMessage(UUID,UUID,long)`).
- Consumes: nothing from earlier tasks; Task 3 validates.

- [ ] **Step 1: Swap dependencies and config**

`pom.xml`: replace starter `spring-boot-starter-data-jpa` → `spring-boot-starter-data-jdbc`; test starter `spring-boot-starter-data-jpa-test` → `spring-boot-starter-data-jdbc-test` (4.1.1 exists on Central).

`application.properties`: delete the JPA block (comment + `spring.jpa.hibernate.ddl-auto=validate` + `spring.jpa.open-in-view=false`).

- [ ] **Step 2: Rewrite the four entities (delete JPA/Hibernate imports; keep classes, ctors, getters)**

Mapping conventions to apply:
- `@Table("...")` (`org.springframework.data.relational.core.mapping.Table`) on all four: `wallets`, `wallet_ledger`, `rejected_movements`, `wallet_outbox`.
- `@Id` (`org.springframework.data.annotation.Id`) on the id field.
- Columns follow the snake_case convention → no `@Column` needed, except `WalletFailedTransaction.error` → `@Column("error_code")`.
- Drop `@Enumerated` (enums map to names by default), `@Transient` + `isNew` field + `@PostLoad`/`@PostPersist` (Spring Data JDBC has no lifecycle callbacks; see next).
- `Persistable.isNew()` returns `true` (constant): every row is created once via `save()`; balance/status changes go through guarded UPDATE queries; the natural PK guards duplicates. Update the Javadoc accordingly.
- `@CreationTimestamp` → set in constructor: `Wallet.updatedAt = Instant.now()`, `WalletTransaction.updatedAt = Instant.now()`, `WalletFailedTransaction.createdAt = Instant.now()`, `WalletOutboxMessage.createdAt = Instant.now()` (preserves in-memory non-null contract for gRPC responses; balance UPDATE keeps `CURRENT_TIMESTAMP`).
- `WalletOutboxMessage.seq`: `@ReadOnlyProperty` (`org.springframework.data.annotation.ReadOnlyProperty`) — never written, read on load (bigserial).
- Drop Hibernate `@Immutable` (JDBC does no dirty checking; append-only is enforced by SQL + DB trigger).

- [ ] **Step 3: Rewrite repositories**

- `JpaRepository` → `ListCrudRepository` (`org.springframework.data.repository.ListCrudRepository`) in all four.
- `@Query`/`@Modifying` → `org.springframework.data.jdbc.repository.query.Query` / `...query.Modifying`; queries become plain SQL (no JPQL); drop `nativeQuery=true` and the JPA-only attributes `clearAutomatically`/`flushAutomatically`.
- `WalletRepository.updateWallet`: SQL `update wallets set amount = amount + :amount, updated_at = CURRENT_TIMESTAMP where wallet_id = :walletId and amount + :amount >= 0`, return `int`.
- Outbox: `claim` keeps the `for update skip locked` SQL; `markProcessing`/`deleteByTransactionIds` keep `... where transaction_id in (:ids)`; `resetStaleProcessing` keeps the interval arithmetic; `markFailed` keeps the increments; keep the interface-level `@Transactional`.
- `@Param` annotations only where needed (compiler has `-parameters`); keep param names identical to query placeholders.

- [ ] **Step 4: Service adjustment**

`WalletService.createWallet`: `saveAndFlush` → `save` (JDBC inserts immediately; the PK violation surfaces inside the transaction synchronously). Update the comment; keep the same catch block.

- [ ] **Step 5: Compile**

```bash
mvn -q -DskipTests compile
```

Expected: success. Fix any remaining `jakarta.persistence`/`org.hibernate` references.

- [ ] **Step 6: Targeted fast tests (smoke)**

```bash
COMPOSE_PROJECT_NAME=walletengine mvn -B -Pfast-test test -Dtest='WalletMovementTest,WalletIdempotencyStrictTest,WalletOutboxMessageRepositoryTest'
```

Expected: green. Most likely failure candidates and remedies:
- enum param binding in `@Query` → use SpEL bind `:#{#status.name()}`;
- entity construction error on load → add `@PersistenceCreator` (all-args ctor) or verify field population.

- [ ] **Step 7: Commit**

```bash
git add pom.xml src/main/java src/main/resources/application.properties
git commit -m "refactor: migrate persistence from Spring Data JPA to Spring Data JDBC"
```

---

### Task 3: Full suite green (unchanged tests)

**Files:** fix-ups only, if needed (main sources).

**Interfaces:** consumes Task 2; produces green branch consumed by Task 4.

- [ ] **Step 1: Fast suite**

```bash
COMPOSE_PROJECT_NAME=walletengine mvn -B -Pfast-test test
```

Expected: green.

- [ ] **Step 2: Full suite incl. slow concurrency/stress tests**

```bash
COMPOSE_PROJECT_NAME=walletengine mvn -B test
```

Expected: green (77 tests). Iterate on failures; record each in the friction log.

- [ ] **Step 3: Friction log**

Append concrete findings to `/tmp/opencode/jpa-jdbc-perf/friction-log.md` (framework gotchas, workarounds, surprises) — input for the Task 5 report. Include: worktree compose project clash, record-type exclusion due to test getters, `@CreationTimestamp` replacement, any enum/param/callback issues actually hit.

- [ ] **Step 4: Commit any fixes**

```bash
git add -A src/main/java && git commit -m "fix: adapt <...> for Spring Data JDBC"
```

(Skip if no fixes were needed.)

---

### Task 4: Performance runs (interleaved)

**Files:** none. Artifacts under `/tmp/opencode/jpa-jdbc-perf/`.

**Interfaces:** consumes green JDBC branch + untouched JPA master checkout; produces run artifacts for Task 5.

- [ ] **Step 1: JDBC run 1**

```bash
bash /tmp/opencode/jpa-jdbc-perf/run.sh jdbc-1 /home/sfesenko/Projects/test/alpeca/WalletEngine-jdbc
```

- [ ] **Step 2: JPA run 2** (master checkout)

```bash
bash /tmp/opencode/jpa-jdbc-perf/run.sh jpa-2 /home/sfesenko/Projects/test/alpeca/WalletEngine
```

- [ ] **Step 3: JDBC run 2**

```bash
bash /tmp/opencode/jpa-jdbc-perf/run.sh jdbc-2 /home/sfesenko/Projects/test/alpeca/WalletEngine-jdbc
```

- [ ] **Step 4: JPA run 3**

```bash
bash /tmp/opencode/jpa-jdbc-perf/run.sh jpa-3 /home/sfesenko/Projects/test/alpeca/WalletEngine
```

- [ ] **Step 5: JDBC run 3**

```bash
bash /tmp/opencode/jpa-jdbc-perf/run.sh jdbc-3 /home/sfesenko/Projects/test/alpeca/WalletEngine-jdbc
```

- [ ] **Step 6: Verify all runs green**

Check `exit-code.txt` = 0 in all five labels. A red run is re-run after the fix and the label gets a `-fixed` suffix; report only green runs and note replacements.

---

### Task 5: Analysis and comparison report

**Files:**
- Create: `docs/experiments/2026-10-12-jpa-vs-jdbc.md`
- Analysis helper (outside repo): `/tmp/opencode/jpa-jdbc-perf/analyze.py`

**Interfaces:** consumes Tasks 1/3/4 artifacts; produces the report committed on the branch.

- [ ] **Step 1: Diff statistics**

```bash
git diff --stat master...HEAD -- src/main pom.xml
git diff master...HEAD -- src/main pom.xml | git apply --numstat --summary -  # or `git diff --numstat`
```

Record: files changed, ±LOC (main sources only; docs excluded).

- [ ] **Step 2: Per-test timings**

`/tmp/opencode/jpa-jdbc-perf/analyze.py`: parse `surefire-reports/TEST-*.xml` per run; emit per-class median time for JPA (jpa-baseline1, jpa-2, jpa-3) vs JDBC (jdbc-1..3); flag slow-tagged stress tests separately; ignore the first JPA run if it shows warmup skew (documented).

- [ ] **Step 3: Write the report** with sections:
  1. Setup + correctness (suite green both sides, unchanged tests)
  2. Diff stats (files, ±LOC)
  3. Concern-by-concern table (JPA → JDBC → verdict per concern: entity mapping, newness, timestamps, generated columns, enums, queries, transactions, config)
  4. Friction log (from Task 3)
  5. Performance table + caveats (interleaved runs, medians, wall time; state that infra is shared and runs are serialized)
  6. Recommendation incl. hybrid option; list behavior deltas (`ddl-auto` validation lost, stray-save now fails loudly, no first-level cache)

- [ ] **Step 4: Commit report**

```bash
git add docs/experiments && git commit -m "docs: JPA vs Spring Data JDBC comparison report"
```

- [ ] **Step 5: Wrap up**

Report branch state to the user: branch, spec, plan (this file), report paths; test results; recommendation; explicitly note that merge/keep decisions are the user's, and `master` is untouched.
