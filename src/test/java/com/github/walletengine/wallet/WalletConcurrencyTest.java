package com.github.walletengine.wallet;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concurrent wallet movements against the real database (Docker Compose
 * infrastructure, {@code test} profile). {@link #runConcurrently} is the shared
 * runner for all concurrency tests in this class.
 */
@SpringBootTest
@ActiveProfiles("test")
class WalletConcurrencyTest {

    private static final int CONCURRENCY = 20;

    @Autowired
    private WalletService walletService;

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
        var wallet = walletService.getWallet(walletId);

        assertThat(wallet.getAmount()).isZero();
        assertThat(moved).isEqualTo(1_000L);
    }

    @Test
    void csvScenarioAppliesEveryMovementExactlyOnce() throws Exception {
        var rows = loadScenario("/scenario/movements.csv");

        var walletIds = new HashMap<String, UUID>();
        var txIds = new HashMap<String, UUID>();

        for (var row : rows) {
            if (row.operation().equals("CREATE")) {
                var walletId = resolveWallet(walletIds, row.wallet());
                walletService.createWallet(walletId);
            }
        }

        // One task per wallet, rows in file order: the wallet's movements stay
        // deterministic while wallets run concurrently across the pool.
        var expected = new HashMap<UUID, Long>();
        var seenTx = new HashSet<UUID>();
        var byWallet = new HashMap<UUID, List<WalletOp>>();
        for (var row : rows) {
            if (row.operation().equals("CREATE")) {
                continue;
            }
            var walletId = resolveWallet(walletIds, row.wallet());
            var transactionId = resolveTx(txIds, row.transaction());
            var operation = row.operation();
            var amount = row.amount();

            var ops = byWallet.computeIfAbsent(walletId, k -> new ArrayList<>());
            ops.add(new WalletOp(operation, transactionId, amount));

            var firstSeen = seenTx.add(transactionId);
            if (firstSeen) {
                var movement = signedMovement(operation, amount);
                var balance = expected.getOrDefault(walletId, 0L);
                expected.put(walletId, balance + movement);
            }
        }

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

        for (var balance : expected.entrySet()) {
            var walletId = balance.getKey();
            var expectedAmount = balance.getValue();
            var wallet = walletService.getWallet(walletId);

            assertThat(wallet.getAmount()).isEqualTo(expectedAmount);
        }
    }

    /**
     * Runs every task on a shared pool and returns the results in task order.
     * Failures surface through {@link java.util.concurrent.Future#get}, so a task
     * exception fails the test.
     */
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

    private List<ScenarioRow> loadScenario(String resource) throws Exception {
        var stream = WalletConcurrencyTest.class.getResourceAsStream(resource);
        var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
        try (reader) {
            var rows = new ArrayList<ScenarioRow>();
            var line = reader.readLine();
            while (line != null) {
                var content = line.trim();
                var comment = content.startsWith("#");
                var header = content.startsWith("operation|");
                if (!comment && !header) {
                    var row = parseRow(content);
                    rows.add(row);
                }
                line = reader.readLine();
            }

            return rows;
        }
    }

    private ScenarioRow parseRow(String line) {
        var parts = line.split("\\|", -1);
        var operation = parts[0].trim();
        var wallet = parts[1].trim();
        var transaction = parts[2].trim();
        var amountText = parts[3].trim();

        var amount = 0L;
        if (!amountText.isEmpty()) {
            amount = Long.parseLong(amountText);
        }

        return new ScenarioRow(operation, wallet, transaction, amount);
    }

    private UUID resolveWallet(Map<String, UUID> walletIds, String key) {
        return walletIds.computeIfAbsent(key, k -> UUID.randomUUID());
    }

    private UUID resolveTx(Map<String, UUID> txIds, String key) {
        return txIds.computeIfAbsent(key, k -> UUID.randomUUID());
    }

    private long signedMovement(String operation, long amount) {
        return switch (operation) {
            case "DEBIT" -> -amount;
            case "CREDIT" -> amount;
            default -> throw new IllegalArgumentException("unknown operation: " + operation);
        };
    }

    private void applyRow(String operation, UUID walletId, UUID transactionId, long amount) {
        switch (operation) {
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

    private record ScenarioRow(String operation, String wallet, String transaction, long amount) {
    }

    private record WalletOp(String operation, UUID transactionId, long amount) {
    }
}
