package com.github.walletengine.publisher;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.UUID;

/** Published to RabbitMQ for every successfully applied movement. */
public record WalletUpdatedEvent(
        @JsonProperty("transaction_id") UUID transactionId,
        @JsonProperty("wallet_id") UUID walletId,
        @JsonProperty("amount") long amount,
        @JsonProperty("seq") long seq,
        @JsonProperty("updated_at") Instant updatedAt) {
}
