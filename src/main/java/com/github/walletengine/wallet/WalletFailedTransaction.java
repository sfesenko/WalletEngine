package com.github.walletengine.wallet;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Immutable;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.UUID;

/**
 * Failed transaction
 */
@Immutable
@Entity
@Table(name = "rejected_movements")
public class WalletFailedTransaction implements Persistable<UUID> {

    public enum Error {
        INSUFFICIENT_FUNDS, WALLET_NOT_FOUND
    }

    @Id
    @Column(name = "transaction_id", nullable = false)
    private UUID transactionId;

    @Column(name = "wallet_id", nullable = false)
    private UUID walletId;

    @Column(name = "amount", nullable = false)
    private long amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "error_code", nullable = false)
    private Error error;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /**
     * Makes {@code save} persist instead of merge — see {@link WalletTransaction}.
     */
    @Transient
    private boolean isNew;

    protected WalletFailedTransaction() {
        // for JPA
    }

    public WalletFailedTransaction(UUID transactionId, UUID walletId, long amount, Error error) {
        this.transactionId = transactionId;
        this.walletId = walletId;
        this.amount = amount;
        this.error = error;
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

    public Error getError() {
        return error;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
