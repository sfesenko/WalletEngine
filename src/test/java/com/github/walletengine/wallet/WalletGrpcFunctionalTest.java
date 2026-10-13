package com.github.walletengine.wallet;

import com.github.walletengine.grpc.wallet.v1.*;
import com.github.walletengine.lock.LockManager;
import com.google.protobuf.InvalidProtocolBufferException;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
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

    private static final long AMOUNT = 100L;

    @Autowired
    private GrpcChannelFactory channelFactory;

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
            "not-a-uuid                           | 00000000-0000-0000-0000-000000000001 | 100",
            "''                                   | 00000000-0000-0000-0000-000000000001 | 100",
            "aaaaaaaa-0000-0000-0000-000000000001 | definitely-not-a-uuid                | 100",
            "aaaaaaaa-0000-0000-0000-000000000001 | 00000000-0000-0000-0000-000000000001 | 0",
            "aaaaaaaa-0000-0000-0000-000000000001 | 00000000-0000-0000-0000-000000000001 | -100",
    }, delimiter = '|')
    void transactionRequestValidationFails(String transactionId, String walletId, long amount) {
        var request = TransactionRequest.newBuilder()
                .setTransactionId(transactionId)
                .setWalletId(walletId)
                .setAmount(amount)
                .build();

        assertStatus(Status.Code.INVALID_ARGUMENT, () -> stub.debit(request));
        assertStatus(Status.Code.INVALID_ARGUMENT, () -> stub.credit(request));
    }

    @Test
    void getBalanceRejectsMalformedWalletId() {
        assertStatus(Status.Code.INVALID_ARGUMENT, () -> stub.getBalance(GetBalanceRequest.newBuilder()
                .setWalletId("12345")
                .build()));
    }

    @Test
    void createWalletRejectsMalformedWalletId() {
        assertStatus(Status.Code.INVALID_ARGUMENT, () -> stub.createWallet(CreateWalletRequest.newBuilder()
                .setWalletId("wallet-1")
                .build()));
    }

    @Test
    void duplicateCreateWalletIsRejected() {
        var walletId = UUID.randomUUID();
        stub.createWallet(createWalletRequest(walletId));

        assertStatus(Status.Code.ALREADY_EXISTS, () -> stub.createWallet(createWalletRequest(walletId)));
    }

    @Test
    void debitUnknownWalletIsRejected() {
        var walletId = UUID.randomUUID();

        assertStatus(Status.Code.NOT_FOUND, () -> stub.debit(transactionRequest(UUID.randomUUID(), walletId, AMOUNT)));
    }

    @Test
    void getBalanceUnknownWalletIsRejected() {
        var walletId = UUID.randomUUID();

        assertStatus(Status.Code.NOT_FOUND, () -> stub.getBalance(balanceRequest(walletId)));
    }

    @Test
    void debitOnEmptyWalletIsRejected() {
        var walletId = UUID.randomUUID();
        stub.createWallet(createWalletRequest(walletId));

        assertStatus(Status.Code.FAILED_PRECONDITION,
                () -> stub.debit(transactionRequest(UUID.randomUUID(), walletId, AMOUNT)));
    }

    @Test
    void creditConflictIsRejected() {
        var walletId = UUID.randomUUID();
        var transactionId = UUID.randomUUID();
        stub.createWallet(createWalletRequest(walletId));
        stub.credit(transactionRequest(transactionId, walletId, AMOUNT));

        assertStatus(Status.Code.INVALID_ARGUMENT,
                () -> stub.credit(transactionRequest(transactionId, walletId, AMOUNT + 1)));
    }

    @Test
    void debitOnLockedWalletIsRejected() {
        var walletId = UUID.randomUUID();
        stub.createWallet(createWalletRequest(walletId));
        stub.credit(transactionRequest(UUID.randomUUID(), walletId, 10_000L));

        try (var ignored = lockManager.acquire("wallet:" + walletId, Duration.ofSeconds(10))) {
            assertStatus(Status.Code.ABORTED, () -> stub.debit(transactionRequest(UUID.randomUUID(), walletId, AMOUNT)));
        }
    }

    @ParameterizedTest
    @CsvSource(value = {
            "false | 100",
            "true  | 200",
    }, delimiter = '|')
    void conflictingTransactionReplayDoesNotLeakStoredDetails(boolean replayAgainstStoredWallet, long replayAmount) {
        var walletId = UUID.randomUUID();
        var otherWalletId = UUID.randomUUID();
        var transactionId = UUID.randomUUID();

        stub.createWallet(createWalletRequest(walletId));
        stub.createWallet(createWalletRequest(otherWalletId));
        stub.credit(transactionRequest(transactionId, walletId, AMOUNT));

        var replayWalletId = replayAgainstStoredWallet
                ? walletId
                : otherWalletId;

        var failure = assertThrows(StatusRuntimeException.class,
                () -> stub.credit(transactionRequest(transactionId, replayWalletId, replayAmount)));

        assertThat(failure.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
        // only the request's transaction_id may be exposed: the stored wallet / amount must not leak
        assertThat(failure.getStatus().getDescription())
                .contains(transactionId.toString())
                .doesNotContain(walletId.toString());
    }

    @Test
    void happyPathRoundTrip() throws InvalidProtocolBufferException {
        var walletId = UUID.randomUUID();
        var creditId = UUID.randomUUID();
        var debitId = UUID.randomUUID();

        var created = stub.createWallet(createWalletRequest(walletId));

        assertThat(created.getWalletId()).isEqualTo(walletId.toString());

        var credited = stub.credit(transactionRequest(creditId, walletId, AMOUNT));

        assertThat(credited.getTransactionId()).isEqualTo(creditId.toString());
        assertThat(credited.getAmount()).isEqualTo(AMOUNT);
        assertThat(credited.getTransactionAmount()).isEqualTo(AMOUNT);

        var debited = stub.debit(transactionRequest(debitId, walletId, AMOUNT));

        assertThat(debited.getTransactionId()).isEqualTo(debitId.toString());
        assertThat(debited.getTransactionAmount()).isEqualTo(-AMOUNT);
        assertExplicitZero(debited);

        var balance = stub.getBalance(balanceRequest(walletId));

        assertThat(balance.getAmount()).isZero();
    }

    @Test
    void replayedTransactionReportsOriginalMovementAndCurrentBalance() {
        var walletId = UUID.randomUUID();
        var replayedTransactionId = UUID.randomUUID();

        stub.createWallet(createWalletRequest(walletId));
        stub.credit(transactionRequest(replayedTransactionId, walletId, AMOUNT));
        stub.credit(transactionRequest(UUID.randomUUID(), walletId, 50L));

        var replayed = stub.credit(transactionRequest(replayedTransactionId, walletId, AMOUNT));

        assertThat(replayed.getTransactionAmount()).isEqualTo(AMOUNT);
        assertThat(replayed.getAmount()).isEqualTo(AMOUNT + 50L);
    }

    @Test
    void zeroBalanceIsExplicitlyPresent() throws InvalidProtocolBufferException {
        var walletId = UUID.randomUUID();

        assertExplicitZero(stub.createWallet(createWalletRequest(walletId)));
        assertExplicitZero(stub.getBalance(balanceRequest(walletId)));
    }

    @Test
    void redisConnectionFailuresAreMappedToUnavailable() {
        var advice = new WalletGrpcServiceExceptionAdvice();
        var status = advice.onResourceFailure(new RedisConnectionFailureException("redis is down"));

        assertThat(status.getCode()).isEqualTo(Status.Code.UNAVAILABLE);
        assertThat(status.getDescription()).isNotBlank();
    }

    private static TransactionRequest transactionRequest(UUID transactionId, UUID walletId, long amount) {
        return TransactionRequest.newBuilder()
                .setTransactionId(transactionId.toString())
                .setWalletId(walletId.toString())
                .setAmount(amount)
                .build();
    }

    private static CreateWalletRequest createWalletRequest(UUID walletId) {
        return CreateWalletRequest.newBuilder()
                .setWalletId(walletId.toString())
                .build();
    }

    private static GetBalanceRequest balanceRequest(UUID walletId) {
        return GetBalanceRequest.newBuilder()
                .setWalletId(walletId.toString())
                .build();
    }

    private static void assertStatus(Status.Code expected, Executable call) {
        var failure = assertThrows(StatusRuntimeException.class, call);
        assertThat(failure.getStatus().getCode()).isEqualTo(expected);
    }

    private static void assertExplicitZero(WalletResponse response) throws InvalidProtocolBufferException {
        assertThat(response.getAmount()).isZero();
        assertThat(response.hasAmount()).isTrue();
        // the explicit zero must survive serialization: proto3 omits default scalars otherwise
        assertThat(WalletResponse.parseFrom(response.toByteArray()).hasAmount()).isTrue();
    }

    private static void assertExplicitZero(TransactionResponse response) throws InvalidProtocolBufferException {
        assertThat(response.getAmount()).isZero();
        assertThat(response.hasAmount()).isTrue();
        // the explicit zero must survive serialization: proto3 omits default scalars otherwise
        assertThat(TransactionResponse.parseFrom(response.toByteArray()).hasAmount()).isTrue();
    }
}
