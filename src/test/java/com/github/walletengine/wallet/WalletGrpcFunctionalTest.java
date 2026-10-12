package com.github.walletengine.wallet;

import com.github.walletengine.grpc.wallet.v1.CreateWalletRequest;
import com.github.walletengine.grpc.wallet.v1.GetBalanceRequest;
import com.github.walletengine.grpc.wallet.v1.TransactionRequest;
import com.github.walletengine.grpc.wallet.v1.WalletServiceGrpc;
import com.github.walletengine.lock.LockManager;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.grpc.test.autoconfigure.AutoConfigureTestGrpcTransport;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.grpc.client.GrpcChannelFactory;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * End-to-end checks of the gRPC layer.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureTestGrpcTransport
class WalletGrpcFunctionalTest {

    private static final UUID WALLET_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID TRANSACTION_ID = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final long AMOUNT = 100L;

    @Autowired
    private GrpcChannelFactory channelFactory;

    @Autowired
    private WalletService walletService;

    @Autowired
    private LockManager lockManager;

    private WalletServiceGrpc.WalletServiceBlockingStub stub;

    @BeforeEach
    void setUp() {
        var channel = channelFactory.createChannel("wallet-engine-test");
        this.stub = WalletServiceGrpc.newBlockingStub(channel);
    }

