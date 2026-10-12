package com.github.walletengine.publisher;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.hibernate.annotations.CreationTimestamp;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "wallet_outbox")
public class WalletOutboxMessage implements Persistable<UUID> {

    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_PROCESSING = "processing";

    @Id
    @Column(name = "transaction_id", nullable = false)
    private UUID transactionId;

    @Column(name = "wallet_id", nullable = false)
    private UUID walletId;

    @Column(name = "amount", nullable = false)
    private long amount;

    @Column(name = "seq", nullable = false, insertable = false, updatable = false)
    private long seq;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "last_error")
    private String lastError;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Transient
    private boolean isNew;

    protected WalletOutboxMessage() {
        // for JPA
    }

    public WalletOutboxMessage(UUID transactionId, UUID walletId, long amount) {
        this.transactionId = transactionId;
        this.walletId = walletId;
        this.amount = amount;
        this.status = STATUS_PENDING;
        this.isNew = true;
    }

    @Override
    public UUID getId() {
        return transactionId;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.isNew = false;
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

    public String getStatus() {
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
