package com.github.walletengine.wallet;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Concurrency suite in strict idempotency mode: state-dependent rejections are recorded
 * and a duplicate request for them replays the original error.
 */
@SpringBootTest(properties = "wallet.idempotency.strict=true")
@ActiveProfiles("test")
class StrictWalletConcurrencyTest extends AbstractWalletConcurrencyTest {

    @Override
    protected boolean isStrictIdempotency() {
        return true;
    }
}
