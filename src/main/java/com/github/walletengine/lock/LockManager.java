package com.github.walletengine.lock;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;


/**
 * Distributed LockManager that utilize single redis instance.
 * As improvement - it may be enhanced to implement <a href="https://redis.io/docs/latest/develop/clients/patterns/distributed-locks/#the-redlock-algorithm">RedLock</a>
 */
@Service
public class LockManager {
    private final StringRedisTemplate redis;

    // Max amount of time, how long it tries to acquire lock
    private final Duration lockTimeout;

    // Amount of time between acquire lock attempts
    private final Duration retryInterval;
    private static final String LOCK_PREFIX = "wallet_engine_";

    public LockManager(
            StringRedisTemplate redis,
            @Value("${wallet.lock.retry-interval:25ms}") Duration retryInterval,
            @Value("${wallet.lock.acquire-timeout:2s}") Duration lockTimeout) {
        this.redis = redis;
        this.retryInterval = retryInterval;
        this.lockTimeout = lockTimeout;
    }

    /**
     * Acquire lock for specified key and time
     */
    public Lock acquire(String key, Duration ttl) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("ttl must be positive: " + ttl);
        }
        var lockPayload = UUID.randomUUID()
                .toString();
        var redisOps = redis.opsForValue();
        var lockTimeoutNs = lockTimeout.toNanos();
        var retryIntervalNs = retryInterval.toNanos();
        var deadline = System.nanoTime() + lockTimeoutNs;
        var lockKey = LOCK_PREFIX + key;
        while (!Boolean.TRUE.equals(redisOps.setIfAbsent(lockKey, lockPayload, ttl))) {
            if (System.nanoTime() + retryIntervalNs >= deadline) {
                throw new LockFailedException("acquire took more than " + lockTimeout);
            }
            try {
                Thread.sleep(retryInterval);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LockFailedException("acquire wait was interrupted", e);
            }
        }
        return new Lock(lockKey, lockPayload);
    }

    /**
     * Check that lock is still valid
     */
    public void check(Lock lock) {
        var payload = redis.opsForValue().get(lock.key);
        if (!lock.payload.equals(payload)) {
            throw new LockFailedException("lock for " + lock.key + " is not held by us (expired or taken over)");
        }
    }

    /**
     * Remove lock, if it's owned, otherwise - leave it
     * do not throw any exception
     */
    public void releaseQuiet(Lock lock) {
        try {
            redis.compareAndDelete(lock.key, lock.payload);
        } catch (RuntimeException ignored) {
            // best effort: the lock expires on its own
        }
    }

    public class Lock implements AutoCloseable {
        private final String key;
        private final String payload;

        Lock(String key, String payload) {
            this.key = key;
            this.payload = payload;
        }

        /**
         * Check that lock is still valid
         */
        public void check() {
            LockManager.this.check(this);
        }

        public void release() {
            LockManager.this.releaseQuiet(this);
        }

        @Override
        public void close() {
            release();
        }

        @Override
        public String toString() {
            return "Lock{key='" + key + "'}";
        }
    }
}
