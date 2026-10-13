package com.github.walletengine.publisher;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitOperations;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Publishes wallet events from {@code wallet_outbox} to RabbitMQ with publisher confirms.*
 * It implements multi-node support with parallel messages processing on several nodes, and naive circuit-breaker
 * implementation, in case when RabbitMQ is down
 */
@Service
public class WalletOutboxPublisher {

    private static final Logger LOGGER = LoggerFactory.getLogger(WalletOutboxPublisher.class);

    private final WalletOutboxMessageRepository outboxRepository;
    private final RabbitTemplate rabbitTemplate;
    private final TransactionTemplate txTemplate;
    private final int batchSize;
    private final int maxAttempts;
    private final Duration batchPublishTimeout;
    private final Duration retryDelay;
    private final Duration processingTimeout;

    @Nullable
    private Instant publishAfter;

    public WalletOutboxPublisher(
            WalletOutboxMessageRepository outboxRepository,
            RabbitTemplate rabbitTemplate,
            PlatformTransactionManager txManager,
            @Value("${wallet.outbox.batch-size:100}") int batchSize,
            @Value("${wallet.outbox.max-attempts:10}") int maxAttempts,
            @Value("${wallet.outbox.batch-publish-timeout:5s}") Duration batchPublishTimeout,
            @Value("${wallet.outbox.retry-delay:1m}") Duration retryDelay,
            @Value("${wallet.outbox.processing-timeout:30m}") Duration processingTimeout) {
        if (!(rabbitTemplate.getConnectionFactory() instanceof CachingConnectionFactory connectionFactory)
                || !connectionFactory.isPublisherReturns()) {
            // without returns, unroutable events would be acked and deleted as published
            throw new IllegalStateException("publisher returns must be enabled (spring.rabbitmq.publisher-returns)");
        }
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("max attempts must be at least 1: " + maxAttempts);
        }
        if (processingTimeout.toMinutes() < 1) {
            throw new IllegalArgumentException("processing timeout must be at least 1 minute: " + processingTimeout);
        }

