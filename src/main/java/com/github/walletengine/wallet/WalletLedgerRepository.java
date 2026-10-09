package com.github.walletengine.wallet;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface WalletLedgerRepository extends JpaRepository<WalletLedgerEntry, UUID> {

    /**
     * Transactions after the stored cursor, ordered so the position stays unambiguous
     * when timestamps collide. The cursor table has exactly one row, so the join is
     * just a filter — and a null cursor needs no special case, because the comparison
     * runs against a column, not an untyped parameter.
     */
    @Query("""
            select e from WalletLedgerEntry e
              join PublisherState s on s.id = 1
             where s.lastProcessedAt is null
                or e.updatedAt > s.lastProcessedAt
                or (e.updatedAt = s.lastProcessedAt and e.transactionId > s.lastTransactionId)
             order by e.updatedAt asc, e.transactionId asc
            """)
    List<WalletLedgerEntry> findPending(Pageable page);
}
