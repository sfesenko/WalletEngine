package com.github.walletengine.wallet;

import com.github.walletengine.publisher.WalletOutboxMessageRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Verify strict idempotency ({@code wallet.idempotency.strict=true}): a duplicate request for a
 * rejected movement returns the stored rejection without re-executing the movement.
 */
@SpringBootTest(properties = "wallet.idempotency.strict=true")
@ActiveProfiles("test")
class WalletIdempotencyStrictTest {

    @Autowired
    private WalletService walletService;

    @Autowired
    private WalletTransactionRepository transactionRepository;

    @Autowired
    private WalletFailedTransactionRepository failedTransactionRepository;

    @Autowired
    private WalletOutboxMessageRepository outboxRepository;

    @Test
    void rejectedMovementIsNotReexecutedAfterFunding() {
        var walletId = UUID.randomUUID();
        var transactionId = UUID.randomUUID();

        walletService.createWallet(walletId);
        walletService.credit(UUID.randomUUID(), walletId, 1_000L);

        assertThatThrownBy(() -> walletService.debit(transactionId, walletId, 2_000L))
                .isInstanceOf(InsufficientFundsException.class);

        assertThat(failedTransactionRepository.findById(transactionId)).isPresent();

        // funds arrive: a re-executing retry would now succeed and move money
        walletService.credit(UUID.randomUUID(), walletId, 2_000L);

        assertThatThrownBy(() -> walletService.debit(transactionId, walletId, 2_000L))
                .isInstanceOf(InsufficientFundsException.class);

        assertThat(walletService.getWalletById(walletId).getAmount()).isEqualTo(3_000L);
        assertThat(transactionRepository.findById(transactionId)).isEmpty();
        assertThat(outboxRepository.findById(transactionId)).isEmpty();
    }

    @Test
    void missingWalletRejectionIsReplayed() {
        var walletId = UUID.randomUUID();
        var transactionId = UUID.randomUUID();

        assertThatThrownBy(() -> walletService.debit(transactionId, walletId, 100L))
                .isInstanceOf(WalletNotFoundException.class);

        // wallet appears: a re-executing retry would now succeed
        walletService.createWallet(walletId);
        walletService.credit(UUID.randomUUID(), walletId, 500L);

        assertThatThrownBy(() -> walletService.debit(transactionId, walletId, 100L))
                .isInstanceOf(WalletNotFoundException.class);

        assertThat(walletService.getWalletById(walletId).getAmount()).isEqualTo(500L);
    }

    @ParameterizedTest
    @CsvSource(value = {
            "false | 100",
            "true  | 200",
    }, delimiter = '|')
    void rejectedTransactionReplayedWithConflictThrows(boolean replayAgainstStoredWallet, long replayAmount) {
        var walletId = UUID.randomUUID();
        var otherWalletId = UUID.randomUUID();
        var transactionId = UUID.randomUUID();

        walletService.createWallet(walletId);
        walletService.createWallet(otherWalletId);
        walletService.credit(UUID.randomUUID(), otherWalletId, 500L);

        assertThatThrownBy(() -> walletService.debit(transactionId, walletId, 100L))
                .isInstanceOf(InsufficientFundsException.class);

        // same transaction id with a different amount or wallet is a conflict, not a replay
        var replayWalletId = replayAgainstStoredWallet ? walletId : otherWalletId;
        var failure = catchThrowable(() -> walletService.debit(transactionId, replayWalletId, replayAmount));

        assertThat(failure).isInstanceOf(IllegalTransactionException.class);
        // only the request's transaction_id may be exposed: the stored wallet / amount must not leak
        assertThat(failure.getMessage())
                .contains(transactionId.toString())
                .doesNotContain(walletId.toString());
        assertThat(walletService.getWalletById(otherWalletId).getAmount()).isEqualTo(500L);
    }

    @Test
    void zeroAmountRejectionIsNotRecorded() {
        var walletId = UUID.randomUUID();
        var transactionId = UUID.randomUUID();

        walletService.createWallet(walletId);

        assertThatThrownBy(() -> walletService.debit(transactionId, walletId, 0L))
                .isInstanceOf(IllegalArgumentException.class);

        // input-validity rejections are state-independent: same error on retry, no record
        assertThat(failedTransactionRepository.findById(transactionId)).isEmpty();

        assertThatThrownBy(() -> walletService.debit(transactionId, walletId, 0L))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(walletService.getWalletById(walletId).getAmount()).isZero();
    }
}
