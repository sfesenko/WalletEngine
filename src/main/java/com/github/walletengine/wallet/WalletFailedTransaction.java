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

    public static final String ERROR_INSUFFICIENT_FUNDS = "insufficient_funds";
    public static final String ERROR_WALLET_NOT_FOUND = "wallet_not_found";

    @Id
    @Column(name = "transaction_id", nullable = false)
    private UUID transactionId;

    @Column(name = "wallet_id", nullable = false)
    private UUID walletId;

    @Column(name = "amount", nullable = false)
    private long amount;

    @Column(name = "error_code", nullable = false)
    private String errorCode;

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

    public WalletFailedTransaction(UUID transactionId, UUID walletId, long amount, String errorCode) {
        this.transactionId = transactionId;
        this.walletId = walletId;
        this.amount = amount;
        this.errorCode = errorCode;
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

    public String getErrorCode() {
        return errorCode;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
