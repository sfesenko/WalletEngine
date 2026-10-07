package com.github.walletengine.wallet;

import java.util.UUID;

/** Maps to {@code FAILED_PRECONDITION}. */
public class InsufficientFundsException extends RuntimeException {

    public InsufficientFundsException(UUID walletId) {
        super("balance would go negative for wallet: " + walletId);
    }
}
