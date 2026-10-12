package com.github.walletengine.lock;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Functional tests for {@link LockManager} against the real Redis instance started
 * from Docker Compose by the {@code test} profile (same pattern as
 * {@code WalletConcurrencyTest}). No mocks.
 */
@SpringBootTest
@ActiveProfiles("test")
class LockManagerFunctionalTest {

    @Autowired
    private LockManager lockManager;

    @Autowired
    private StringRedisTemplate redis;

    private final List<String> keys = new ArrayList<>();

    @AfterEach
    void cleanup() {
        keys.forEach(redis::delete);
        keys.clear();
    }

    private String newKey() {
        var key = "test-" + UUID.randomUUID();
        keys.add(lockKey(key));
        return key;
    }

    private static String lockKey(String key) {
        return "wallet_engine_" + key;
    }

    @Test
    void acquireStoresPayloadAndReleaseRemovesKey() {
        var key = newKey();

        var lock = lockManager.acquire(key, Duration.ofSeconds(5));

        assertThat(redis.hasKey(lockKey(key))).isTrue();
        lock.release();
        assertThat(redis.hasKey(lockKey(key))).isFalse();
    }

    @Test
    void acquireOnHeldLockTimesOutAfterConfiguredBudget() {
        var key = newKey();
        var holder = lockManager.acquire(key, Duration.ofSeconds(5));
        try {
            var start = System.nanoTime();

            assertThatThrownBy(() -> lockManager.acquire(key, Duration.ofSeconds(5)))
                    .isInstanceOf(LockFailedException.class);

            // wallet.lock.acquire-timeout defaults to 2s; a hard-coded 1-minute
            // budget would fail this duration assertion.
            var elapsed = Duration.ofNanos(System.nanoTime() - start);
            assertThat(elapsed).isLessThan(Duration.ofSeconds(5));
        } finally {
            holder.release();
        }
    }

    @Test
    void distinctKeysAcquireWithoutBlockingEachOther() {
        var keyA = newKey();
        var keyB = newKey();
        var ttl = Duration.ofSeconds(5);

        var lockA = lockManager.acquire(keyA, ttl);
        var lockB = lockManager.acquire(keyB, ttl);

        assertThat(lockA).isNotNull();
        assertThat(lockB).isNotNull();
        lockA.release();
        lockB.release();
    }

    @Test
    void checkPassesWhileHeld() {
        var key = newKey();
        var lock = lockManager.acquire(key, Duration.ofSeconds(5));

        lock.check(); // no exception: the lock is still ours

        lock.release();
    }

    @ParameterizedTest
    @CsvSource(value = {
            "checkExpired          ",
            "checkTakenOver        ",
            "releaseQuiet          ",
            "staleReleaseKeepsOwner",
    }, delimiter = '|')
    void ttlExpiryBehaviour(String scenario) throws Exception {
        var key = newKey();
        var stale = lockManager.acquire(key, Duration.ofMillis(100));

        Thread.sleep(250); // stale lock expires

        switch (scenario.strip()) {
            case "checkExpired" -> {
                assertThat(redis.hasKey(lockKey(key))).isFalse();
                assertThatThrownBy(stale::check).isInstanceOf(LockFailedException.class);
            }
            case "checkTakenOver" -> {
                var owner = lockManager.acquire(key, Duration.ofSeconds(5));

                assertThatThrownBy(stale::check).isInstanceOf(LockFailedException.class);
                owner.check(); // the new owner is healthy

                owner.release();
            }
            case "releaseQuiet" -> assertThatCode(stale::release).doesNotThrowAnyException();
            case "staleReleaseKeepsOwner" -> {
                var owner = lockManager.acquire(key, Duration.ofSeconds(5));

                stale.release(); // quiet no-op, must not delete the owner's lock
                assertThat(redis.hasKey(lockKey(key))).isTrue();

                owner.release();
            }
            default -> throw new IllegalArgumentException("unknown scenario: " + scenario);
        }
    }

    @Test
    void secondReleaseAndCloseAreIdempotent() {
        var key = newKey();
        var lock = lockManager.acquire(key, Duration.ofSeconds(5));

        lock.release();
        lock.release();
        lock.close();
    }

    @Test
    void closeReleasesLockOnNormalExit() {
        var key = newKey();

        try (var lock = lockManager.acquire(key, Duration.ofSeconds(5))) {
            assertThat(redis.hasKey(lockKey(key))).isTrue();
        }

        assertThat(redis.hasKey(lockKey(key))).isFalse();
    }

    @Test
    void closeReleasesLockOnExceptionalExit() {
        var key = newKey();

        assertThatThrownBy(() -> {
            try (var lock = lockManager.acquire(key, Duration.ofSeconds(5))) {
                throw new IllegalStateException("boom");
            }
        }).isInstanceOf(IllegalStateException.class);

        assertThat(redis.hasKey(lockKey(key))).isFalse();
    }

    @Test
    void interruptedWaiterThrowsAndRestoresInterruptFlag() throws Exception {
        var key = newKey();
        var ttl = Duration.ofSeconds(5);
        var holder = lockManager.acquire(key, ttl);

        var started = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();
        var interruptFlagAfterCatch = new AtomicBoolean();
        var waiter = new Thread(() -> {
            started.countDown();
            try {
                lockManager.acquire(key, ttl);
            } catch (Throwable t) {
                failure.set(t);
                interruptFlagAfterCatch.set(Thread.currentThread().isInterrupted());
            }
        });
        waiter.start();
        started.await();
        Thread.sleep(50); // let the waiter enter its retry sleep
        waiter.interrupt();
        waiter.join(2_000);

        try {
            assertThat(failure.get()).isInstanceOf(LockFailedException.class);
            assertThat(interruptFlagAfterCatch).isTrue();
        } finally {
            holder.release();
        }
    }

    @ParameterizedTest
    @CsvSource(value = {
            "null ",
            "PT0S ",
            "-PT1S",
    }, delimiter = '|', nullValues = "null")
    void acquireRejectsInvalidTtl(Duration ttl) {
        assertThatThrownBy(() -> lockManager.acquire(newKey(), ttl))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void releaseWithDeadRedisSwallowsConnectionFailure() {
        var factory = new LettuceConnectionFactory("localhost", 1);
        factory.afterPropertiesSet();
        try {
            var template = new StringRedisTemplate(factory);
            template.afterPropertiesSet();

            var unreachable = new LockManager(template, Duration.ofMillis(25), Duration.ofSeconds(1));
            var lock = unreachable.new Lock("unreachable-key", "payload");

            assertThatCode(lock::release).doesNotThrowAnyException();
            assertThatCode(lock::close).doesNotThrowAnyException();
        } finally {
            factory.destroy();
        }
    }
}
