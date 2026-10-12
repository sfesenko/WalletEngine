package com.github.walletengine.wallet;

import org.springframework.data.annotation.Id;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Append-only transactions log, one row per movement.
 * Invariant: `wallets.amount` must equal `sum(amount)` over all entries for that wallet.
 */
@Table("wallet_ledger")
public class WalletTransaction implements Persistable<UUID> {

    @Id
    private UUID transactionId;

    private UUID walletId;

    /** signed: positive = deposit (Credit), negative = withdrawal (Debit) */
    private long amount;

    private Instant updatedAt;

    protected WalletTransaction() {
        // for Spring Data
    }

    public WalletTransaction(UUID transactionId, UUID walletId, long amount) {
        this.transactionId = transactionId;
        this.walletId = walletId;
        this.amount = amount;
        this.updatedAt = Instant.now();
    }

    @Override
    public UUID getId() {
        return transactionId;
    }

    /**
     * Always an insert: ledger rows are written once, the append-only trigger rejects updates,
     * and the primary key guards idempotency.
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

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
