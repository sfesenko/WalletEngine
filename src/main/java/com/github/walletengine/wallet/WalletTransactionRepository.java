package com.github.walletengine.wallet;

import org.springframework.data.repository.ListCrudRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface WalletTransactionRepository extends ListCrudRepository<WalletTransaction, UUID> {
}
