package com.github.walletengine.wallet;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Concurrency suite in non-strict idempotency mode: rejected movements leave no trace,
 * so a later duplicate may be re-executed.
 */
@SpringBootTest(properties = "wallet.idempotency.strict=false")
@ActiveProfiles("test")
class NonStrictWalletConcurrencyTest extends AbstractWalletConcurrencyTest {

    @Override
    protected boolean isStrictIdempotency() {
        return false;
    }
}
