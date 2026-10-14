package com.github.walletengine.wallet;

import com.github.walletengine.TestFixture;
import com.github.walletengine.lock.LockFailedException;
import com.github.walletengine.publisher.WalletOutboxPublisher;
import com.github.walletengine.publisher.WalletUpdatedEvent;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Concurrent wallet movements, executed once per idempotency mode
 */
abstract class AbstractWalletConcurrencyTest {

    private static final int CONCURRENCY = 20;
    private static final int STRESS_WALLETS = 4;
    // reduced from 5000 to keep the suite fast; raise for deeper soak runs
    private static final int OPS_PER_WALLET = 1000;
    private static final int SLICES_PER_WALLET = 5;
    private static final double STRESS_DUPLICATE_RATE = 0.10;
    private static final long STRESS_SEED = 42L;
    // reachable balance tops out at OPS_PER_WALLET * 1000, so this debit never succeeds
    private static final long STRESS_GUARANTEED_DEBIT = 10_000_000L;

    @Autowired
    private WalletService walletService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager txManager;

    @Autowired
    private WalletOutboxPublisher publisher;

    @Autowired
    private WalletTransactionRepository transactionRepository;

    @Autowired
    private WalletFailedTransactionRepository failedTransactionRepository;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    /**
     * Whether the context under test runs with {@code wallet.idempotency.strict=true}.
     */
    protected abstract boolean isStrictIdempotency();

    @Test
    void concurrentDebitsNeverDriveBalanceNegative() throws Exception {
        var walletId = UUID.randomUUID();
        var fundingId = UUID.randomUUID();
        var threadCount = 20;
        var debitAmount = 100L;

        walletService.createWallet(walletId);
        walletService.credit(fundingId, walletId, 1_000L);

        var tasks = new ArrayList<Callable<Boolean>>(threadCount);
        for (var i = 0; i < threadCount; i++) {
            var transactionId = UUID.randomUUID();
            tasks.add(() -> tryDebit(transactionId, walletId, debitAmount));
        }

        var results = runConcurrently(tasks, threadCount);

        var successes = results.stream().filter(it -> it).count();

        var moved = successes * debitAmount;
        var wallet = walletService.getWalletById(walletId);

        assertThat(successes).isEqualTo(10);
        assertThat(moved).isEqualTo(1_000L);
        assertThat(wallet.getAmount()).isZero();
        assertThat(ledgerSum(walletId)).isZero();
        // strict mode records every state-dependent rejection; non-strict leaves no trace
        assertThat(failedTransactionCount(walletId)).isEqualTo(isStrictIdempotency() ? 10 : 0);
    }

    @Test
    void concurrentMovementsScenarioAppliesEveryMovementExactlyOnce() throws Exception {
        // operation | wallet | transaction | amount — verbatim duplicates are intentional.
        var rows = List.of(
                new WalletOp("credit", "w0", "t1", 1000L),
                new WalletOp("credit", "w0", "t2", 500L),
                new WalletOp("debit", "w0", "t3", 200L),
                new WalletOp("credit", "w0", "t2", 500L),
                new WalletOp("debit", "w0", "t3", 200L),
                new WalletOp("credit", "w1", "t4", 2000L),
                new WalletOp("debit", "w1", "t5", 700L),
                new WalletOp("credit", "w1", "t4", 2000L),
                new WalletOp("credit", "w2", "t6", 1500L),
                new WalletOp("debit", "w2", "t7", 1500L),
                new WalletOp("credit", "w2", "t8", 300L),
                new WalletOp("debit", "w2", "t7", 1500L));

        var walletIds = new HashMap<String, UUID>();
        var txIds = new HashMap<String, UUID>();
        for (var row : rows) {
            walletIds.computeIfAbsent(row.wallet(), k -> UUID.randomUUID());
            txIds.computeIfAbsent(row.transaction(), k -> UUID.randomUUID());
        }
        for (var walletId : walletIds.values()) {
            walletService.createWallet(walletId);
        }

        var expected = new HashMap<UUID, Long>();
        var seenTx = new HashSet<UUID>();
        var byWallet = new HashMap<UUID, List<PlannedMovement>>();
        for (var row : rows) {
            var walletId = walletIds.get(row.wallet());
            var transactionId = txIds.get(row.transaction());

            byWallet.computeIfAbsent(walletId, k -> new ArrayList<>())
                    .add(new PlannedMovement(row.operation(), transactionId, row.amount()));

            if (seenTx.add(transactionId)) {
                expected.merge(walletId, TestFixture.signedAmount(row.operation(), row.amount()), Long::sum);
            }
        }

        // one task per wallet: each wallet's rows stay in declared order while wallets run concurrently
        var tasks = new ArrayList<Callable<Void>>(byWallet.size());
        for (var group : byWallet.entrySet()) {
            var walletId = group.getKey();
            var ops = group.getValue();
            tasks.add(() -> {
                for (var op : ops) {
                    TestFixture.perform(walletService, op.operation(), op.transactionId(), walletId, op.amount());
                }

                return null;
            });
        }

        runConcurrently(tasks, CONCURRENCY);

        assertThat(expected).containsOnlyKeys(byWallet.keySet());
        for (var balance : expected.entrySet()) {
            var wallet = walletService.getWalletById(balance.getKey());

            assertThat(wallet.getAmount()).isEqualTo(balance.getValue());
            assertThat(ledgerSum(balance.getKey())).isEqualTo(balance.getValue());
            // the scenario holds only movements that succeed, so nothing is rejected in either mode
            assertThat(failedTransactionCount(balance.getKey())).isZero();
        }

        // scenario totals: w0 = 1300, w1 = 1300, w2 = 300
    }

