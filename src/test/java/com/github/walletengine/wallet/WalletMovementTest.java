package com.github.walletengine.wallet;

import com.github.walletengine.publisher.WalletOutboxMessageRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Checks wallet lifecycle and movements ({@code debit}, {@code credit}) against
 * the real database (Docker Compose infrastructure, {@code test} profile).
 */
@SpringBootTest
@ActiveProfiles("test")
class WalletMovementTest {

    private static final UUID SEEDED_WALLET_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Autowired
    private WalletService walletService;

    @Autowired
    private WalletTransactionRepository ledgerRepository;

    @Autowired
    private WalletOutboxMessageRepository outboxRepository;

    @ParameterizedTest
    @CsvSource(value = {
            "credit | 0    | 500  | 500 |  500",
            "debit  | 500  | 200  | 300 | -200",
            "debit  | 1000 | 1000 | 0   | -1000",
    }, delimiter = '|')
    void movementAppliesAmountAndWritesLedgerRow(
            String operation, long funded, long amount, long expectedBalance, long expectedStored) {
        var walletId = UUID.randomUUID();
        var fundingId = UUID.randomUUID();
        var transactionId = UUID.randomUUID();

        walletService.createWallet(walletId);
        if (funded > 0) {
            walletService.credit(fundingId, walletId, funded);
        }

        var entry = switch (operation.strip()) {
            case "debit" -> walletService.debit(transactionId, walletId, amount);
            case "credit" -> walletService.credit(transactionId, walletId, amount);
            default -> throw new IllegalArgumentException("unknown operation: " + operation);
        };

        assertThat(entry.getTransactionId()).isEqualTo(transactionId);
        assertThat(entry.getWalletId()).isEqualTo(walletId);
        assertThat(entry.getAmount()).isEqualTo(expectedStored);

        var wallet = walletService.getWalletById(walletId);

        assertThat(wallet.getAmount()).isEqualTo(expectedBalance);

        var queued = outboxRepository.findById(transactionId).orElseThrow();

        assertThat(queued.getWalletId()).isEqualTo(walletId);
        assertThat(queued.getAmount()).isEqualTo(expectedStored);
    }

    @ParameterizedTest
    @CsvSource(value = {
            "debit  | true  | 0 | 100 | InsufficientFundsException       ",
            "debit  | true  | 0 | 0   | DataIntegrityViolationException  ",
            "credit | true  | 0 | 0   | DataIntegrityViolationException  ",
            "debit  | false | 0 | 100 | WalletNotFoundException          ",
    }, delimiter = '|')
    void movementErrorIsRejected(String operation, boolean createWallet, long funded, long amount,
            String exception) {
        var walletId = UUID.randomUUID();
        var transactionId = UUID.randomUUID();

        if (createWallet) {
            walletService.createWallet(walletId);
            if (funded > 0) {
                walletService.credit(UUID.randomUUID(), walletId, funded);
            }
        }

        var expected = switch (exception.strip()) {
            case "InsufficientFundsException" -> InsufficientFundsException.class;
            case "DataIntegrityViolationException" -> DataIntegrityViolationException.class;
            case "WalletNotFoundException" -> WalletNotFoundException.class;
            default -> throw new IllegalArgumentException("unknown exception: " + exception);
        };

        var op = operation.strip();
        assertThatThrownBy(() -> {
            if (op.equals("debit")) {
                walletService.debit(transactionId, walletId, amount);
            } else {
                walletService.credit(transactionId, walletId, amount);
            }
        }).isInstanceOf(expected);

        assertThat(ledgerRepository.findById(transactionId)).isEmpty();
        assertThat(outboxRepository.findById(transactionId)).isEmpty();

        if (createWallet) {
            assertThat(walletService.getWalletById(walletId).getAmount()).isEqualTo(funded);
        }
    }

    @ParameterizedTest
    @CsvSource(value = {
            "otherWallet     | 500 | 500",
            "differentAmount | 500 | 600",
    }, delimiter = '|')
    void replayingTransactionWithConflictThrows(String conflict, long firstAmount, long secondAmount) {
        var walletId = UUID.randomUUID();
        var otherWalletId = UUID.randomUUID();
        var transactionId = UUID.randomUUID();

        walletService.createWallet(walletId);
        walletService.createWallet(otherWalletId);
        walletService.credit(transactionId, walletId, firstAmount);

        var conflictingWallet = conflict.strip().equals("otherWallet") ? otherWalletId : walletId;

        assertThatThrownBy(() -> walletService.credit(transactionId, conflictingWallet, secondAmount))
                .isInstanceOf(IllegalTransactionException.class);

        assertThat(walletService.getWalletById(walletId).getAmount()).isEqualTo(firstAmount);
        if (conflictingWallet.equals(otherWalletId)) {
            assertThat(walletService.getWalletById(otherWalletId).getAmount()).isZero();
        }
    }

    @Test
    void duplicateTransactionReturnsStoredRowWithoutMovingBalanceTwice() {
        var walletId = UUID.randomUUID();
        var transactionId = UUID.randomUUID();

        walletService.createWallet(walletId);

        var first = walletService.credit(transactionId, walletId, 500L);
        var second = walletService.credit(transactionId, walletId, 500L);

        assertThat(second.getTransactionId()).isEqualTo(first.getTransactionId());
        assertThat(second.getAmount()).isEqualTo(first.getAmount());

        var wallet = walletService.getWalletById(walletId);

        assertThat(wallet.getAmount()).isEqualTo(500L);

        var rows = ledgerRepository.findById(transactionId);

        assertThat(rows).isPresent();

        // the duplicate returned the stored row without queueing a second event
        assertThat(outboxRepository.findById(transactionId)).isPresent();
    }

    @Test
    void getBalanceReturnsSeededWallet() {
        var wallet = walletService.getWalletById(SEEDED_WALLET_ID);

        assertThat(wallet.getWalletId()).isEqualTo(SEEDED_WALLET_ID);
        assertThat(wallet.getAmount()).isEqualTo(100_000L);
    }

    @Test
    void getBalanceUnknownWalletThrows() {
        var unknownWalletId = UUID.randomUUID();

        assertThatThrownBy(() -> walletService.getWalletById(unknownWalletId))
                .isInstanceOf(WalletNotFoundException.class);
    }

    @ParameterizedTest
    @CsvSource(value = {
            "fresh     | false | null                         ",
            "duplicate | true  | WalletAlreadyExistsException ",
    }, delimiter = '|', nullValues = "null")
    void createWalletLifecycle(String scenario, boolean preCreate, String exception) {
        var walletId = UUID.randomUUID();

        if (preCreate) {
            walletService.createWallet(walletId);
        }

        if (exception == null || exception.isBlank()) {
            var wallet = walletService.createWallet(walletId);

            assertThat(wallet.getWalletId()).isEqualTo(walletId);
            assertThat(wallet.getAmount()).isZero();
            assertThat(wallet.getUpdatedAt()).isNotNull();
        } else {
            assertThatThrownBy(() -> walletService.createWallet(walletId))
                    .isInstanceOf(WalletAlreadyExistsException.class);
        }
    }
}
