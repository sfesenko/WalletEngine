package com.github.walletengine.publisher;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

@Service
public class WalletOutboxPublisher {

    private static final Logger LOGGER = LoggerFactory.getLogger(WalletOutboxPublisher.class);
    private static final int MAX_ATTEMPTS = 10;

    private final WalletOutboxRepository outboxRepository;
    private final RabbitTemplate rabbitTemplate;
    private final TransactionTemplate txTemplate;
    private final PageRequest batchSize;
    private final Duration batchPublishTimeout;

    public WalletOutboxPublisher(
            WalletOutboxRepository outboxRepository,
            RabbitTemplate rabbitTemplate,
            PlatformTransactionManager txManager,
            @Value("${wallet.outbox.batch-size:100}") int batchSize,
            @Value("${wallet.outbox.batch-publish-timeout:5s}") Duration batchPublishTimeout) {
        this.outboxRepository = outboxRepository;
        this.rabbitTemplate = rabbitTemplate;
        this.txTemplate = new TransactionTemplate(txManager);
        this.batchSize = PageRequest.of(0, batchSize);
        this.batchPublishTimeout = batchPublishTimeout;
    }

    /**
     *
     */
    record PublishResult(
            List<UUID> published,
            Map<UUID, String> failed
    ) {
    }

    @Scheduled(fixedDelayString = "${wallet.outbox.interval:5s}")
    public void publish() {
        List<WalletOutboxEntry> rows;
        do {
            rows = outboxRepository.findPending(batchSize);
            if (rows.isEmpty()) {
                break;
            }
            var result = publishBatch(rows);
            LOGGER.info("published {} wallet outbox entries", rows.size());
            acknowledge(result);
            LOGGER.info("acknowledged {} published and {} failed", result.published.size(), result.failed().size());
        } while (rows.size() == batchSize.getPageSize());
    }

    @Scheduled(fixedDelayString = "${wallet.outbox.retry-interval:30s}")
    public void retryFailed() {
        List<WalletOutboxEntry> rows;
        do {
            rows = outboxRepository.findRetryable(MAX_ATTEMPTS, batchSize);
            if (rows.isEmpty()) {
                break;
            }
            var result = publishBatch(rows);
            LOGGER.info("published {} wallet outbox entries", rows.size());
            acknowledge(result);
            LOGGER.info("acknowledged {} published and {} failed", result.published.size(), result.failed().size());
        } while (rows.size() == batchSize.getPageSize());
    }

    private PublishResult publishBatch(List<WalletOutboxEntry> rows) {
        var published = Collections.synchronizedList(new ArrayList<UUID>());
        var failed = new ConcurrentHashMap<UUID, String>();

        var futures = new ArrayList<CompletableFuture<CorrelationData.Confirm>>();

        for (var row : rows) {
            var transactionId = row.getTransactionId();
            var f = publishMQ(row)
                    .whenComplete((confirm, error) -> {
                        if (error != null) {
                            var errorMessage = Objects.toString(errorText(error, confirm), "unknown");
                            failed.put(transactionId, errorMessage);
                        } else if (confirm.ack()) {
                            published.add(transactionId);
                        } else {
                            failed.put(transactionId, "not acknowledged");
                        }
                    });
            futures.add(f);
        }

        try {
            // wait for all publishers
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(batchPublishTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException | InterruptedException | TimeoutException e) {
            // timeout.
            // move not published events into failed
            var transactionIds = rows.stream()
                    .map(WalletOutboxEntry::getTransactionId)
                    .collect(Collectors.toSet());
            published.forEach(transactionIds::remove);
            failed.keySet().forEach(transactionIds::remove);
            for (var tid : transactionIds) {
                failed.put(tid, "publish timeout");
            }
        }

        return new PublishResult(published, failed);
    }

    private CompletableFuture<CorrelationData.Confirm> publishMQ(WalletOutboxEntry row) {
        var correlation = new CorrelationData(row.getTransactionId().toString());
        try {
            rabbitTemplate.convertAndSend(
                    WalletAmqpConfiguration.EXCHANGE, WalletAmqpConfiguration.ROUTING_KEY, toEvent(row), correlation);
            return correlation.getFuture();
        } catch (AmqpException ex) {
            return CompletableFuture.failedFuture(ex);
        }
    }

    private void acknowledge(PublishResult publishResult) {
        txTemplate.executeWithoutResult(status -> {
            var published = publishResult.published();
            if (!published.isEmpty()) {
                outboxRepository.deleteByTransactionIds(published);
            }
            publishResult.failed().forEach(outboxRepository::markFailed);
        });
    }

    private WalletUpdatedEvent toEvent(WalletOutboxEntry row) {
        return new WalletUpdatedEvent(
                row.getTransactionId(),
                row.getWalletId(),
                row.getAmount(),
                row.getSeq(),
                row.getCreatedAt()
        );
    }

    private static String errorText(Throwable error, CorrelationData.Confirm confirm) {
        if (error != null) {
            return Objects.toString(error.getMessage(), error.getClass().getName());
        } else if (confirm != null) {
            return confirm.reason();
        } else {
            return null;
        }
    }
}
