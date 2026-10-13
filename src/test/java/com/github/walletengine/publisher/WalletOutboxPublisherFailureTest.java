package com.github.walletengine.publisher;

import com.github.walletengine.TestFixture;
import com.github.walletengine.wallet.WalletService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Checks publisher failure handling
 */
@SpringBootTest
@ActiveProfiles("test")
class WalletOutboxPublisherFailureTest {

    private static final int BATCH_SIZE = 10;

    @Value("${wallet.outbox.max-attempts:10}")
    private int maxAttempts;

    @Autowired
    private WalletService walletService;

    @Autowired
    private WalletOutboxMessageRepository outboxRepository;

    @Autowired
    private PlatformTransactionManager txManager;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private MessageConverter walletEventMessageConverter;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AmqpAdmin amqpAdmin;

    private final List<CachingConnectionFactory> deadFactories = new ArrayList<>();

    @BeforeEach
    void clearPendingOutbox() {
        jdbcTemplate.update("delete from wallet_outbox where status = 'PENDING'");
    }

    @AfterEach
    void closeDeadFactories() {
        deadFactories.forEach(CachingConnectionFactory::destroy);
        deadFactories.clear();
    }

    @Test
    void brokerUnreachableMarksRowFailedAndPausesTheNextPublish() {
        var transactionId = TestFixture.creditFreshTransactions(walletService, BATCH_SIZE, 500L).getFirst();
        // a long pause: the second publish must be skipped regardless of test speed
        var publisher = publisherOn(deadBrokerTemplate(), Duration.ofHours(1));

        publisher.publish();

        var row = outboxRepository.findById(transactionId).orElseThrow();

        assertThat(row.getAttempts()).isEqualTo(1);
        assertThat(row.getLastError()).isNotBlank();
        assertThat(row.getStatus()).isEqualTo(WalletOutboxMessage.Status.PENDING);

        // whole batch failed: the next publish is paused, attempts stay put
        publisher.publish();

        assertThat(outboxRepository.findById(transactionId).orElseThrow().getAttempts()).isEqualTo(1);
    }

    @Test
    void rowAtTheAttemptLimitIsLeftInTheOutboxAsIs() {
        var transactionId = TestFixture.creditFreshTransactions(walletService, BATCH_SIZE, 500L).getFirst();
        // zero retry delay: every publish is allowed, so attempts can burn up to the limit
        var publisher = publisherOn(deadBrokerTemplate(), Duration.ZERO);

        for (var i = 0; i < maxAttempts; i++) {
            publisher.publish();
        }

        assertThat(outboxRepository.findById(transactionId).orElseThrow().getAttempts()).isEqualTo(maxAttempts);

        publisher.publish();

        var row = outboxRepository.findById(transactionId).orElseThrow();

        assertThat(row.getAttempts()).isEqualTo(maxAttempts);
        assertThat(row.getStatus()).isEqualTo(WalletOutboxMessage.Status.PENDING);
    }

    @Test
    void stuckProcessingRowIsReclaimedAndRepublished() {
        var transactionId = TestFixture.creditFreshTransactions(walletService, 1, 500L).getFirst();

        // simulate a crash after the claim, long before the processing timeout
        jdbcTemplate.update("""
                update wallet_outbox
                   set status = 'PROCESSING', created_at = now() - interval '2 hours'
                 where transaction_id = ?
                """, transactionId);

        var publisher = publisherOn(rabbitTemplate);
        publisher.reclaimProcessing();

        assertThat(outboxRepository.findById(transactionId).orElseThrow().getStatus())
                .isEqualTo(WalletOutboxMessage.Status.PENDING);

        publisher.publish();

        assertThat(outboxRepository.findById(transactionId)).isEmpty();
    }

    @Test
    void unroutableMessageSurvivesWithReturnedError() {
        var transactionId = TestFixture.creditFreshTransactions(walletService, BATCH_SIZE, 500L).getFirst();
        var publisher = publisherOn(rabbitTemplate);

        amqpAdmin.removeBinding(new Binding(WalletAmqpConfiguration.QUEUE,
                Binding.DestinationType.QUEUE, WalletAmqpConfiguration.EXCHANGE,
                WalletAmqpConfiguration.ROUTING_KEY, Map.of()));
        try {
            publisher.publish();

            var row = outboxRepository.findById(transactionId).orElseThrow();

            assertThat(row.getAttempts()).isEqualTo(1);
            assertThat(row.getLastError()).startsWith("returned:");
            assertThat(row.getStatus()).isEqualTo(WalletOutboxMessage.Status.PENDING);
        } finally {
            amqpAdmin.initialize();
        }
    }

    @Test
    void publisherConstructorRejectsPublisherReturnsDisabled() {
        assertThatThrownBy(() -> publisherOn(deadBrokerTemplate(false)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("publisher returns must be enabled");
    }

    @Test
    void publisherConstructorRejectsSubMinuteProcessingTimeout() {
        assertThatThrownBy(() -> new WalletOutboxPublisher(outboxRepository, rabbitTemplate, txManager,
                BATCH_SIZE, maxAttempts, Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofSeconds(30)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("processing timeout must be at least 1 minute");
    }

    @Test
    void publisherConstructorRejectsNonPositiveMaxAttempts() {
        assertThatThrownBy(() -> new WalletOutboxPublisher(outboxRepository, rabbitTemplate, txManager,
                BATCH_SIZE, 0, Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofMinutes(30)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max attempts must be at least 1");
    }

    private WalletOutboxPublisher publisherOn(RabbitTemplate template) {
        return publisherOn(template, Duration.ofSeconds(5));
    }

    private WalletOutboxPublisher publisherOn(RabbitTemplate template, Duration retryDelay) {
        return new WalletOutboxPublisher(outboxRepository, template, txManager,
                BATCH_SIZE, maxAttempts, Duration.ofSeconds(5), retryDelay, Duration.ofMinutes(30));
    }

    private RabbitTemplate deadBrokerTemplate() {
        return deadBrokerTemplate(true);
    }

    /** Template whose factory is never connected; {@code publisherReturns} drives the constructor guard. */
    private RabbitTemplate deadBrokerTemplate(boolean publisherReturns) {
        var factory = new CachingConnectionFactory("localhost", 1);
        factory.setConnectionTimeout(1000);
        factory.setPublisherReturns(publisherReturns);
        deadFactories.add(factory);

        var template = new RabbitTemplate(factory);
        template.setMessageConverter(walletEventMessageConverter);

        return template;
    }
}
