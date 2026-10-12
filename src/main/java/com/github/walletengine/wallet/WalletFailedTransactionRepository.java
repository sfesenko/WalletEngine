package com.github.walletengine.wallet;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface WalletFailedTransactionRepository extends JpaRepository<WalletFailedTransaction, UUID> {
}