    @Test
    void rejectedMovementReplayFollowsIdempotencyMode() {
        var walletId = UUID.randomUUID();
        var transactionId = UUID.randomUUID();
        walletService.createWallet(walletId);
        walletService.credit(UUID.randomUUID(), walletId, 1_000L);

        assertThatThrownBy(() -> walletService.debit(transactionId, walletId, 2_000L))
                .isInstanceOf(InsufficientFundsException.class);

        if (isStrictIdempotency()) {
            assertThat(failedTransactionRepository.findById(transactionId)).isPresent();
        } else {
            assertThat(failedTransactionRepository.findById(transactionId)).isEmpty();
        }

        // funds arrive: only non-strict mode may re-execute the rejected movement
        walletService.credit(UUID.randomUUID(), walletId, 2_000L);

        if (isStrictIdempotency()) {
            assertThatThrownBy(() -> walletService.debit(transactionId, walletId, 2_000L))
                    .isInstanceOf(InsufficientFundsException.class);
            assertThat(walletService.getWalletById(walletId).getAmount()).isEqualTo(3_000L);
            assertThat(transactionRepository.findById(transactionId)).isEmpty();
        } else {
            walletService.debit(transactionId, walletId, 2_000L);
            assertThat(walletService.getWalletById(walletId).getAmount()).isEqualTo(1_000L);
            assertThat(transactionRepository.findById(transactionId)).isPresent();
        }
    }

    @Test
    void conflictingReuseOfAppliedTransactionIsRejectedInBothModes() {
        var walletId = UUID.randomUUID();
        var transactionId = UUID.randomUUID();
        walletService.createWallet(walletId);
        walletService.credit(transactionId, walletId, 100L);

        // same transaction id with a different amount: a conflict, not a replay
        assertThatThrownBy(() -> walletService.debit(transactionId, walletId, 50L))
                .isInstanceOf(IllegalTransactionException.class);

        assertThat(walletService.getWalletById(walletId).getAmount()).isEqualTo(100L);
        assertThat(failedTransactionRepository.findById(transactionId)).isEmpty();
    }

