package com.github.walletengine.wallet;

import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface WalletRepository extends ListCrudRepository<Wallet, UUID> {

    /**
     * Update wallet's balance. Ensure that balance is positive
     * @return either 1 if update was successful or 0 if update amount is < 0 or wallet is missing
     */
    @Modifying
    @Query("""
            update wallets
               set amount = amount + :amount,
                   updated_at = CURRENT_TIMESTAMP
             where wallet_id = :walletId
               and amount + :amount >= 0
            """)
    int updateWallet(UUID walletId, long amount);
}
