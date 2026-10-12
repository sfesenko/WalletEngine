package com.github.walletengine.publisher;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.ReadOnlyProperty;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

@Table("wallet_outbox")
public class WalletOutboxMessage implements Persistable<UUID> {

    public enum Status {
        PENDING, PROCESSING
    }

    @Id
    private UUID transactionId;

    private UUID walletId;

    private long amount;

    /** bigserial assigned by the database; read on load, never written. */
    @ReadOnlyProperty
    private long seq;

    private Status status;

    private int attempts;

    private String lastError;

    private Instant createdAt;

    protected WalletOutboxMessage() {
        // for Spring Data
    }

    public WalletOutboxMessage(UUID transactionId, UUID walletId, long amount) {
        this.transactionId = transactionId;
        this.walletId = walletId;
        this.amount = amount;
        this.status = Status.PENDING;
        this.createdAt = Instant.now();
    }

    @Override
    public UUID getId() {
        return transactionId;
    }

    /**
     * Always an insert: outbox rows are created once; status/attempt changes go through the
     * guarded modifying queries, and the primary key guards duplicates.
     */
    @Override
    public boolean isNew() {
        return true;
    }

    public UUID getTransactionId() {
        return transactionId;
    }

    public UUID getWalletId() {
        return walletId;
    }

    public long getAmount() {
        return amount;
    }

    public long getSeq() {
        return seq;
    }

    public Status getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public String getLastError() {
        return lastError;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