    @Tag("slow")
    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    void concurrentStress() throws Exception {
        var opsByWalletKey = stressOps();

        var walletIds = new HashMap<String, UUID>();
        var txIds = new HashMap<String, UUID>();
        var walletByTx = new HashMap<UUID, UUID>();
        for (var entry : opsByWalletKey.entrySet()) {
            var walletId = walletIds.computeIfAbsent(entry.getKey(), k -> UUID.randomUUID());
            for (var op : entry.getValue()) {
                txIds.computeIfAbsent(op.txKey(), k -> UUID.randomUUID());
                walletByTx.put(txIds.get(op.txKey()), walletId);
            }
        }
        for (var walletId : walletIds.values()) {
            walletService.createWallet(walletId);
        }

        var insufficient = new AtomicInteger();
        var tasks = new ArrayList<Callable<Void>>(STRESS_WALLETS * SLICES_PER_WALLET);
        for (var entry : opsByWalletKey.entrySet()) {
            var walletId = walletIds.get(entry.getKey());
            var ops = entry.getValue();
            var sliceSize = ops.size() / SLICES_PER_WALLET;
            for (var s = 0; s < SLICES_PER_WALLET; s++) {
                var slice = ops.subList(s * sliceSize,
                        s == SLICES_PER_WALLET - 1 ? ops.size() : (s + 1) * sliceSize);
                var movements = slice.stream()
                        .map(op -> new PlannedMovement(op.operation(), txIds.get(op.txKey()), op.amount()))
                        .toList();
                tasks.add(() -> {
                    for (var movement : movements) {
                        applyWithLockRetry(movement, walletId, insufficient);
                    }
                    return null;
                });
            }
        }

        runConcurrently(tasks, CONCURRENCY);

        assertThat(insufficient.get()).isGreaterThanOrEqualTo(STRESS_WALLETS);

        var ownTxIds = new HashSet<>(txIds.values());
        var ledgerByWallet = new HashMap<UUID, Map<UUID, Long>>();
        for (var walletId : walletIds.values()) {
            var rows = jdbcTemplate.query(
                    "select transaction_id, amount from wallet_ledger where wallet_id = ?",
                    (rs, n) -> Map.entry(rs.getObject("transaction_id", UUID.class), rs.getLong("amount")),
                    walletId);
            var byTx = new HashMap<UUID, Long>();
            for (var row : rows) {
                byTx.put(row.getKey(), row.getValue());
            }

            var wallet = walletService.getWalletById(walletId);

            assertThat(rows).isNotEmpty();
            assertThat(wallet.getAmount()).isGreaterThanOrEqualTo(0L);
            assertThat(wallet.getAmount())
                    .isEqualTo(byTx.values().stream().mapToLong(Long::longValue).sum());
            ledgerByWallet.put(walletId, byTx);
        }

        for (var i = 0; i < 100 && outboxRowCount(walletIds.values()) > 0; i++) {
            publisher.publish();
        }
        assertThat(outboxRowCount(walletIds.values())).isZero();

        var eventsByTx = new HashMap<UUID, List<WalletUpdatedEvent>>();
        for (var event : TestFixture.drainEvents(rabbitTemplate)) {
            if (ownTxIds.contains(event.transactionId())) {
                eventsByTx.computeIfAbsent(event.transactionId(), k -> new ArrayList<>()).add(event);
            }
        }
        for (var entry : ledgerByWallet.entrySet()) {
            for (var applied : entry.getValue().entrySet()) {
                var events = eventsByTx.get(applied.getKey());

                assertThat(events).hasSize(1);
                assertThat(events.getFirst().walletId()).isEqualTo(entry.getKey());
                assertThat(events.getFirst().amount()).isEqualTo(applied.getValue());
            }
        }
        for (var txId : ownTxIds) {
            if (!isApplied(ledgerByWallet, txId)) {
                assertThat(eventsByTx.get(txId)).isNull();
            }
        }

        // mode-aware bookkeeping: strict records every state-dependent rejection exactly once
        // (transaction_id is the PK); non-strict leaves no trace. Applied txs are never recorded.
        var rejectedByTx = new HashMap<UUID, RejectedRow>();
        for (var walletId : walletIds.values()) {
            for (var row : jdbcTemplate.query(
                    """
                    select transaction_id, wallet_id, amount, error_code
                      from wallet_failed_transactions
                     where wallet_id = ?
                    """,
                    (rs, n) -> Map.entry(rs.getObject("transaction_id", UUID.class),
                            new RejectedRow(rs.getObject("wallet_id", UUID.class), rs.getLong("amount"),
                                    rs.getString("error_code"))),
                    walletId)) {
                rejectedByTx.put(row.getKey(), row.getValue());
            }
        }
        for (var txId : ownTxIds) {
            if (isApplied(ledgerByWallet, txId)) {
                assertThat(rejectedByTx).doesNotContainKey(txId);
            } else if (isStrictIdempotency()) {
                assertThat(rejectedByTx).containsKey(txId);
                var rejected = rejectedByTx.get(txId);
                assertThat(rejected.walletId()).isEqualTo(walletByTx.get(txId));
                assertThat(rejected.amount()).isNegative();
                assertThat(rejected.errorCode()).isEqualTo(WalletFailedTransaction.Error.INSUFFICIENT_FUNDS.name());
            } else {
                assertThat(rejectedByTx).doesNotContainKey(txId);
            }
        }
    }

