package com.github.walletengine.publisher;

import com.github.walletengine.TestFixture;
import com.github.walletengine.wallet.WalletService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Checks the outbox queries against the real database (Docker Compose infrastructure,
 * {@code test} profile). Rows are filtered to the transaction ids each test creates,
 * because the shared database holds other tests' rows.
 */
@SpringBootTest
@ActiveProfiles("test")
class WalletOutboxMessageRepositoryTest {

    private static final int MAX_ATTEMPTS = 10;
    private static final int ALL_ROWS = 10_000;
    private static final int PROCESSING_TIMEOUT_MINUTES = 30;

    @Autowired
    private WalletService walletService;

    @Autowired
    private WalletOutboxMessageRepository outboxRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void pendingRowsComeBackInSeqOrderAndDisappearAfterDelete() {
        var walletId = UUID.randomUUID();
        var first = UUID.randomUUID();
        var second = UUID.randomUUID();

        walletService.createWallet(walletId);
        walletService.credit(first, walletId, 100L);
        walletService.credit(second, walletId, 200L);

        var rows = claimPending().stream()
                .filter(row -> List.of(first, second).contains(row.getTransactionId()))
                .toList();

        assertThat(rows).hasSize(2);
        assertThat(rows.getFirst().getSeq()).isLessThan(rows.getLast().getSeq());

        var deleted = outboxRepository.deleteByTransactionIds(List.of(first, second));

        assertThat(deleted).isEqualTo(2);
        assertThat(outboxRepository.findById(first)).isEmpty();
        assertThat(outboxRepository.findById(second)).isEmpty();
    }

    @ParameterizedTest
    @CsvSource(value = {
            "1  | true ",
            "10 | false",
    }, delimiter = '|')
    void failedRowsStayClaimableUntilTheAttemptLimit(int failures, boolean claimable) {
        var freshId = TestFixture.creditFreshWallet(walletService, 100L);
        var failingId = TestFixture.creditFreshWallet(walletService, 200L);

        for (var i = 0; i < failures; i++) {
            var updated = outboxRepository.markFailed(failingId, "failure " + (i + 1), WalletOutboxMessage.Status.PENDING);

            assertThat(updated).isEqualTo(1);
        }

        var row = outboxRepository.findById(failingId).orElseThrow();

        assertThat(row.getAttempts()).isEqualTo(failures);
        assertThat(row.getLastError()).isNotBlank();
        assertThat(row.getStatus()).isEqualTo(WalletOutboxMessage.Status.PENDING);

        if (claimable) {
            assertThat(claimableAmong(List.of(freshId, failingId)))
                    .containsExactlyInAnyOrder(freshId, failingId);
        } else {
            assertThat(claimableAmong(List.of(freshId, failingId))).containsExactly(freshId);
        }
    }

    @Test
    void markProcessingMovesClaimedRowsToProcessing() {
        var first = TestFixture.creditFreshWallet(walletService, 100L);
        var second = TestFixture.creditFreshWallet(walletService, 200L);

        var updated = outboxRepository.markProcessing(List.of(first, second), WalletOutboxMessage.Status.PROCESSING);

        assertThat(updated).isEqualTo(2);
        assertThat(outboxRepository.findById(first).orElseThrow().getStatus())
                .isEqualTo(WalletOutboxMessage.Status.PROCESSING);
        assertThat(outboxRepository.findById(second).orElseThrow().getStatus())
                .isEqualTo(WalletOutboxMessage.Status.PROCESSING);
        assertThat(claimableAmong(List.of(first, second))).isEmpty();
    }

    @Test
    void resetStaleProcessingOnlyTouchesRowsOlderThanTheTimeout() {
        var staleId = TestFixture.creditFreshWallet(walletService, 100L);
        var freshId = TestFixture.creditFreshWallet(walletService, 200L);

        jdbcTemplate.update("""
                update wallet_outbox
                   set status = 'PROCESSING', created_at = now() - interval '2 hours'
                 where transaction_id = ?
                """, staleId);
        jdbcTemplate.update("""
                update wallet_outbox
                   set status = 'PROCESSING'
                 where transaction_id = ?
                """, freshId);

        outboxRepository.resetStaleProcessing(PROCESSING_TIMEOUT_MINUTES);

        assertThat(outboxRepository.findById(staleId).orElseThrow().getStatus())
                .isEqualTo(WalletOutboxMessage.Status.PENDING);
        assertThat(outboxRepository.findById(freshId).orElseThrow().getStatus())
                .isEqualTo(WalletOutboxMessage.Status.PROCESSING);

        outboxRepository.deleteByTransactionIds(List.of(staleId, freshId));
    }

    private List<WalletOutboxMessage> claimPending() {
        return outboxRepository.claim(WalletOutboxMessage.Status.PENDING.name(), MAX_ATTEMPTS - 1, ALL_ROWS);
    }

    private List<UUID> claimableAmong(List<UUID> mine) {
        return claimPending().stream()
                .map(WalletOutboxMessage::getTransactionId)
                .filter(mine::contains)
                .toList();
    }
}
