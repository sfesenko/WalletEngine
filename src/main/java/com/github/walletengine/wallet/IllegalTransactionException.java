package com.github.walletengine.wallet;

import org.jspecify.annotations.NonNull;

import java.util.UUID;

/**
 * Specified transaction is not correct and cannot be applied
 * maps to {@code INVALID_ARGUMENT} — the same request will conflict again, not retryable.
 */
public class IllegalTransactionException extends IllegalArgumentException {

    public IllegalTransactionException(String message) {
        super(message);
    }

    public static IllegalTransactionException transactionAlreadyUsed(@NonNull UUID transactionId) {
        var msg = "transaction_id %s is already used".formatted(transactionId);
        return new IllegalTransactionException(msg);
    }
}
