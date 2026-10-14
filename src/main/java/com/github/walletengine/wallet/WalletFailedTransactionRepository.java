package com.github.walletengine.wallet;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface WalletFailedTransactionRepository extends JpaRepository<WalletFailedTransaction, UUID> {

    /**
     * Record a rejected movement. Racing duplicates of the same transaction may attempt the same
     * insert; the first stored record wins. Must run inside the movement's transaction so the
     * record is committed atomically with the rejection decision.
     */
    @Modifying
    @Query(value = """
            insert into wallet_failed_transactions (transaction_id, wallet_id, amount, error_code)
            values (:transactionId, :walletId, :amount, :errorCode)
            on conflict (transaction_id) do nothing
            """, nativeQuery = true)
    int insertIfAbsent(
            @Param("transactionId") UUID transactionId,
            @Param("walletId") UUID walletId,
            @Param("amount") long amount,
            @Param("errorCode") String errorCode);
}
