package com.github.walletengine;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke test for the project skeleton.
 *
 * <p>Runs against the Docker Compose infrastructure (see
 * {@code application-test.properties}), so a running Docker daemon is
 * required. The containers are left running after the test run so the
 * database can be inspected afterwards.
 */
@SpringBootTest
@ActiveProfiles("test")
class WalletEngineApplicationTests {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void contextLoads() {
    }

    @Test
    void flywayMigrationsAreApplied() {
        var applied = this.jdbcTemplate.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success = true", Integer.class);
        assertThat(applied).isGreaterThanOrEqualTo(2);
    }

    @Test
    void seedWalletsArePresent() {
        var wallets = this.jdbcTemplate.queryForObject(
                "SELECT count(*) FROM wallets", Integer.class);
        assertThat(wallets).isGreaterThanOrEqualTo(3);
    }

    @Test
    void ledgerImmutabilityTriggerIsInstalled() {
        var triggers = this.jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_trigger WHERE tgname = 'trg_wallet_ledger_immutable'", Integer.class);
        assertThat(triggers).isEqualTo(1);
    }
}
