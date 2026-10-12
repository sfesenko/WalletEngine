package com.github.walletengine.publisher;

import com.github.walletengine.TestFixture;
import com.github.walletengine.wallet.WalletService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Autowired;
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
 * Checks publisher failure handling against real Postgres and an unreachable broker
 * (a RabbitTemplate pointed at a dead port; no mocks). Leftover pending rows are
 * cleared per test so a full batch of exactly this test's rows is claimed.
 */
@SpringBootTest
@ActiveProfiles("test")
class WalletOutboxPublisherFailureTest {

    private static final int BATCH_SIZE = 10;

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
        jdbcTemplate.update("delete from wallet_outbox where status = 'pending'");
    }

    @AfterEach
    void closeDeadFactories() {
        deadFactories.forEach(CachingConnectionFactory::destroy);
        deadFactories.clear();
    }

    @Test
    void brokerUnreachableMarksRowFailedAndPausesTheNextPublish() {
        var transactionId = TestFixture.creditFreshWallets(walletService, BATCH_SIZE, 500L).getFirst();
        // a long pause: the second publish must be skipped regardless of test speed
        var publisher = publisherOn(deadBrokerTemplate(), Duration.ofHours(1));

        publisher.publish();

        var row = outboxRepository.findById(transactionId).orElseThrow();

        assertThat(row.getAttempts()).isEqualTo(1);
        assertThat(row.getLastError()).isNotBlank();
        assertThat(row.getStatus()).isEqualTo(WalletOutboxMessage.STATUS_PENDING);

        // whole batch failed: the next publish is paused, attempts stay put
        publisher.publish();

        assertThat(outboxRepository.findById(transactionId).orElseThrow().getAttempts()).isEqualTo(1);
    }

    @Test
    void rowAtTheAttemptLimitIsLeftInTheOutboxAsIs() {
        var transactionId = TestFixture.creditFreshWallets(walletService, BATCH_SIZE, 500L).getFirst();
        // zero retry delay: every publish is allowed, so attempts can burn up to the limit
        var publisher = publisherOn(deadBrokerTemplate(), Duration.ZERO);

        for (var i = 0; i < 10; i++) {
            publisher.publish();
        }

        assertThat(outboxRepository.findById(transactionId).orElseThrow().getAttempts()).isEqualTo(10);

        publisher.publish();

        var row = outboxRepository.findById(transactionId).orElseThrow();

        assertThat(row.getAttempts()).isEqualTo(10);
        assertThat(row.getStatus()).isEqualTo(WalletOutboxMessage.STATUS_PENDING);
    }

    @Test
    void stuckProcessingRowIsReclaimedAndRepublished() {
        var transactionId = TestFixture.creditFreshWallets(walletService, 1, 500L).getFirst();

        // simulate a crash after the claim, long before the processing timeout
        jdbcTemplate.update("""
                update wallet_outbox
                   set status = 'processing', created_at = now() - interval '2 hours'
                 where transaction_id = ?
                """, transactionId);

        var publisher = publisherOn(rabbitTemplate);
        publisher.reclaimProcessing();

        assertThat(outboxRepository.findById(transactionId).orElseThrow().getStatus())
                .isEqualTo(WalletOutboxMessage.STATUS_PENDING);

        publisher.publish();

        assertThat(outboxRepository.findById(transactionId)).isEmpty();
    }

    @Test
    void unroutableMessageSurvivesWithReturnedError() {
        var transactionId = TestFixture.creditFreshWallets(walletService, BATCH_SIZE, 500L).getFirst();
        var publisher = publisherOn(rabbitTemplate);

        amqpAdmin.removeBinding(new Binding(WalletAmqpConfiguration.QUEUE,
                Binding.DestinationType.QUEUE, WalletAmqpConfiguration.EXCHANGE,
                WalletAmqpConfiguration.ROUTING_KEY, Map.of()));
        try {
            publisher.publish();

            var row = outboxRepository.findById(transactionId).orElseThrow();

            assertThat(row.getAttempts()).isEqualTo(1);
            assertThat(row.getLastError()).startsWith("returned:");
            assertThat(row.getStatus()).isEqualTo(WalletOutboxMessage.STATUS_PENDING);
        } finally {
            amqpAdmin.initialize();
        }
    }

    @ParameterizedTest
    @CsvSource(value = {
            "noReturns | IllegalStateException    ",
            "subMinute | IllegalArgumentException ",
    }, delimiter = '|')
    void publisherConstructorRejectsInvalidConfiguration(String scenario, String exception) {
        var expected = switch (scenario.strip()) {
            case "noReturns" -> IllegalStateException.class;
            case "subMinute" -> IllegalArgumentException.class;
            default -> throw new IllegalArgumentException("unknown scenario: " + scenario);
        };

        assertThatThrownBy(() -> {
            if (scenario.strip().equals("noReturns")) {
                var factory = new CachingConnectionFactory("localhost", 1);
                deadFactories.add(factory);
                publisherOn(new RabbitTemplate(factory));
            } else {
                new WalletOutboxPublisher(outboxRepository, rabbitTemplate, txManager,
                        BATCH_SIZE, Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofSeconds(30));
            }
        }).isInstanceOf(expected);
    }

    private WalletOutboxPublisher publisherOn(RabbitTemplate template) {
        return publisherOn(template, Duration.ofSeconds(5));
    }

    private WalletOutboxPublisher publisherOn(RabbitTemplate template, Duration retryDelay) {
        return new WalletOutboxPublisher(outboxRepository, template, txManager,
                BATCH_SIZE, Duration.ofSeconds(5), retryDelay, Duration.ofMinutes(30));
    }

    private RabbitTemplate deadBrokerTemplate() {
        var factory = new CachingConnectionFactory("localhost", 1);
        factory.setConnectionTimeout(1000);
        factory.setPublisherReturns(true);
        deadFactories.add(factory);

        var template = new RabbitTemplate(factory);
        template.setMessageConverter(walletEventMessageConverter);

        return template;
    }
}