        this.outboxRepository = outboxRepository;
        this.rabbitTemplate = rabbitTemplate;
        this.txTemplate = new TransactionTemplate(txManager);
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
        this.batchPublishTimeout = batchPublishTimeout;
        this.retryDelay = retryDelay;
        this.processingTimeout = processingTimeout;
    }

    record PublishResult(
            UUID transactionId,
            boolean published,
            String error
    ) {

        static PublishResult ok(UUID transactionId) {
            return new PublishResult(transactionId, true, null);
        }

        static PublishResult failed(UUID transactionId, String error) {
            return new PublishResult(transactionId, false, error);
        }
    }

    record PublishBatchResult(
            List<UUID> published,
            Map<UUID, String> failed
    ) {
        boolean batchFailed() {
            return published.isEmpty();
        }

        boolean hasFails() {
            return !failed.isEmpty();
        }
    }

    @Scheduled(
            fixedDelayString = "${wallet.outbox.interval:5s}",
            initialDelayString = "${wallet.outbox.initial-delay:0}")
    public void publish() {
        drainEventQueue();
    }

    @Scheduled(
            fixedDelayString = "${wallet.outbox.reclaim-interval:1h}",
            initialDelayString = "${wallet.outbox.initial-delay:0}")
    public void reclaimProcessing() {
        var reset = outboxRepository.resetStaleProcessing(processingTimeout.toMinutes());
        if (reset > 0) {
            LOGGER.warn("reclaimed {} stuck wallet outbox rows", reset);
        }
    }

    private void drainEventQueue() {
        if (publishAfter != null && Instant.now().isBefore(publishAfter)) {
            // paused after a whole-batch failure
            return;
        }
        if (publishAfter != null) {
            LOGGER.info("outbox publishing resumed");
            publishAfter = null;
        }

        List<WalletOutboxMessage> rows;
        do {
            rows = txTemplate.execute(status -> {
                var claimed = outboxRepository.claim(
                        WalletOutboxMessage.Status.PENDING.name(), maxAttempts, batchSize);
                if (!claimed.isEmpty()) {
                    outboxRepository.markProcessing(
                            claimed.stream().map(WalletOutboxMessage::getId).toList(),
                            WalletOutboxMessage.Status.PROCESSING);
                }
                return claimed;
            });
            if (rows == null || rows.isEmpty()) {
                // nothing to process
                break;
            }
            LOGGER.info("publishing {} wallet outbox entries", rows.size());
            var batchResult = publishBatch(rows);
            if (batchResult == null) {
                // execution was aborted; stuck rows are reset by reclaimProcessing
                break;
            }
            acknowledgeBatch(rows, batchResult);

            LOGGER.info("acknowledged {} published and {} failed",
                    batchResult.published().size(),
                    batchResult.failed().size());
            if (batchResult.batchFailed()) {
                // whole batch failed: broker down, disable processing for a while
                publishAfter = Instant.now().plus(retryDelay);
                LOGGER.warn("outbox publishing paused until {}", publishAfter);
                break;
            }

            if (batchResult.hasFails()) {
                // failed rows wait for the next poll
                break;
            }
        } while (rows.size() == batchSize);
    }

    /**
     * Publish batch on a single channel: AMQP guarantees publish order per channel only,
     * and confirm channels are not reused while their confirms are pending
     */
    @Nullable
    private PublishBatchResult publishBatch(List<WalletOutboxMessage> rows) {
        var futures = new HashMap<UUID, CompletableFuture<PublishResult>>();
        try {
            rabbitTemplate.invoke(ops -> {
                for (var row : rows) {
                    futures.put(row.getTransactionId(), publishRabbitMQ(ops, row));
                }
                return null;
            });
        } catch (RuntimeException e) {
            // send failed (e.g. broker unreachable): rows without a result are marked failed;
            // putIfAbsent keeps the results of rows already sent before the exception
            for (var row : rows) {
                var failedResult = PublishResult.failed(row.getTransactionId(), errorText(e));
                var failedFuture = CompletableFuture.completedFuture(failedResult);
                futures.putIfAbsent(row.getTransactionId(), failedFuture);
            }
        }

        // wait batch to be published
        try {
            CompletableFuture.allOf(futures.values().toArray(new CompletableFuture[0]))
                    .get(batchPublishTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // early exit, because was interrupted
            return null;
        } catch (ExecutionException e) {
            LOGGER.warn("publisher confirms did not arrive within {}", batchPublishTimeout, e);
        } catch (TimeoutException e) {
            LOGGER.warn("publisher confirms did not arrive within {}", batchPublishTimeout);
        }

        // process results; futures that never completed are reported as missing confirms
        var published = new ArrayList<UUID>();
        var failed = new HashMap<UUID, String>();
        for (var entry : futures.entrySet()) {
            var transactionId = entry.getKey();
            var result = entry.getValue().getNow(null);
            if (result != null && result.published()) {
                published.add(transactionId);
            } else if (result != null) {
                failed.put(transactionId, result.error());
            } else {
                failed.put(transactionId, "no publisher confirm within " + batchPublishTimeout);
            }
        }

        return new PublishBatchResult(published, failed);
    }

    private CompletableFuture<PublishResult> publishRabbitMQ(RabbitOperations ops, WalletOutboxMessage row) {
        var transactionId = row.getTransactionId();
        var correlation = new CorrelationData(transactionId.toString());

        try {
            var event = toEvent(row);
            ops.convertAndSend(WalletAmqpConfiguration.EXCHANGE, WalletAmqpConfiguration.ROUTING_KEY, event, correlation);
        } catch (RuntimeException e) {
            return CompletableFuture.completedFuture(PublishResult.failed(transactionId, errorText(e)));
        }

        return correlation.getFuture()
                .handle((confirm, error) -> toPublishResult(transactionId, confirm, error, correlation));
    }

    /**
     * Handle batch result: delete published events and set failed rows back to PENDING
     * so the next poll retries them
     */
    private void acknowledgeBatch(List<WalletOutboxMessage> rows, PublishBatchResult result) {
        txTemplate.executeWithoutResult(status -> {
            if (!result.published().isEmpty()) {
                outboxRepository.deleteByTransactionIds(result.published());
            }
            // per-row update: last_error carries the failure reason, which differs per row
            result.failed().forEach((id, error) ->
                    outboxRepository.markFailed(id, error, WalletOutboxMessage.Status.PENDING));
        });
        for (var row : rows) {
            var error = result.failed().get(row.getTransactionId());
            if (error != null && row.getAttempts() + 1 >= maxAttempts) {
                LOGGER.warn("done with outbox row {}, leaving it in wallet_outbox as-is: {}",
                        row.getTransactionId(), error);
            }
        }
    }

    private WalletUpdatedEvent toEvent(WalletOutboxMessage row) {
        return new WalletUpdatedEvent(
                row.getTransactionId(),
                row.getWalletId(),
                row.getAmount(),
                row.getSeq(),
                row.getCreatedAt()
        );
    }

    private static PublishResult toPublishResult(
            UUID transactionId, CorrelationData.Confirm confirm, Throwable error, CorrelationData correlation) {
        if (error != null) {
            return PublishResult.failed(transactionId, errorText(error));
        }
        if (confirm == null) {
            return PublishResult.failed(transactionId, "null confirm");
        }
        if (!confirm.ack()) {
            return PublishResult.failed(transactionId,
                    "not acknowledged: " + Objects.toString(confirm.reason(), "no reason"));
        }
        // Requires mandatory=true (spring.rabbitmq.template.mandatory) for the broker to return unroutable messages.
        var returned = correlation.getReturned();
        if (returned != null) {
            return PublishResult.failed(transactionId,
                    "returned: " + returned.getReplyText());
        }
        return PublishResult.ok(transactionId);
    }

    private static String errorText(Throwable error) {
        return Objects.toString(error.getMessage(), error.getClass().getName());
    }
}
