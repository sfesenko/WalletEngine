package com.github.walletengine.wallet;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Immutable;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.UUID;

/**
 * Append-only transactions log, one row per movement.
 * Invariant: `wallets.amount` must equal `sum(amount)` over all entries for that wallet.
 */
@Immutable
@Entity
@Table(name = "wallet_ledger")
public class WalletTransaction implements Persistable<UUID> {

    @Id
    @Column(name = "transaction_id", nullable = false)
    private UUID transactionId;

    @Column(name = "wallet_id", nullable = false)
    private UUID walletId;

    /** signed: positive = deposit (Credit), negative = withdrawal (Debit) */
    @Column(name = "amount", nullable = false)
    private long amount;

    @CreationTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Makes {@code save} persist instead of merge: with an assigned id, Spring Data
     * cannot tell a new entry from a detached one, and merge would never hit the
     * primary key that guards idempotency.
     */
    @Transient
    private boolean isNew;

    protected WalletTransaction() {
        // for JPA
    }

    public WalletTransaction(UUID transactionId, UUID walletId, long amount) {
        this.transactionId = transactionId;
        this.walletId = walletId;
        this.amount = amount;
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

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
