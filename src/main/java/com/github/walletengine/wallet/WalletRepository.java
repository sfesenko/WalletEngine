package com.github.walletengine.wallet;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface WalletRepository extends JpaRepository<Wallet, UUID> {

    /** @throws WalletNotFoundException if the wallet does not exist */
    default Wallet getWallet(UUID walletId) {
        return findById(walletId)
                .orElseThrow(() -> new WalletNotFoundException(walletId));
    }

    /**
     * Applies a signed movement to the balance atomically, rejecting it if the result
     * would be negative. Never load-modify-save: that loses updates under concurrency.
     *
     * @return number of rows updated: 1 = applied, 0 = wallet unknown or insufficient funds
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Wallet w
               set w.amount = w.amount + :amount, w.updatedAt = CURRENT_TIMESTAMP
             where w.walletId = :walletId
               and w.amount + :amount >= 0
            """)
    int updateWallet(@Param("walletId") UUID walletId, @Param("amount") long amount);
}
