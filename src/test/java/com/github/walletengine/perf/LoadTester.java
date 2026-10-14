package com.github.walletengine.perf;

import com.github.walletengine.grpc.wallet.v1.CreateWalletRequest;
import com.github.walletengine.grpc.wallet.v1.GetBalanceRequest;
import com.github.walletengine.grpc.wallet.v1.TransactionRequest;
import com.github.walletengine.grpc.wallet.v1.WalletServiceGrpc;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * gRPC load driver for WalletEngine.
 *
 * <p>One virtual thread per wallet issues that wallet's transactions sequentially: the service
 * serializes per-wallet operations anyway, so throughput comes from many wallets in flight.
 * A single reused channel keeps process startup and connection setup out of the measurement.
 *
 * <p>Every run uses fresh random wallets and transaction ids, then verifies the final wallet
 * balances against the expected net of the applied movements.
 */
public final class LoadTester {

    private static final int CALL_DEADLINE_SECONDS = 10;

    private static final String MODE_MIXED = "mixed";
    private static final String MODE_CREDIT = "credit";
    private static final String MODE_DEBIT = "debit";
    private static final String MODE_READ = "read";

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && ("--help".equals(args[0]) || "-h".equals(args[0]))) {
            System.out.print(usage());
            return;
        }
        var config = parseConfig(args);
        var channel = ManagedChannelBuilder.forTarget(config.target()).usePlaintext().build();
        try {
            runLoad(config, WalletServiceGrpc.newBlockingStub(channel));
        } finally {
            channel.shutdownNow();
            channel.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private static void runLoad(Config config, WalletServiceGrpc.WalletServiceBlockingStub stub)
            throws InterruptedException, ExecutionException {
        var walletIds = new ArrayList<UUID>(config.wallets());
        for (var i = 0; i < config.wallets(); i++) {
            walletIds.add(UUID.randomUUID());
        }

        var funding = fundingPerWallet(config);

        System.out.printf("WalletEngine load test%n  target=%s mode=%s wallets=%d ops/wallet=%d amount=%d%n",
                config.target(), config.mode(), config.wallets(), config.ops(), config.amount());

        var setupStart = System.nanoTime();
        for (var walletId : walletIds) {
            deadline(stub).createWallet(CreateWalletRequest.newBuilder().setWalletId(walletId.toString()).build());
            if (funding > 0) {
                deadline(stub).credit(transaction(walletId, funding));
            }
        }
        System.out.printf("setup: %d wallets created%s in %.2fs%n",
                config.wallets(), funding > 0 ? " and funded" : "", secondsSince(setupStart));

        var samples = new long[config.wallets()][config.ops()];
        var errorCounts = new ConcurrentHashMap<String, AtomicLong>();

        var loadStart = System.nanoTime();
        var nets = new long[config.wallets()];
        var recorded = new int[config.wallets()];
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<Future<TaskResult>>(config.wallets());
            for (var i = 0; i < config.wallets(); i++) {
                final var index = i;
                futures.add(executor.submit(() ->
                        runWallet(config, stub, walletIds.get(index), samples[index], errorCounts)));
            }
            for (var i = 0; i < config.wallets(); i++) {
                var result = futures.get(i).get();
                nets[i] = result.net();
                recorded[i] = result.recorded();
            }
        }
        var loadSeconds = secondsSince(loadStart);

        var executed = Arrays.stream(recorded).sum();
        var totalOps = (long) config.wallets() * config.ops();
        System.out.printf("load: %d ops requested, %d completed in %.2fs -> %.0f ops/s%n",
                totalOps, executed, loadSeconds, executed / loadSeconds);

        var all = new long[executed];
        var position = 0;
        for (var i = 0; i < config.wallets(); i++) {
            System.arraycopy(samples[i], 0, all, position, recorded[i]);
            position += recorded[i];
        }
        Arrays.sort(all);
        if (executed > 0) {
            System.out.printf("latency ms: p50=%.1f p90=%.1f p99=%.1f p99.9=%.1f max=%.1f%n",
                    percentile(all, 0.50) / 1e6, percentile(all, 0.90) / 1e6,
                    percentile(all, 0.99) / 1e6, percentile(all, 0.999) / 1e6, all[executed - 1] / 1e6);
        }

        var errors = errorCounts.values().stream().mapToLong(AtomicLong::get).sum();
        System.out.println(errors == 0 ? "errors: none" : "errors: " + new TreeMap<>(errorCounts));

        var mismatches = verifyBalances(stub, walletIds, funding, nets);
        System.out.printf("correctness: %d/%d wallets match expected balances%n",
                config.wallets() - mismatches, config.wallets());
        if (errors > 0 || mismatches > 0) {
            throw new IllegalStateException(mismatches + " balance mismatches, " + errors + " rpc errors");
        }
    }

    private static TaskResult runWallet(
            Config config,
            WalletServiceGrpc.WalletServiceBlockingStub stub,
            UUID walletId,
            long[] samples,
            ConcurrentHashMap<String, AtomicLong> errorCounts) {
        var net = 0L;
        var recorded = 0;
        for (var i = 0; i < config.ops(); i++) {
            var start = System.nanoTime();
            try {
                if (MODE_READ.equals(config.mode())) {
                    deadline(stub).getBalance(GetBalanceRequest.newBuilder().setWalletId(walletId.toString()).build());
                } else if (isCredit(config.mode(), i)) {
                    deadline(stub).credit(transaction(walletId, config.amount()));
                    net += config.amount();
                } else {
                    deadline(stub).debit(transaction(walletId, config.amount()));
                    net -= config.amount();
                }
                samples[recorded++] = System.nanoTime() - start;
            } catch (StatusRuntimeException e) {
                errorCounts.computeIfAbsent(e.getStatus().getCode().name(), code -> new AtomicLong())
                        .incrementAndGet();
            }
        }
        return new TaskResult(net, recorded);
    }

    private static int verifyBalances(
            WalletServiceGrpc.WalletServiceBlockingStub stub,
            List<UUID> walletIds,
            long funding,
            long[] nets) {
        var mismatches = 0;
        for (var i = 0; i < walletIds.size(); i++) {
            var actual = deadline(stub).getBalance(GetBalanceRequest.newBuilder()
                    .setWalletId(walletIds.get(i).toString())
                    .build()).getAmount();
            var expected = funding + nets[i];
            if (actual != expected) {
                if (mismatches < 5) {
                    System.out.printf("  mismatch: wallet=%s expected=%d actual=%d%n",
                            walletIds.get(i), expected, actual);
                }
                mismatches++;
            }
        }
        return mismatches;
    }

    private static boolean isCredit(String mode, int opIndex) {
        return switch (mode) {
            case MODE_CREDIT -> true;
            case MODE_DEBIT -> false;
            default -> opIndex % 2 == 0; // mixed: credit first so the following debit is covered
        };
    }

    /**
     * Per-call deadline. Calling {@code withDeadlineAfter} once on a shared stub freezes a single
     * absolute deadline for every later call; stubs must be wrapped per call instead.
     */
    private static WalletServiceGrpc.WalletServiceBlockingStub deadline(
            WalletServiceGrpc.WalletServiceBlockingStub stub) {
        return stub.withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS);
    }

    private static TransactionRequest transaction(UUID walletId, long amount) {
        return TransactionRequest.newBuilder()
                .setTransactionId(UUID.randomUUID().toString())
                .setWalletId(walletId.toString())
                .setAmount(amount)
                .build();
    }

    private static long fundingPerWallet(Config config) {
        return switch (config.mode()) {
            case MODE_CREDIT -> 0;
            case MODE_DEBIT -> Math.multiplyExact(config.amount(), (long) config.ops());
            default -> config.amount();
        };
    }

    private static long percentile(long[] sorted, double quantile) {
        return sorted[(int) Math.round(quantile * (sorted.length - 1))];
    }

    private static double secondsSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1e9;
    }

    private static Config parseConfig(String[] args) {
        var target = "localhost:9090";
        var wallets = 50;
        var ops = 200;
        var mode = MODE_MIXED;
        var amount = 100L;
        for (var i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--target" -> target = args[++i];
                case "--wallets" -> wallets = Integer.parseInt(args[++i]);
                case "--ops" -> ops = Integer.parseInt(args[++i]);
                case "--mode" -> mode = args[++i];
                case "--amount" -> amount = Long.parseLong(args[++i]);
                case "--help", "-h" -> throw new IllegalArgumentException(usage());
                default -> throw new IllegalArgumentException("unknown argument: " + args[i]
                        + System.lineSeparator() + usage());
            }
        }
        if (wallets < 1 || ops < 1 || amount < 1) {
            throw new IllegalArgumentException("wallets, ops and amount must be positive"
                    + System.lineSeparator() + usage());
        }
        if (!List.of(MODE_MIXED, MODE_CREDIT, MODE_DEBIT, MODE_READ).contains(mode)) {
            throw new IllegalArgumentException("unknown mode: " + mode + System.lineSeparator() + usage());
        }
        return new Config(target, wallets, ops, mode, amount);
    }

    private static String usage() {
        return """
                Usage: docs/load-test.sh [options]
                  --target host:port   gRPC target (default localhost:9090)
                  --wallets N          wallets, one virtual thread each (default 50)
                  --ops N              transactions per wallet (default 200)
                  --mode M             mixed | credit | debit | read (default mixed)
                  --amount N           transaction amount (default 100)

                mixed alternates credit/debit per wallet; debit funds the wallet first;
                read only calls GetBalance. Every run uses fresh random wallets and
                transaction ids, then verifies final balances against the expected net.
                """;
    }

    private record Config(String target, int wallets, int ops, String mode, long amount) {
    }

    private record TaskResult(long net, int recorded) {
    }
}
