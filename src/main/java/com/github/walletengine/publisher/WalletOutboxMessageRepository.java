package com.github.walletengine.publisher;

import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
@Transactional
public interface WalletOutboxMessageRepository extends ListCrudRepository<WalletOutboxMessage, UUID> {

    // Claim rows for one publisher batch; parallel instances skip rows locked by a peer.
    @Query("""
            select * from wallet_outbox
             where status = :status
               and attempts <= :maxAttempts
             order by seq asc
             limit :size
             for update skip locked
            """)
    List<WalletOutboxMessage> claim(
            @Param("status") String status,
            @Param("maxAttempts") int maxAttempts,
            @Param("size") int size);

    @Modifying
    @Query("update wallet_outbox set status = :status where transaction_id in (:ids)")
    int markProcessing(@Param("ids") Collection<UUID> ids, @Param("status") WalletOutboxMessage.Status status);

    @Modifying
    @Query("""
            update wallet_outbox
               set status = 'PENDING'
             where status = 'PROCESSING'
               and (now() - created_at) > (:timeoutMinutes * interval '1 minute')
            """)
    int resetStaleProcessing(@Param("timeoutMinutes") long timeoutMinutes);

    @Modifying
    @Query("delete from wallet_outbox where transaction_id in (:ids)")
    int deleteByTransactionIds(@Param("ids") Collection<UUID> ids);

    @Modifying
    @Query("""
            update wallet_outbox
               set attempts = attempts + 1,
                   last_error = :lastError,
                   status = :status
             where transaction_id = :id
            """)
    int markFailed(@Param("id") UUID transactionId, @Param("lastError") String lastError,
                   @Param("status") WalletOutboxMessage.Status status);
}
