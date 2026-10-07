package com.github.walletengine.publisher;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** maps to `publisher_state` — the event publisher's cursor into the transactions log. */
@Entity
@Table(name = "publisher_state")
public class PublisherState {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private Integer id;

    @Column(name = "last_processed_at")
    private Instant lastProcessedAt;

    @Column(name = "last_transaction_id")
    private UUID lastTransactionId;

    protected PublisherState() {
        // for JPA
    }

    public Integer getId() {
        return id;
    }

    public Instant getLastProcessedAt() {
        return lastProcessedAt;
    }

    public UUID getLastTransactionId() {
        return lastTransactionId;
    }
}
