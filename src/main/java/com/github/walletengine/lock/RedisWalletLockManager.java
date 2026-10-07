package com.github.walletengine.lock;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

/**
 * Redis distributed lock: {@code SET wallet:{id} NX PX <ttl>} to acquire,
 * {@code DEL wallet:{id}} to release.
 *
 * <p>The TTL is what makes a crashed instance safe — the key expires on its own, so no
 * lock is left behind. It must exceed the longest expected transaction.
 */
@Component
public class RedisWalletLockManager implements WalletLockManager {

    private static final String KEY_PREFIX = "wallet:";

    private final StringRedisTemplate redis;
    private final Duration ttl;
    private final Duration waitBudget;
    private final Duration retryInterval;

    public RedisWalletLockManager(
            StringRedisTemplate redis,
            @Value("${wallet.lock.ttl:5s}") Duration ttl,
            @Value("${wallet.lock.wait-budget:2s}") Duration waitBudget,
            @Value("${wallet.lock.retry-interval:25ms}") Duration retryInterval) {
        this.redis = redis;
        this.ttl = ttl;
        this.waitBudget = waitBudget;
        this.retryInterval = retryInterval;
    }

    @Override
    public void acquire(UUID walletId) {
        var key = KEY_PREFIX + walletId;
        var deadline = System.nanoTime() + waitBudget.toNanos();

        var acquired = setIfAbsent(key);
        while (!acquired) {
            var expired = System.nanoTime() >= deadline;
            if (expired) {
                throw new LockAcquisitionTimeoutException(walletId);
            }
            sleep();
            acquired = setIfAbsent(key);
        }
    }

    @Override
    public void release(UUID walletId) {
        var key = KEY_PREFIX + walletId;

        redis.delete(key);
    }

    private boolean setIfAbsent(String key) {
        var result = redis.opsForValue().setIfAbsent(key, "1", ttl);
        return Boolean.TRUE.equals(result);
    }

    private void sleep() {
        try {
            var millis = retryInterval.toMillis();

            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();

            throw new IllegalStateException("interrupted while waiting for lock", e);
        }
    }
}
