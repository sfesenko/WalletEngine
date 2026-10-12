package com.github.walletengine.wallet;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface WalletRepository extends JpaRepository<Wallet, UUID> {

    /**
     * Update wallet's balance. Ensure that balance is positive
     * @return either 1 if update was successful or 0 if update amount is < 0 or wallet is missing
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Wallet w
               set w.amount = w.amount + :amount,
                   w.updatedAt = CURRENT_TIMESTAMP
             where w.walletId = :walletId
               and w.amount + :amount >= 0
            """)
    int updateWallet(UUID walletId, long amount);
}
