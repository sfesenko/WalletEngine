package com.github.walletengine;

import com.github.walletengine.publisher.WalletAmqpConfiguration;
import com.github.walletengine.publisher.WalletUpdatedEvent;
import com.github.walletengine.wallet.WalletService;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Shared helpers for functional outbox tests. All tests run against real
 * Postgres + RabbitMQ, so helpers only filter to the caller's own ids —
 * never absolute counts.
 */
    public final class TestFixture {

    private TestFixture() {
    }

    public static UUID creditFreshWallet(WalletService walletService, long amount) {
        var walletId = UUID.randomUUID();
        var transactionId = UUID.randomUUID();

        walletService.createWallet(walletId);
        walletService.credit(transactionId, walletId, amount);

        return transactionId;
    }

    public static List<UUID> creditFreshWallets(WalletService walletService, int count, long amount) {
        var walletId = UUID.randomUUID();

        walletService.createWallet(walletId);

        var transactionIds = new ArrayList<UUID>();
        for (var i = 0; i < count; i++) {
            var transactionId = UUID.randomUUID();
            walletService.credit(transactionId, walletId, amount);
            transactionIds.add(transactionId);
        }

        return transactionIds;
    }

    public static List<WalletUpdatedEvent> drainEvents(RabbitTemplate rabbitTemplate) {
        var events = new ArrayList<WalletUpdatedEvent>();
        var received = receiveEvent(rabbitTemplate);
        while (received != null) {
            events.add(received);
            received = receiveEvent(rabbitTemplate);
        }

        return events;
    }

    public static WalletUpdatedEvent receiveEvent(RabbitTemplate rabbitTemplate) {
        var received = rabbitTemplate.receiveAndConvert(WalletAmqpConfiguration.QUEUE, 1_000L);

        return received instanceof WalletUpdatedEvent event ? event : null;
    }

    public static WalletUpdatedEvent findEvent(List<WalletUpdatedEvent> events, UUID transactionId) {
        for (var event : events) {
            if (event.transactionId().equals(transactionId)) {
                return event;
            }
        }

        return null;
    }
}
