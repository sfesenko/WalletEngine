package com.github.walletengine.wallet;

import org.springframework.data.annotation.Id;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Failed transaction
 */
@Table("rejected_movements")
public class WalletFailedTransaction implements Persistable<UUID> {

    public enum Error {
        INSUFFICIENT_FUNDS, WALLET_NOT_FOUND
    }

    @Id
    private UUID transactionId;

    private UUID walletId;

    private long amount;

    @Column("error_code")
    private Error error;

    private Instant createdAt;

    protected WalletFailedTransaction() {
        // for Spring Data
    }

    public WalletFailedTransaction(UUID transactionId, UUID walletId, long amount, Error error) {
        this.transactionId = transactionId;
        this.walletId = walletId;
        this.amount = amount;
        this.error = error;
        this.createdAt = Instant.now();
    }

    @Override
    public UUID getId() {
        return transactionId;
    }

    /**
     * Always an insert: rejected movements are written once and never updated.
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

    public Error getError() {
        return error;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
