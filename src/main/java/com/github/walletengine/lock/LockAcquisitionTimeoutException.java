package com.github.walletengine.lock;

import java.util.UUID;

/**
 * Maps to {@code ABORTED} — retryable. The lock has a TTL, so the wallet becomes
 * available once it expires.
 */
public class LockAcquisitionTimeoutException extends RuntimeException {

    public LockAcquisitionTimeoutException(UUID walletId) {
        super("could not acquire lock for wallet: " + walletId);
    }
}
