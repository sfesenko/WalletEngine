package com.github.walletengine.wallet;

import java.util.UUID;

/** Maps to {@code NOT_FOUND}. */
public class WalletNotFoundException extends RuntimeException {

    public WalletNotFoundException(UUID walletId) {
        super("unknown wallet: " + walletId);
    }
}