    @ParameterizedTest
    @CsvSource(value = {
            "debit        | not-a-uuid | 00000000-0000-0000-0000-000000000001 | 100  ",
            "debit        | EMPTY      | 00000000-0000-0000-0000-000000000001 | 100  ",
            "debit        | aaaaaaaa-0000-0000-0000-000000000001 | definitely-not-a-uuid | 100 ",
            "getBalance   | - | 12345 | 0 ",
            "createWallet | - | wallet-1 | 0 ",
            "debit        | aaaaaaaa-0000-0000-0000-000000000001 | 00000000-0000-0000-0000-000000000001 | 0 ",
            "debit        | aaaaaaaa-0000-0000-0000-000000000001 | 00000000-0000-0000-0000-000000000001 | -100 ",
            "credit       | aaaaaaaa-0000-0000-0000-000000000001 | 00000000-0000-0000-0000-000000000001 | 0 ",
            "credit       | aaaaaaaa-0000-0000-0000-000000000001 | 00000000-0000-0000-0000-000000000001 | -100 ",
    }, delimiter = '|')
    void requestValidationFails(String rpc, String transactionId, String walletId, long amount) {
        var tx = transactionId.strip().equals("EMPTY") ? "" : transactionId.strip();
        var rpcName = rpc.strip();

        StatusRuntimeException failure = assertThrows(StatusRuntimeException.class, () -> {
            switch (rpcName) {
                case "debit" -> stub.debit(TransactionRequest.newBuilder()
                        .setTransactionId(tx)
                        .setWalletId(walletId.strip())
                        .setAmount(amount)
                        .build());
                case "credit" -> stub.credit(TransactionRequest.newBuilder()
                        .setTransactionId(tx)
                        .setWalletId(walletId.strip())
                        .setAmount(amount)
                        .build());
                case "getBalance" -> stub.getBalance(GetBalanceRequest.newBuilder()
                        .setWalletId(walletId.strip())
                        .build());
                case "createWallet" -> stub.createWallet(CreateWalletRequest.newBuilder()
                        .setWalletId(walletId.strip())
                        .build());
                default -> throw new IllegalArgumentException("unknown rpc: " + rpc);
            }
        });

        assertThat(failure.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
    }

    @ParameterizedTest
    @CsvSource(value = {
            "duplicateCreate | createWallet | ALREADY_EXISTS      ",
            "missing         | debit        | NOT_FOUND           ",
            "missing         | getBalance   | NOT_FOUND           ",
            "empty           | debit        | FAILED_PRECONDITION ",
            "conflict        | credit       | INVALID_ARGUMENT    ",
            "locked          | debit        | ABORTED             ",
    }, delimiter = '|')
    void serviceErrorMapping(String setup, String rpc, String expectedCode) {
        var walletId = UUID.randomUUID();
        var transactionId = UUID.randomUUID();
        var status = Status.Code.valueOf(expectedCode.strip());
        var rpcName = rpc.strip();

        switch (setup.strip()) {
            case "duplicateCreate" -> walletService.createWallet(walletId);
            case "missing" -> {
            }
            case "empty" -> walletService.createWallet(walletId);
            case "conflict" -> {
                walletService.createWallet(walletId);
                walletService.credit(transactionId, walletId, AMOUNT);
            }
            case "locked" -> {
                walletService.createWallet(walletId);
                walletService.credit(UUID.randomUUID(), walletId, 10_000L);
            }
            default -> throw new IllegalArgumentException("unknown setup: " + setup);
        }

        if (setup.strip().equals("locked")) {
            try (var ignored = lockManager.acquire("wallet:" + walletId, Duration.ofSeconds(10))) {
                StatusRuntimeException failure = assertThrows(StatusRuntimeException.class,
                        () -> stub.debit(TransactionRequest.newBuilder()
                                .setTransactionId(transactionId.toString())
                                .setWalletId(walletId.toString())
                                .setAmount(AMOUNT)
                                .build()));

                assertThat(failure.getStatus().getCode()).isEqualTo(status);
            }
            return;
        }

        StatusRuntimeException failure = assertThrows(StatusRuntimeException.class, () -> {
            switch (rpcName) {
                case "createWallet" -> stub.createWallet(CreateWalletRequest.newBuilder()
                        .setWalletId(walletId.toString())
                        .build());
                case "debit" -> stub.debit(TransactionRequest.newBuilder()
                        .setTransactionId(transactionId.toString())
                        .setWalletId(walletId.toString())
                        .setAmount(AMOUNT)
                        .build());
                case "credit" -> {
                    var secondAmount = setup.strip().equals("conflict") ? AMOUNT + 1 : AMOUNT;
                    stub.credit(TransactionRequest.newBuilder()
                            .setTransactionId(transactionId.toString())
                            .setWalletId(walletId.toString())
                            .setAmount(secondAmount)
                            .build());
                }
                case "getBalance" -> stub.getBalance(GetBalanceRequest.newBuilder()
                        .setWalletId(walletId.toString())
                        .build());
                default -> throw new IllegalArgumentException("unknown rpc: " + rpc);
            }
        });

        assertThat(failure.getStatus().getCode()).isEqualTo(status);
    }

    @Test
    void happyPathRoundTrip() {
        var walletId = UUID.randomUUID();
        var transactionId = UUID.randomUUID();

        var created = stub.createWallet(CreateWalletRequest.newBuilder()
                .setWalletId(walletId.toString())
                .build());

        assertThat(created.getWalletId()).isEqualTo(walletId.toString());
        assertThat(created.getAmount()).isZero();

        var credited = stub.credit(TransactionRequest.newBuilder()
                .setTransactionId(transactionId.toString())
                .setWalletId(walletId.toString())
                .setAmount(AMOUNT)
                .build());

        assertThat(credited.getTransactionId()).isEqualTo(transactionId.toString());

        var balance = stub.getBalance(GetBalanceRequest.newBuilder()
                .setWalletId(walletId.toString())
                .build());

        assertThat(balance.getAmount()).isEqualTo(AMOUNT);
    }

    @Test
    void redisConnectionFailuresAreMappedToUnavailable() {
        var advice = new WalletGrpcServiceExceptionAdvice();
        var status = advice.onResourceFailure(new RedisConnectionFailureException("redis is down"));

        assertThat(status.getCode()).isEqualTo(Status.Code.UNAVAILABLE);
        assertThat(status.getDescription()).isNotBlank();
    }
}
