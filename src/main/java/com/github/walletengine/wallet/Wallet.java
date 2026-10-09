package com.github.walletengine.wallet;

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

/**
 * Wallet - used as current-state projection of a wallet balance.
 */
@Entity
@Table(name = "wallets")
public class Wallet implements Persistable<UUID> {

    @Id
    @Column(name = "wallet_id", nullable = false)
    private UUID walletId;

    @Column(name = "amount", nullable = false)
    private long amount;

    @CreationTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Makes {@code save} persist instead of merge: with an assigned id, Spring Data
     * cannot tell a new entity from a detached one, and merge would silently reset the
     * balance of an existing wallet.
     */
    @Transient
    private boolean isNew;

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.isNew = false;
    }

    protected Wallet() {
        // for JPA
    }

    public Wallet(UUID walletId) {
        this.walletId = walletId;
        this.amount = 0L;
        this.isNew = true;
    }

    @Override
    public UUID getId() {
        return walletId;
    }

    @Override
    public boolean isNew() {
        return isNew;
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
