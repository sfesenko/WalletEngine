package com.github.walletengine.wallet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Checks wallet movements ({@code debit}, {@code credit}) against the real database
 * (Docker Compose infrastructure, {@code test} profile).
 */
@SpringBootTest
@ActiveProfiles("test")
class WalletMovementTest {

    @Autowired
    private WalletService walletService;

    @Autowired
    private WalletLedgerRepository ledgerRepository;

    @ParameterizedTest
    @CsvSource(value = {
            "credit | 0   | 500 | 500 |  500",
            "debit  | 500 | 200 | 300 | -200",
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

        var entry = switch (operation) {
            case "debit" -> walletService.debit(transactionId, walletId, amount);
            case "credit" -> walletService.credit(transactionId, walletId, amount);
            default -> throw new IllegalArgumentException("unknown operation: " + operation);
        };

        assertThat(entry.getTransactionId()).isEqualTo(transactionId);
        assertThat(entry.getWalletId()).isEqualTo(walletId);
        assertThat(entry.getAmount()).isEqualTo(expectedStored);

        var wallet = walletService.getWallet(walletId);

        assertThat(wallet.getAmount()).isEqualTo(expectedBalance);
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

        var wallet = walletService.getWallet(walletId);

        assertThat(wallet.getAmount()).isEqualTo(500L);

        var rows = ledgerRepository.findByTransactionId(transactionId);

        assertThat(rows).isPresent();
    }

    @Test
    void debitInsufficientFundsThrowsAndWritesNoLedgerRow() {
        var walletId = UUID.randomUUID();
        var transactionId = UUID.randomUUID();

        walletService.createWallet(walletId);

        assertThatThrownBy(() -> walletService.debit(transactionId, walletId, 100L))
                .isInstanceOf(InsufficientFundsException.class);

        var wallet = walletService.getWallet(walletId);

        assertThat(wallet.getAmount()).isZero();

        var rows = ledgerRepository.findByTransactionId(transactionId);

        assertThat(rows).isEmpty();
    }

    @Test
    void debitUnknownWalletThrowsAndWritesNoLedgerRow() {
        var walletId = UUID.randomUUID();
        var transactionId = UUID.randomUUID();

        assertThatThrownBy(() -> walletService.debit(transactionId, walletId, 100L))
                .isInstanceOf(WalletNotFoundException.class);

        var rows = ledgerRepository.findByTransactionId(transactionId);

        assertThat(rows).isEmpty();
    }

    @ParameterizedTest
    @CsvSource(value = {
            "debit  | 0",
            "debit  | -100",
            "credit | 0",
            "credit | -100"
    }, delimiter = '|')
    void movementRejectsNonPositiveAmount(String operation, long amount) {
        var walletId = UUID.randomUUID();
        var transactionId = UUID.randomUUID();

        walletService.createWallet(walletId);

        switch (operation) {
            case "debit" -> assertThatThrownBy(() -> walletService.debit(transactionId, walletId, amount))
                    .isInstanceOf(IllegalArgumentException.class);
            case "credit" -> assertThatThrownBy(() -> walletService.credit(transactionId, walletId, amount))
                    .isInstanceOf(IllegalArgumentException.class);
            default -> throw new IllegalArgumentException("unknown operation: " + operation);
        }
    }

}
