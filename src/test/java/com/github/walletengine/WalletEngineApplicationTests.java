package com.github.walletengine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Smoke test of the application skeleton:
 */
@SpringBootTest
@ActiveProfiles("test")
class WalletEngineApplicationTests {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void contextLoads() {
        // do nothing, but require spring context
    }

    @ParameterizedTest
    @CsvSource(value = {
            "update wallet_ledger set amount = 0 where transaction_id = ?",
            "delete from wallet_ledger where transaction_id = ?        ",
    }, delimiter = '|')
    void ledgerIsAppendOnly(String sql) {
        var walletId = UUID.randomUUID();
        var transactionId = UUID.randomUUID();
        var amount = 100L;

        jdbcTemplate.update("insert into wallets (wallet_id) values (?)", walletId);
        jdbcTemplate.update("insert into wallet_ledger (transaction_id, wallet_id, amount, wallet_balance) values (?, ?, ?, ?)",
                transactionId, walletId, amount, amount);

        assertThatThrownBy(() -> jdbcTemplate.update(sql, transactionId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");

        assertThat(jdbcTemplate.queryForObject(
                "select amount from wallet_ledger where transaction_id = ?", Long.class, transactionId))
                .isEqualTo(amount);
    }
}
