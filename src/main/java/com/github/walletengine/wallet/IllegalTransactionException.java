package com.github.walletengine.wallet;

/**
 * Specified transaction is not correct and cannot be applied
 * maps to {@code INVALID_ARGUMENT} — the same request will conflict again, not retryable.
 */
public class IllegalTransactionException extends IllegalArgumentException {

    public IllegalTransactionException(String message) {
        super(message);
    }
}
