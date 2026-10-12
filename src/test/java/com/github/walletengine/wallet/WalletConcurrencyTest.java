package com.github.walletengine.wallet;

import com.github.walletengine.TestFixture;
import com.github.walletengine.lock.LockFailedException;
import com.github.walletengine.publisher.WalletOutboxPublisher;
import com.github.walletengine.publisher.WalletOutboxMessageRepository;
import com.github.walletengine.publisher.WalletUpdatedEvent;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concurrent wallet movements
 */
@SpringBootTest
@ActiveProfiles("test")
class WalletConcurrencyTest {

    private static final int CONCURRENCY = 20;
    private static final int STRESS_WALLETS = 4;
    private static final int OPS_PER_WALLET = 5000;
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
    private WalletOutboxMessageRepository outboxRepository;

    @Autowired
    private RabbitTemplate rabbitTemplate;

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

        var successes = 0;
        for (var applied : results) {
            if (applied) {
                successes++;
            }
        }

        var moved = successes * debitAmount;
        var wallet = walletService.getWalletById(walletId);

        assertThat(wallet.getAmount()).isZero();
        assertThat(moved).isEqualTo(1_000L);
    }

    @Test
    void concurrentMovementsScenarioAppliesEveryMovementExactlyOnce() throws Exception {
        // operation | wallet | transaction | amount — verbatim duplicates are intentional.
        var rows = List.of(
                new WalletOp("CREDIT", "w0", "t1", 1000L),
                new WalletOp("CREDIT", "w0", "t2", 500L),
                new WalletOp("DEBIT", "w0", "t3", 200L),
                new WalletOp("CREDIT", "w0", "t2", 500L),
                new WalletOp("DEBIT", "w0", "t3", 200L),
                new WalletOp("CREDIT", "w1", "t4", 2000L),
                new WalletOp("DEBIT", "w1", "t5", 700L),
                new WalletOp("CREDIT", "w1", "t4", 2000L),
                new WalletOp("CREDIT", "w2", "t6", 1500L),
                new WalletOp("DEBIT", "w2", "t7", 1500L),
                new WalletOp("CREDIT", "w2", "t8", 300L),
                new WalletOp("DEBIT", "w2", "t7", 1500L));

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
        var byWallet = new HashMap<UUID, List<WalletMovement>>();
        for (var row : rows) {
            var walletId = walletIds.get(row.wallet());
            var transactionId = txIds.get(row.transaction());

            byWallet.computeIfAbsent(walletId, k -> new ArrayList<>())
                    .add(new WalletMovement(row.operation(), transactionId, row.amount()));

            if (seenTx.add(transactionId)) {
                expected.merge(walletId, signedMovement(row.operation(), row.amount()), Long::sum);
            }
        }

        // one task per wallet: each wallet's rows stay in declared order while wallets run concurrently
        var tasks = new ArrayList<Callable<Void>>(byWallet.size());
        for (var group : byWallet.entrySet()) {
            var walletId = group.getKey();
            var ops = group.getValue();
            tasks.add(() -> {
                for (var op : ops) {
                    applyRow(op.operation(), walletId, op.transactionId(), op.amount());
                }

                return null;
            });
        }

        runConcurrently(tasks, CONCURRENCY);

        assertThat(expected).containsOnlyKeys(byWallet.keySet());
        for (var balance : expected.entrySet()) {
            var wallet = walletService.getWalletById(balance.getKey());

            assertThat(wallet.getAmount()).isEqualTo(balance.getValue());
        }

        assertThat(walletService.getWalletById(walletIds.get("w0")).getAmount()).isEqualTo(1300L);
        assertThat(walletService.getWalletById(walletIds.get("w1")).getAmount()).isEqualTo(1300L);
        assertThat(walletService.getWalletById(walletIds.get("w2")).getAmount()).isEqualTo(300L);
    }

    @Tag("slow")
    @Test
    void concurrentStress() throws Exception {
        var opsByWalletKey = stressOps();

        var walletIds = new HashMap<String, UUID>();
        var txIds = new HashMap<String, UUID>();
        for (var entry : opsByWalletKey.entrySet()) {
            walletIds.computeIfAbsent(entry.getKey(), k -> UUID.randomUUID());
            for (var op : entry.getValue()) {
                txIds.computeIfAbsent(op.txKey(), k -> UUID.randomUUID());
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
                        .map(op -> new WalletMovement(op.operation(), txIds.get(op.txKey()), op.amount()))
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

        for (var i = 0; i < 100 && !outboxRepository.findAllById(ownTxIds).isEmpty(); i++) {
            publisher.publish();
        }
        assertThat(outboxRepository.findAllById(ownTxIds)).isEmpty();

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
            var applied = ledgerByWallet.values().stream().anyMatch(byTx -> byTx.containsKey(txId));
            if (!applied) {
                assertThat(eventsByTx.get(txId)).isNull();
            }
        }
    }

    private static Map<String, List<StressOp>> stressOps() {
        var rng = new Random(STRESS_SEED);

        var opsByWalletKey = new HashMap<String, List<StressOp>>();
        for (var w = 0; w < STRESS_WALLETS; w++) {
            var ops = new ArrayList<StressOp>(OPS_PER_WALLET);
            // first row: can never succeed, so the rejection path runs regardless of interleaving
            ops.add(new StressOp("DEBIT", "s" + w + "-0", STRESS_GUARANTEED_DEBIT));
            for (var i = 1; i < OPS_PER_WALLET; i++) {
                if (rng.nextDouble() < STRESS_DUPLICATE_RATE) {
                    ops.add(ops.get(rng.nextInt(ops.size())));
                } else {
                    var operation = rng.nextBoolean() ? "CREDIT" : "DEBIT";
                    ops.add(new StressOp(operation, "s" + w + "-" + i, 1 + rng.nextInt(1000)));
                }
            }
            opsByWalletKey.put("s" + w, ops);
        }
        return opsByWalletKey;
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
            try (var pool = Executors.newFixedThreadPool(2)) {
                var tasks = new ArrayList<Callable<WalletTransaction>>(2);
                for (var t = 0; t < 2; t++) {
                    tasks.add(() -> {
                        start.await();
                        return walletService.credit(transactionId, walletId, 500L);
                    });
                }

                var futures = pool.invokeAll(tasks);
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
            "0    | 500   | credit | 500  | 500   | null                          | 500",
            "1000 | -1000 | debit  | 1000 | -1000 | null                          | 0  ",
            "1000 | -1000 | debit  | 600  | null  | IllegalTransactionException   | 0  ",
    }, delimiter = '|', nullValues = "null")
    void duplicateRaceBehindCommittedWinnerReturnsExpectedOutcome(
            long funded, long winnerAmount, String callKind, long callAmount,
            Long expectedStored, String exception, long expectedBalance) throws Exception {
        var walletId = UUID.randomUUID();
        var transactionId = UUID.randomUUID();
        walletService.createWallet(walletId);
        if (funded > 0) {
            walletService.credit(UUID.randomUUID(), walletId, funded);
        }

        Callable<WalletTransaction> serviceCall = switch (callKind.strip()) {
            case "credit" -> () -> walletService.credit(transactionId, walletId, callAmount);
            case "debit" -> () -> walletService.debit(transactionId, walletId, callAmount);
            default -> throw new IllegalArgumentException("unknown call: " + callKind);
        };

        var outcome = raceBehindUncommittedWinner(transactionId, walletId, winnerAmount, serviceCall);

        if (exception == null) {
            assertThat(outcome.failure()).isNull();
            assertThat(outcome.value()).isNotNull();
            assertThat(outcome.value().getTransactionId()).isEqualTo(transactionId);
            assertThat(outcome.value().getAmount()).isEqualTo(expectedStored);
        } else {
            assertThat(outcome.value()).isNull();
            assertThat(outcome.failure()).isInstanceOf(IllegalTransactionException.class);
        }
        assertThat(walletService.getWalletById(walletId).getAmount()).isEqualTo(expectedBalance);
    }

    private record RaceOutcome(WalletTransaction value, RuntimeException failure) {
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
                    jdbcTemplate.update("insert into wallet_ledger (transaction_id, wallet_id, amount) values (?, ?, ?)",
                            transactionId, walletId, winnerAmount);
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
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            var futures = pool.invokeAll(tasks);

            var results = new ArrayList<T>(tasks.size());
            for (var future : futures) {
                var result = future.get();
                results.add(result);
            }
            return results;
        }
    }

    private long signedMovement(String operation, long amount) {
        return switch (operation.strip()) {
            case "DEBIT" -> -amount;
            case "CREDIT" -> amount;
            default -> throw new IllegalArgumentException("unknown operation: " + operation);
        };
    }

    private void applyRow(String operation, UUID walletId, UUID transactionId, long amount) {
        switch (operation.strip()) {
            case "DEBIT" -> walletService.debit(transactionId, walletId, amount);
            case "CREDIT" -> walletService.credit(transactionId, walletId, amount);
            default -> throw new IllegalArgumentException("unknown operation: " + operation);
        }
    }

    private boolean tryDebit(UUID transactionId, UUID walletId, long amount) {
        try {
            walletService.debit(transactionId, walletId, amount);
            return true;
        } catch (InsufficientFundsException e) {
            return false;
        }
    }

    private void applyWithLockRetry(WalletMovement movement, UUID walletId, AtomicInteger insufficient) {
        for (var attempt = 0; ; attempt++) {
            try {
                applyRow(movement.operation(), walletId, movement.transactionId(), movement.amount());
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

    private record WalletOp(String operation, String wallet, String transaction, long amount) {
    }

    private record StressOp(String operation, String txKey, long amount) {
    }

    private record WalletMovement(String operation, UUID transactionId, long amount) {
    }
}
