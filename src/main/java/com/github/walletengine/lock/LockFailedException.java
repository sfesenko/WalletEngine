package com.github.walletengine.lock;

/**
 * The lock was not held when required: the acquire budget was exceeded, the wait was
 * interrupted, or the lock was lost (expired or taken over) before release.
 *
 * <p>Maps to {@code ABORTED} — retryable. Locks expire on their own, and retried
 * movements are idempotent.
 */
public class LockFailedException extends RuntimeException {

    public LockFailedException(String message) {
        super(message);
    }

    public LockFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}