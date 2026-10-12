package com.github.walletengine.publisher;

import com.github.walletengine.TestFixture;
import com.github.walletengine.wallet.WalletService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static com.github.walletengine.TestFixture.drainEvents;
import static com.github.walletengine.TestFixture.findEvent;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Checks the outbox publisher against real Postgres and RabbitMQ (Docker Compose
 * infrastructure, {@code test} profile). The queue is drained dynamically: the shared
 * database holds other tests' rows, so only this test's own transaction ids are
 * asserted, never absolute counts.
 */
@SpringBootTest
@ActiveProfiles("test")
class WalletOutboxPublisherTest {

    @Autowired
    private WalletService walletService;

    @Autowired
    private WalletOutboxPublisher publisher;

    @Autowired
    private WalletOutboxMessageRepository outboxRepository;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @BeforeEach
    void drainQueue() {
        drainEvents(rabbitTemplate);
    }

    @Test
    void publishesPendingRowsOnceAndDeletesThem() {
        var walletId = UUID.randomUUID();
        var first = UUID.randomUUID();
        var second = UUID.randomUUID();

        walletService.createWallet(walletId);
        walletService.credit(first, walletId, 500L);
        walletService.credit(second, walletId, 200L);

        publisher.publish();

        var events = drainEvents(rabbitTemplate);

        assertThat(events).extracting(WalletUpdatedEvent::transactionId).contains(first, second);
        assertThat(findEvent(events, first).amount()).isEqualTo(500L);
        assertThat(findEvent(events, second).amount()).isEqualTo(200L);

        // rows go out in seq order, not just in the order they were inserted
        assertThat(events.indexOf(findEvent(events, first)))
                .isLessThan(events.indexOf(findEvent(events, second)));

        assertThat(outboxRepository.findById(first)).isEmpty();
        assertThat(outboxRepository.findById(second)).isEmpty();

        publisher.publish();

        assertThat(drainEvents(rabbitTemplate)).extracting(WalletUpdatedEvent::transactionId)
                .doesNotContain(first, second);
    }

    @ParameterizedTest
    @CsvSource(value = {
            "1  | true  | 700",
            "10 | false | 400",
    }, delimiter = '|')
    void failedRowsAreRetriedUntilTheAttemptLimit(int failures, boolean published, long amount) {
        var transactionId = TestFixture.creditFreshWallet(walletService, amount);

        for (var i = 0; i < failures; i++) {
            outboxRepository.markFailed(transactionId, "simulated failure " + i);
        }

        publisher.publish();

        var event = findEvent(drainEvents(rabbitTemplate), transactionId);
        if (published) {
            assertThat(event.amount()).isEqualTo(amount);
            assertThat(outboxRepository.findById(transactionId)).isEmpty();
        } else {
            assertThat(event).isNull();
            assertThat(outboxRepository.findById(transactionId).orElseThrow().getAttempts())
                    .isEqualTo(failures);
        }
    }
}
