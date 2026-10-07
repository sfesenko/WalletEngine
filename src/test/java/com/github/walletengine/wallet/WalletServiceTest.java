package com.github.walletengine.wallet;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Checks wallet lifecycle ({@code getWallet}, {@code createWallet}) against the real
 * database (Docker Compose infrastructure, {@code test} profile).
 */
@SpringBootTest
@ActiveProfiles("test")
class WalletServiceTest  {

    private static final UUID SEEDED_WALLET_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Autowired
    private WalletService walletService;

    @Test
    void getBalanceReturnsSeededWallet() {
        var wallet = walletService.getWallet(SEEDED_WALLET_ID);

        assertThat(wallet.getWalletId()).isEqualTo(SEEDED_WALLET_ID);
        assertThat(wallet.getAmount()).isEqualTo(100_000L);
    }

    @Test
    void getBalanceUnknownWalletThrows() {
        var unknownWalletId = UUID.randomUUID();

        assertThatThrownBy(() -> walletService.getWallet(unknownWalletId))
                .isInstanceOf(WalletNotFoundException.class);
    }

    @Test
    void createWalletCreatesEmptyWallet() {
        var walletId = UUID.randomUUID();

        var wallet = walletService.createWallet(walletId);

        assertThat(wallet.getWalletId()).isEqualTo(walletId);
        assertThat(wallet.getAmount()).isZero();
        assertThat(wallet.getUpdatedAt()).isNotNull();
    }

    @Test
    void createWalletRejectsExistingWallet() {
        var walletId = UUID.randomUUID();

        walletService.createWallet(walletId);

        assertThatThrownBy(() -> walletService.createWallet(walletId))
                .isInstanceOf(WalletAlreadyExistsException.class);
    }
}