    @Tag("slow")
    @Test
    void concurrentDuplicateRequestsAlwaysReturnStoredRow() throws Exception {
        // repeats: the uncommitted-winner race window is probabilistic
        for (var i = 0; i < 20; i++) {
            var walletId = UUID.randomUUID();
            var transactionId = UUID.randomUUID();
            walletService.createWallet(walletId);

            var start = new CyclicBarrier(2);
            try (var executor = Executors.newFixedThreadPool(2)) {
                var tasks = new ArrayList<Callable<WalletTransaction>>(2);
                for (var t = 0; t < 2; t++) {
                    tasks.add(() -> {
                        start.await();
                        return walletService.credit(transactionId, walletId, 500L);
                    });
                }

                var futures = executor.invokeAll(tasks);
                var first = futures.getFirst().get(10, TimeUnit.SECONDS);
                var second = futures.getLast().get(10, TimeUnit.SECONDS);

                assertThat(first.getTransactionId()).isEqualTo(transactionId);
                assertThat(second).usingRecursiveComparison()
                        // updated_at: Hibernate sets it in-memory, Postgres stores microseconds
                        .ignoringFields("updatedAt")
                        .isEqualTo(first);
                assertThat(walletService.getWalletById(walletId).getAmount()).isEqualTo(500L);
            }
        }
    }

    /** Different wallets bypass the per-wallet lock: the loser must fail on the transaction_id PK. */
    @Test
    void duplicateTransactionIdAcrossWalletsRejectsTheLoser() throws Exception {
        var firstWalletId = UUID.randomUUID();
        var secondWalletId = UUID.randomUUID();
        var transactionId = UUID.randomUUID();
        walletService.createWallet(firstWalletId);
        walletService.createWallet(secondWalletId);

        var start = new CyclicBarrier(2);
        var tasks = new ArrayList<Callable<WalletTransaction>>(2);
        tasks.add(() -> {
            start.await();
            return walletService.credit(transactionId, firstWalletId, 500L);
        });
        tasks.add(() -> {
            start.await();
            return walletService.credit(transactionId, secondWalletId, 500L);
        });

        var successes = 0;
        var conflicts = 0;
        try (var pool = Executors.newFixedThreadPool(2)) {
            var futures = pool.invokeAll(tasks);
            for (var future : futures) {
                try {
                    future.get(10, TimeUnit.SECONDS);
                    successes++;
                } catch (ExecutionException e) {
                    assertThat(e.getCause()).isInstanceOf(IllegalTransactionException.class);
                    conflicts++;
                }
            }
        }

        assertThat(successes).isEqualTo(1);
        assertThat(conflicts).isEqualTo(1);

        var movedOnce = walletService.getWalletById(firstWalletId).getAmount()
                + walletService.getWalletById(secondWalletId).getAmount();
        assertThat(movedOnce).isEqualTo(500L);
    }

    @Tag("slow")
    @ParameterizedTest
    @CsvSource(value = {
            "0    | 500   | credit | 500  | 500   | 500",
            "1000 | -1000 | debit  | 1000 | -1000 | 0  ",
    }, delimiter = '|')
    void duplicateRaceBehindCommittedWinnerReplaysStoredMovement(
            long funded, long winnerAmount, String operation, long callAmount,
            long expectedStored, long expectedBalance) throws Exception {
        var walletId = UUID.randomUUID();
        var transactionId = UUID.randomUUID();
        walletService.createWallet(walletId);
        if (funded > 0) {
            walletService.credit(UUID.randomUUID(), walletId, funded);
        }

        var outcome = raceBehindUncommittedWinner(transactionId, walletId, winnerAmount,
                () -> TestFixture.perform(walletService, operation, transactionId, walletId, callAmount));

        assertThat(outcome.failure()).isNull();
        assertThat(outcome.value()).isNotNull();
        assertThat(outcome.value().getTransactionId()).isEqualTo(transactionId);
        assertThat(outcome.value().getAmount()).isEqualTo(expectedStored);
        assertThat(walletService.getWalletById(walletId).getAmount()).isEqualTo(expectedBalance);
    }

