package com.github.walletengine.wallet;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface WalletLedgerRepository extends JpaRepository<WalletLedgerEntry, UUID> {

    /** Duplicate check: a stored row means the request was already applied. */
    Optional<WalletLedgerEntry> findByTransactionId(UUID transactionId);
}
