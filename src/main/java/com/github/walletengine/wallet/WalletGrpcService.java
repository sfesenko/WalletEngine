package com.github.walletengine.wallet;

import com.github.walletengine.grpc.wallet.v1.*;
import com.github.walletengine.util.Utils;
import com.google.protobuf.Timestamp;
import io.grpc.stub.StreamObserver;
import org.springframework.grpc.server.service.GrpcService;

import java.time.Instant;

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
        var transactionId = Utils.parseUuid(request.getTransactionId(), "transaction_id");
        var walletId = Utils.parseUuid(request.getWalletId(), "wallet_id");
        var amount = Utils.requirePositive(request.getAmount(),
                () -> "debit amount must be positive: " + request.getAmount());

        var transaction = walletService.debit(transactionId, walletId, amount);
        var response = toTransactionResponse(transaction);

        observer.onNext(response);
        observer.onCompleted();
    }

    @Override
    public void credit(TransactionRequest request, StreamObserver<TransactionResponse> observer) {
        var transactionId = Utils.parseUuid(request.getTransactionId(), "transaction_id");
        var walletId = Utils.parseUuid(request.getWalletId(), "wallet_id");
        var amount = Utils.requirePositive(request.getAmount(),
                () -> "credit amount must be positive: " + request.getAmount());

        var transaction = walletService.credit(transactionId, walletId, amount);
        var response = toTransactionResponse(transaction);

        observer.onNext(response);
        observer.onCompleted();
    }

    @Override
    public void getBalance(GetBalanceRequest request, StreamObserver<WalletResponse> observer) {
        var walletId = Utils.parseUuid(request.getWalletId(), "wallet_id");

        var wallet = walletService.getWalletById(walletId);
        var response = toWalletResponse(wallet);

        observer.onNext(response);
        observer.onCompleted();
    }

    @Override
    public void createWallet(CreateWalletRequest request, StreamObserver<WalletResponse> observer) {
        var walletId = Utils.parseUuid(request.getWalletId(), "wallet_id");

        var wallet = walletService.createWallet(walletId);
        var response = toWalletResponse(wallet);

        observer.onNext(response);
        observer.onCompleted();
    }

    private static TransactionResponse toTransactionResponse(WalletTransaction transaction) {
        return TransactionResponse.newBuilder()
                .setTransactionId(transaction.getTransactionId().toString())
                .setWalletId(transaction.getWalletId().toString())
                .setAmount(transaction.getWalletBalance())
                .setTransactionAmount(transaction.getAmount())
                .setUpdatedAt(toTimestamp(transaction.getUpdatedAt()))
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