    @Tag("slow")
    @Test
    void duplicateRaceBehindCommittedWinnerRejectsConflictingMovement() throws Exception {
        var walletId = UUID.randomUUID();
        var transactionId = UUID.randomUUID();
        walletService.createWallet(walletId);
        walletService.credit(UUID.randomUUID(), walletId, 1_000L);

        var outcome = raceBehindUncommittedWinner(transactionId, walletId, -1_000L,
                () -> walletService.debit(transactionId, walletId, 600L));

        assertThat(outcome.value()).isNull();
        assertThat(outcome.failure())
                .isInstanceOf(IllegalTransactionException.class)
                .hasMessage("transaction_id %s is already used".formatted(transactionId));
        assertThat(walletService.getWalletById(walletId).getAmount()).isZero();
    }

    private static Map<String, List<StressOp>> stressOps() {
        var rng = new Random(STRESS_SEED);

        var opsByWalletKey = new HashMap<String, List<StressOp>>();
        for (var w = 0; w < STRESS_WALLETS; w++) {
            var ops = new ArrayList<StressOp>(OPS_PER_WALLET);
            // first row: can never succeed, so the rejection path runs regardless of interleaving
            ops.add(new StressOp("debit", "s" + w + "-0", STRESS_GUARANTEED_DEBIT));
            for (var i = 1; i < OPS_PER_WALLET; i++) {
                if (rng.nextDouble() < STRESS_DUPLICATE_RATE) {
                    ops.add(ops.get(rng.nextInt(ops.size())));
                } else {
                    var operation = rng.nextBoolean() ? "credit" : "debit";
                    ops.add(new StressOp(operation, "s" + w + "-" + i, 1 + rng.nextInt(1000)));
                }
            }
            opsByWalletKey.put("s" + w, ops);
        }
        return opsByWalletKey;
    }

    private static boolean isApplied(Map<UUID, Map<UUID, Long>> ledgerByWallet, UUID transactionId) {
        return ledgerByWallet.values().stream().anyMatch(byTx -> byTx.containsKey(transactionId));
    }

    private record RaceOutcome(WalletTransaction value, RuntimeException failure) {
    }

    private record RejectedRow(UUID walletId, long amount, String errorCode) {
    }

    /** Winner commits only after the service call is blocked on the wallet row. */
    private RaceOutcome raceBehindUncommittedWinner(UUID transactionId, UUID walletId, long winnerAmount,
            Callable<WalletTransaction> serviceCall) throws Exception {
        var sideReady = new CountDownLatch(1);
        var releaseSide = new CountDownLatch(1);
        var sideFailure = new AtomicReference<Throwable>();

        var side = new Thread(() -> {
            try {
                new TransactionTemplate(txManager).executeWithoutResult(status -> {
                    jdbcTemplate.update("update wallets set amount = amount + ? where wallet_id = ?",
                            winnerAmount, walletId);
                    var walletBalance = jdbcTemplate.queryForObject(
                            "select amount from wallets where wallet_id = ?", Long.class, walletId);
                    jdbcTemplate.update(
                            "insert into wallet_ledger (transaction_id, wallet_id, amount, wallet_balance)"
                                    + " values (?, ?, ?, ?)",
                            transactionId, walletId, winnerAmount, walletBalance);
                    jdbcTemplate.update("insert into wallet_outbox (transaction_id, wallet_id, amount) values (?, ?, ?)",
                            transactionId, walletId, winnerAmount);
                    sideReady.countDown();
                    awaitLatch(releaseSide, 10_000);
                });
            } catch (Throwable t) {
                sideFailure.set(t);
                sideReady.countDown();
            }
        }, "duplicate-race-winner");
        side.setDaemon(true);
        side.start();

        try {
            awaitLatch(sideReady, 10_000);

            var serviceFailure = new AtomicReference<Throwable>();
            var serviceValue = new AtomicReference<WalletTransaction>();
            var service = new Thread(() -> {
                try {
                    serviceValue.set(serviceCall.call());
                } catch (Throwable t) {
                    serviceFailure.set(t);
                }
            }, "duplicate-race-service");
            service.setDaemon(true);
            service.start();

            awaitBlockedOnWalletUpdate();

            releaseSide.countDown(); // winner commits; the blocked movement resolves
            service.join(10_000);
            side.join(10_000);

            if (sideFailure.get() != null) {
                throw new IllegalStateException("winner transaction failed", sideFailure.get());
            }
            if (service.isAlive()) {
                throw new IllegalStateException("service call did not finish after the winner committed");
            }
            if (serviceFailure.get() != null) {
                return new RaceOutcome(null, asRuntimeException(serviceFailure.get()));
            }
            return new RaceOutcome(serviceValue.get(), null);
        } finally {
            releaseSide.countDown();
            side.join(2_000);
        }
    }

