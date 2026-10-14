package com.github.walletengine.publisher;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
@Transactional
public interface WalletOutboxMessageRepository extends JpaRepository<WalletOutboxMessage, UUID> {

    // Claim rows for one publisher batch; parallel instances skip rows locked by a peer.
    @Query(value = """
            select * from wallet_outbox
             where status = :status
               and attempts < :maxAttempts
             order by seq asc
             limit :size
             for update skip locked
            """, nativeQuery = true)
    List<WalletOutboxMessage> claim(
            @Param("status") String status,
            @Param("maxAttempts") int maxAttempts,
            @Param("size") int size);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update WalletOutboxMessage e
               set e.status = :status,
                   e.claimedAt = CURRENT_TIMESTAMP
             where e.transactionId in :ids
            """)
    int markProcessing(@Param("ids") Collection<UUID> ids, @Param("status") WalletOutboxMessage.Status status);

    // Keyed on the claim timestamp: a row is stale only after it has been claimed for the
    // timeout. Rows claimed before the column existed fall back to their insertion time.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            update wallet_outbox
               set status = 'PENDING'
             where status = 'PROCESSING'
               and (now() - coalesce(claimed_at, created_at)) > (:timeoutMinutes * interval '1 minute')
            """, nativeQuery = true)
    int resetStaleProcessing(@Param("timeoutMinutes") long timeoutMinutes);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from WalletOutboxMessage e where e.transactionId in :ids")
    int deleteByTransactionIds(@Param("ids") Collection<UUID> ids);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update WalletOutboxMessage e
               set e.attempts = e.attempts + 1,
               e.lastError = :lastError,
               e.status = :status
             where e.transactionId = :id
            """)
    int markFailed(
            @Param("id") UUID transactionId,
            @Param("lastError") String lastError,
            @Param("status") WalletOutboxMessage.Status status);
}
