package com.github.walletengine.wallet;

import java.util.UUID;

/** Maps to {@code ALREADY_EXISTS}. */
public class WalletAlreadyExistsException extends RuntimeException {

    public WalletAlreadyExistsException(UUID walletId, Throwable cause) {
        super("wallet already exists: " + walletId, cause);
    }
}
