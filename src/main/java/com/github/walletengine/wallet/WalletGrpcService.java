package com.github.walletengine.wallet;

import com.github.walletengine.grpc.wallet.v1.*;
import com.google.protobuf.Timestamp;
import io.grpc.stub.StreamObserver;
import org.springframework.grpc.server.service.GrpcService;

import java.time.Instant;
import java.util.UUID;

/**
 * Wallet gRPC endpoint.
 * Responsible for encoding / decoding gRPC payload and basic validation.
 **/
@GrpcService
public class WalletGrpcService extends WalletServiceGrpc.WalletServiceImplBase {

    private final WalletService walletService;

    public WalletGrpcService(WalletService walletService) {
        this.walletService = walletService;
    }

    @Override
    public void debit(TransactionRequest request, StreamObserver<TransactionResponse> observer) {
        var transactionId = parseUuid(request.getTransactionId(), "transaction_id");
        var walletId = parseUuid(request.getWalletId(), "wallet_id");
        var amount = requirePositiveAmount(request.getAmount(), "debit");

        var movement = walletService.debit(transactionId, walletId, amount);
        var response = toTransactionResponse(movement);

        observer.onNext(response);
        observer.onCompleted();
    }

    @Override
    public void credit(TransactionRequest request, StreamObserver<TransactionResponse> observer) {
        var transactionId = parseUuid(request.getTransactionId(), "transaction_id");
        var walletId = parseUuid(request.getWalletId(), "wallet_id");
        var amount = requirePositiveAmount(request.getAmount(), "credit");

        var movement = walletService.credit(transactionId, walletId, amount);
        var response = toTransactionResponse(movement);

        observer.onNext(response);
        observer.onCompleted();
    }

    @Override
    public void getBalance(GetBalanceRequest request, StreamObserver<WalletResponse> observer) {
        var walletId = parseUuid(request.getWalletId(), "wallet_id");

        var wallet = walletService.getWalletById(walletId);
        var response = toWalletResponse(wallet);

        observer.onNext(response);
        observer.onCompleted();
    }

    @Override
    public void createWallet(CreateWalletRequest request, StreamObserver<WalletResponse> observer) {
        var walletId = parseUuid(request.getWalletId(), "wallet_id");

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

    private static long requirePositiveAmount(long amount, String operation) {
        if (amount <= 0) {
            var message = operation + " amount must be positive: " + amount;

            throw new IllegalArgumentException(message);
        }

        return amount;
    }

    private static TransactionResponse toTransactionResponse(WalletService.WalletMovement movement) {
        return TransactionResponse.newBuilder()
                .setTransactionId(movement.entry().getTransactionId().toString())
                .setWalletId(movement.entry().getWalletId().toString())
                .setAmount(movement.walletAmount())
                .setTransactionAmount(movement.entry().getAmount())
                .setUpdatedAt(toTimestamp(movement.entry().getUpdatedAt()))
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
