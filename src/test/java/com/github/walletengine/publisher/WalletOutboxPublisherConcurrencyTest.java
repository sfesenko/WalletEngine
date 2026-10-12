package com.github.walletengine.publisher;

import com.github.walletengine.wallet.WalletService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.github.walletengine.TestFixture.drainEvents;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Checks that two publisher instances draining the same outbox never publish the same
 * event twice.
 */
@SpringBootTest
@ActiveProfiles("test")
class WalletOutboxPublisherConcurrencyTest {

    private static final int SEEDED_ROWS = 300;
    private static final int BATCH_SIZE = 10;

    @Autowired
    private WalletService walletService;

    @Autowired
    private WalletOutboxMessageRepository outboxRepository;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private PlatformTransactionManager txManager;

    @BeforeEach
    void drainQueue() {
        drainEvents(rabbitTemplate);
    }

    @Tag("slow")
    @Test
    void twoPublishersPublishEveryEventExactlyOnce() throws Exception {
        var seeded = new HashSet<UUID>();
        var walletId = UUID.randomUUID();

        walletService.createWallet(walletId);
        for (var i = 0; i < SEEDED_ROWS; i++) {
            var transactionId = UUID.randomUUID();
            walletService.credit(transactionId, walletId, 1L);
            seeded.add(transactionId);
        }

        var first = new WalletOutboxPublisher(outboxRepository, rabbitTemplate, txManager,
                BATCH_SIZE, Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofMinutes(10));
        var second = new WalletOutboxPublisher(outboxRepository, rabbitTemplate, txManager,
                BATCH_SIZE, Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofMinutes(10));

        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var firstRun = pool.submit(() -> publishAfterLatch(first, start));
            var secondRun = pool.submit(() -> publishAfterLatch(second, start));
            start.countDown();
            firstRun.get(30, TimeUnit.SECONDS);
            secondRun.get(30, TimeUnit.SECONDS);
        }

        var counts = new HashMap<UUID, Integer>();
        for (var event : drainEvents(rabbitTemplate)) {
            if (seeded.contains(event.transactionId())) {
                counts.merge(event.transactionId(), 1, Integer::sum);
            }
        }

        assertThat(counts).hasSize(SEEDED_ROWS);
        assertThat(counts.values()).containsOnly(1);
        assertThat(outboxRepository.findAllById(seeded)).isEmpty();
    }

    private static void publishAfterLatch(WalletOutboxPublisher publisher, CountDownLatch start) {
        try {
            start.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        publisher.publish();
    }
}
