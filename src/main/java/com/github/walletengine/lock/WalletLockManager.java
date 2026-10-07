package com.github.walletengine.lock;

import java.util.UUID;

/**
 * Serializes request processing per {@code wallet_id} across service instances.
 *
 * <p>Not a correctness requirement: the conditional update and the ledger primary key
 * hold even if a lock is skipped or expires mid-flight.
 *
 * <p>Acquire first, release in a {@code finally} block. The lock lives outside any
 * database transaction, so no connection is held while waiting for it.
 */
public interface WalletLockManager {

    /**
     * Acquires the lock for {@code walletId}, waiting up to the configured budget.
     *
     * @throws LockAcquisitionTimeoutException if the lock could not be acquired in time
     */
    void acquire(UUID walletId);

    /** Releases a lock held for {@code walletId}. */
    void release(UUID walletId);
}