    /** Waits until a session is blocked on a wallet UPDATE — i.e. the service call under test. */
    private void awaitBlockedOnWalletUpdate() {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            var blocked = jdbcTemplate.queryForObject("""
                    select count(*)
                      from pg_stat_activity
                     where lower(query) like 'update wallets%'
                       and wait_event_type = 'Lock'
                    """, Integer.class);
            if (blocked != null && blocked > 0) {
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while waiting for the blocked movement", e);
            }
        }
        throw new IllegalStateException("service call never blocked on the winner's wallet row");
    }

    private static void awaitLatch(CountDownLatch latch, long millis) {
        try {
            if (!latch.await(millis, TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("timed out coordinating the duplicate race");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while coordinating the duplicate race", e);
        }
    }

    private static RuntimeException asRuntimeException(Throwable t) {
        if (t instanceof RuntimeException runtime) {
            return runtime;
        }
        if (t instanceof Error error) {
            throw error;
        }
        return new IllegalStateException(t);
    }

    private <T> List<T> runConcurrently(List<Callable<T>> tasks, int threads) throws Exception {
        try (var pool = Executors.newFixedThreadPool(threads)) {
            var futures = pool.invokeAll(tasks);

            var results = new ArrayList<T>(tasks.size());
            for (var future : futures) {
                var result = future.get();
                results.add(result);
            }
            return results;
        }
    }

    private boolean tryDebit(UUID transactionId, UUID walletId, long amount) {
        for (var attempt = 0; ; attempt++) {
            try {
                walletService.debit(transactionId, walletId, amount);
                return true;
            } catch (InsufficientFundsException e) {
                return false;
            } catch (LockFailedException e) {
                if (attempt >= 100) {
                    throw e;
                }
            }
        }
    }

    private void applyWithLockRetry(PlannedMovement movement, UUID walletId, AtomicInteger insufficient) {
        for (var attempt = 0; ; attempt++) {
            try {
                TestFixture.perform(walletService, movement.operation(), movement.transactionId(), walletId, movement.amount());
                return;
            } catch (InsufficientFundsException e) {
                insufficient.incrementAndGet();
                return;
            } catch (LockFailedException e) {
                if (attempt >= 100) {
                    throw e;
                }
            }
        }
    }

    private long ledgerSum(UUID walletId) {
        Long sum = jdbcTemplate.queryForObject(
                "select coalesce(sum(amount), 0) from wallet_ledger where wallet_id = ?", Long.class, walletId);
        return sum == null ? 0L : sum;
    }

    private int failedTransactionCount(UUID walletId) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from wallet_failed_transactions where wallet_id = ?", Integer.class, walletId);
        return count == null ? 0 : count;
    }

    private long outboxRowCount(Collection<UUID> walletIds) {
        var total = 0L;
        for (var walletId : walletIds) {
            Long count = jdbcTemplate.queryForObject(
                    "select count(*) from wallet_outbox where wallet_id = ?", Long.class, walletId);
            total += count == null ? 0L : count;
        }
        return total;
    }

    private record WalletOp(String operation, String wallet, String transaction, long amount) {
    }

    private record StressOp(String operation, String txKey, long amount) {
    }

    private record PlannedMovement(String operation, UUID transactionId, long amount) {
    }
}
