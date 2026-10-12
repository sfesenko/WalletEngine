package com.github.walletengine.wallet;

import org.springframework.data.annotation.Id;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Wallet - used as current-state projection of a wallet balance.
 */
@Table("wallets")
public class Wallet implements Persistable<UUID> {

    @Id
    private UUID walletId;

    private long amount;

    private Instant updatedAt;

    protected Wallet() {
        // for Spring Data
    }

    public Wallet(UUID walletId) {
        this.walletId = walletId;
        this.amount = 0L;
        this.updatedAt = Instant.now();
    }

    @Override
    public UUID getId() {
        return walletId;
    }

    /**
     * Always an insert: a wallet row is created once, and balance changes go through the
     * guarded {@code updateWallet} query. With an assigned id Spring Data cannot tell a new
     * row from an existing one — the natural primary key is the duplicate guard.
     */
    @Override
    public boolean isNew() {
        return true;
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
