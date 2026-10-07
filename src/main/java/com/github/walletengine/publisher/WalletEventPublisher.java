package com.github.walletengine.publisher;

import com.github.walletengine.wallet.WalletLedgerRepository;
import com.github.walletengine.lock.WalletLockManager;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

/**
 * Background publisher, separate from request processing: reads new transactions from
 * the transactions log since the last processed timestamp and publishes a
 * {@code WalletUpdatedEvent} for each.
 */
@Service
public class WalletEventPublisher {

    private final WalletLedgerRepository ledgerRepository;
    private final PublisherStateRepository publisherStateRepository;
    private final WalletLockManager lockManager;
    private final RabbitTemplate rabbitTemplate;

    public WalletEventPublisher(WalletLedgerRepository ledgerRepository,
                                PublisherStateRepository publisherStateRepository,
                                WalletLockManager lockManager,
                                RabbitTemplate rabbitTemplate) {
        this.ledgerRepository = ledgerRepository;
        this.publisherStateRepository = publisherStateRepository;
        this.lockManager = lockManager;
        this.rabbitTemplate = rabbitTemplate;
    }

    public void publishPendingTransactions() {
        throw new UnsupportedOperationException("not implemented yet");
    }
}
