package com.github.walletengine.wallet;

import com.github.walletengine.grpc.wallet.v1.CreateWalletRequest;
import com.github.walletengine.grpc.wallet.v1.GetBalanceRequest;
import com.github.walletengine.grpc.wallet.v1.TransactionRequest;
import com.github.walletengine.grpc.wallet.v1.WalletServiceGrpc;
import com.github.walletengine.lock.LockAcquisitionTimeoutException;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.grpc.test.autoconfigure.AutoConfigureTestGrpcTransport;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.grpc.client.GrpcChannelFactory;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * End-to-end checks of the gRPC layer over the in-process transport: request validation
 * and error mapping of {@link WalletGrpcServiceExceptionAdvice}. {@link WalletService} is mocked, so
 * no database is involved in the failure paths.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureTestGrpcTransport
class WalletGrpcErrorRequestTest {

    private static final UUID WALLET_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID TRANSACTION_ID = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final long AMOUNT = 100L;

    @Autowired
    private GrpcChannelFactory channelFactory;

    @MockitoBean
    private WalletService walletService;

    private WalletServiceGrpc.WalletServiceBlockingStub stub;

    @BeforeEach
    void setUp() {
        var channel = channelFactory.createChannel("wallet-engine-test");
        this.stub = WalletServiceGrpc.newBlockingStub(channel);
    }

    @Test
    void debitWithMalformedTransactionIdReturnsInvalidArgument() {
        var request = TransactionRequest.newBuilder()
                .setTransactionId("not-a-uuid")
                .setWalletId(WALLET_ID.toString())
                .setAmount(AMOUNT)
                .build();

        var failure = assertThrows(StatusRuntimeException.class, () -> stub.debit(request));

        assertThat(failure.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
        assertThat(failure.getStatus().getDescription()).contains("transaction_id");
        verifyNoInteractions(walletService);
    }

    @Test
    void debitWithEmptyTransactionIdReturnsInvalidArgument() {
        var request = TransactionRequest.newBuilder()
                .setTransactionId("")
                .setWalletId(WALLET_ID.toString())
                .setAmount(AMOUNT)
                .build();

        var failure = assertThrows(StatusRuntimeException.class, () -> stub.debit(request));

        assertThat(failure.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
        verifyNoInteractions(walletService);
    }

    @Test
    void debitWithMalformedWalletIdReturnsInvalidArgument() {
        var request = TransactionRequest.newBuilder()
                .setTransactionId(TRANSACTION_ID.toString())
                .setWalletId("definitely-not-a-uuid")
                .setAmount(AMOUNT)
                .build();

        var failure = assertThrows(StatusRuntimeException.class, () -> stub.debit(request));

        assertThat(failure.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
        assertThat(failure.getStatus().getDescription()).contains("wallet_id");
        verifyNoInteractions(walletService);
    }

    @Test
    void getBalanceWithMalformedWalletIdReturnsInvalidArgument() {
        var request = GetBalanceRequest.newBuilder()
                .setWalletId("12345")
                .build();

        var failure = assertThrows(StatusRuntimeException.class, () -> stub.getBalance(request));

        assertThat(failure.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
        verifyNoInteractions(walletService);
    }

    @Test
    void createWalletWithMalformedWalletIdReturnsInvalidArgument() {
        var request = CreateWalletRequest.newBuilder()
                .setWalletId("wallet-1")
                .build();

        var failure = assertThrows(StatusRuntimeException.class, () -> stub.createWallet(request));

        assertThat(failure.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
        verifyNoInteractions(walletService);
    }

    @Test
    void createWalletExistingWalletReturnsAlreadyExists() {
        var alreadyExists = new WalletAlreadyExistsException(WALLET_ID);
        given(walletService.createWallet(WALLET_ID)).willThrow(alreadyExists);

        var request = CreateWalletRequest.newBuilder()
                .setWalletId(WALLET_ID.toString())
                .build();

        var failure = assertThrows(StatusRuntimeException.class, () -> stub.createWallet(request));

        assertThat(failure.getStatus().getCode()).isEqualTo(Status.Code.ALREADY_EXISTS);
    }

    @Test
    void debitUnknownWalletReturnsNotFound() {
        var failure = walletNotFoundFailure();

        assertThat(failure.getStatus().getCode()).isEqualTo(Status.Code.NOT_FOUND);
        assertThat(failure.getStatus().getDescription()).contains(WALLET_ID.toString());
    }

    @Test
    void getBalanceUnknownWalletReturnsNotFound() {
        var notFound = new WalletNotFoundException(WALLET_ID);
        given(walletService.getWallet(WALLET_ID)).willThrow(notFound);

        var request = GetBalanceRequest.newBuilder()
                .setWalletId(WALLET_ID.toString())
                .build();

        var failure = assertThrows(StatusRuntimeException.class, () -> stub.getBalance(request));

        assertThat(failure.getStatus().getCode()).isEqualTo(Status.Code.NOT_FOUND);
    }

    @Test
    void creditInsufficientFundsReturnsFailedPrecondition() {
        var insufficientFunds = new InsufficientFundsException(WALLET_ID);
        given(walletService.credit(TRANSACTION_ID, WALLET_ID, AMOUNT)).willThrow(insufficientFunds);

        var request = TransactionRequest.newBuilder()
                .setTransactionId(TRANSACTION_ID.toString())
                .setWalletId(WALLET_ID.toString())
                .setAmount(AMOUNT)
                .build();

        var failure = assertThrows(StatusRuntimeException.class, () -> stub.credit(request));

        assertThat(failure.getStatus().getCode()).isEqualTo(Status.Code.FAILED_PRECONDITION);
    }

    @Test
    void debitLockTimeoutReturnsAborted() {
        var lockTimeout = new LockAcquisitionTimeoutException(WALLET_ID);
        given(walletService.debit(TRANSACTION_ID, WALLET_ID, AMOUNT)).willThrow(lockTimeout);

        var request = TransactionRequest.newBuilder()
                .setTransactionId(TRANSACTION_ID.toString())
                .setWalletId(WALLET_ID.toString())
                .setAmount(AMOUNT)
                .build();

        var failure = assertThrows(StatusRuntimeException.class, () -> stub.debit(request));

        assertThat(failure.getStatus().getCode()).isEqualTo(Status.Code.ABORTED);
    }

    private StatusRuntimeException walletNotFoundFailure() {
        var notFound = new WalletNotFoundException(WALLET_ID);
        given(walletService.debit(TRANSACTION_ID, WALLET_ID, AMOUNT)).willThrow(notFound);

        var request = TransactionRequest.newBuilder()
                .setTransactionId(TRANSACTION_ID.toString())
                .setWalletId(WALLET_ID.toString())
                .setAmount(AMOUNT)
                .build();

        return assertThrows(StatusRuntimeException.class, () -> stub.debit(request));
    }
}
