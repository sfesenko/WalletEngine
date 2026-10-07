package com.github.walletengine.wallet;

import com.github.walletengine.grpc.wallet.v1.CreateWalletRequest;
import com.github.walletengine.grpc.wallet.v1.GetBalanceRequest;
import com.github.walletengine.grpc.wallet.v1.TransactionRequest;
import com.github.walletengine.grpc.wallet.v1.TransactionResponse;
import com.github.walletengine.grpc.wallet.v1.WalletResponse;
import com.github.walletengine.grpc.wallet.v1.WalletServiceGrpc;
import com.google.protobuf.Timestamp;
import io.grpc.stub.StreamObserver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.grpc.server.service.GrpcService;

import java.time.Instant;
import java.util.UUID;

/**
 * gRPC adapter: parses input, delegates to {@link WalletService}, builds the response.
 * Failures are mapped to status codes by {@link WalletGrpcServiceExceptionAdvice}.
 */
@GrpcService
public class WalletGrpcService extends WalletServiceGrpc.WalletServiceImplBase {

    private final WalletService walletService;

    @Autowired
    public WalletGrpcService(WalletService walletService) {
        this.walletService = walletService;
    }

    @Override
    public void debit(TransactionRequest request, StreamObserver<TransactionResponse> observer) {
        var transactionIdValue = request.getTransactionId();
        var walletIdValue = request.getWalletId();
        var amount = request.getAmount();

        var transactionId = parseUuid(transactionIdValue, "transaction_id");
        var walletId = parseUuid(walletIdValue, "wallet_id");

        var entry = walletService.debit(transactionId, walletId, amount);
        var response = toTransactionResponse(entry);

        observer.onNext(response);
        observer.onCompleted();
    }

    @Override
    public void credit(TransactionRequest request, StreamObserver<TransactionResponse> observer) {
        var transactionIdValue = request.getTransactionId();
        var walletIdValue = request.getWalletId();
        var amount = request.getAmount();

        var transactionId = parseUuid(transactionIdValue, "transaction_id");
        var walletId = parseUuid(walletIdValue, "wallet_id");

        var entry = walletService.credit(transactionId, walletId, amount);
        var response = toTransactionResponse(entry);

        observer.onNext(response);
        observer.onCompleted();
    }

    @Override
    public void getBalance(GetBalanceRequest request, StreamObserver<WalletResponse> observer) {
        var walletIdValue = request.getWalletId();

        var walletId = parseUuid(walletIdValue, "wallet_id");

        var wallet = walletService.getWallet(walletId);
        var response = toWalletResponse(wallet);

        observer.onNext(response);
        observer.onCompleted();
    }

    @Override
    public void createWallet(CreateWalletRequest request, StreamObserver<WalletResponse> observer) {
        var walletIdValue = request.getWalletId();

        var walletId = parseUuid(walletIdValue, "wallet_id");

        var wallet = walletService.createWallet(walletId);
        var response = toWalletResponse(wallet);

        observer.onNext(response);
        observer.onCompleted();
    }

    private static UUID parseUuid(String value, String field) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            var message = field + " is not a valid UUID: " + value;

            throw new IllegalArgumentException(message, e);
        }
    }

    private static TransactionResponse toTransactionResponse(WalletLedgerEntry entry) {
        return TransactionResponse.newBuilder()
                .setTransactionId(entry.getTransactionId().toString())
                .setWalletId(entry.getWalletId().toString())
                .setAmount(entry.getAmount())
                .setUpdatedAt(toTimestamp(entry.getUpdatedAt()))
                .build();
    }

    private static WalletResponse toWalletResponse(Wallet wallet) {
        return WalletResponse.newBuilder()
                .setWalletId(wallet.getWalletId().toString())
                .setAmount(wallet.getAmount())
                .setUpdatedAt(toTimestamp(wallet.getUpdatedAt()))
                .build();
    }

    private static Timestamp toTimestamp(Instant instant) {
        return Timestamp.newBuilder()
                .setSeconds(instant.getEpochSecond())
                .setNanos(instant.getNano())
                .build();
    }
}
