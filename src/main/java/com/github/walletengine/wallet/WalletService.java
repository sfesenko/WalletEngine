package com.github.walletengine.wallet;

import com.github.walletengine.lock.LockManager;
import com.github.walletengine.publisher.WalletOutboxMessage;
import com.github.walletengine.publisher.WalletOutboxMessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.UUID;

@Service
public class WalletService {

    private static final Logger LOGGER = LoggerFactory.getLogger(WalletService.class);

    private final WalletRepository walletRepository;
    private final WalletTransactionRepository transactionRepository;
    private final WalletOutboxMessageRepository outboxRepository;
    private final WalletFailedTransactionRepository failedTransactionRepository;
    private final LockManager lockManager;
    private final Duration lockTtl;
    private final boolean strictIdempotency;
    private final TransactionTemplate txTemplate;

    public WalletService(WalletRepository walletRepository,
                         WalletTransactionRepository transactionRepository,
                         WalletOutboxMessageRepository outboxRepository,
                         WalletFailedTransactionRepository failedTransactionRepository,
                         LockManager lockManager,
                         @Value("${wallet.lock.ttl:5s}") Duration lockTtl,
                         @Value("${wallet.idempotency.strict:false}") boolean strictIdempotency,
                         PlatformTransactionManager txManager) {
        this.walletRepository = walletRepository;
        this.transactionRepository = transactionRepository;
        this.outboxRepository = outboxRepository;
        this.failedTransactionRepository = failedTransactionRepository;
        this.lockManager = lockManager;
        this.lockTtl = lockTtl;
        this.strictIdempotency = strictIdempotency;
        this.txTemplate = new TransactionTemplate(txManager);
    }

    public WalletTransaction debit(UUID transactionId, UUID walletId, long amount) {
        return applyMovement(transactionId, walletId, -amount);
    }

    public WalletTransaction credit(UUID transactionId, UUID walletId, long amount) {
        return applyMovement(transactionId, walletId, amount);
    }

    /**
     * @throws WalletNotFoundException if the wallet does not exist
     */
    @Transactional(readOnly = true)
    public Wallet getWalletById(UUID walletId) {
        return walletRepository.findById(walletId)
                .orElseThrow(() -> new WalletNotFoundException(walletId));
    }

    /**
     * Creates wallet with amount 0. No ledger row, no event.
     */
    @Transactional
    public Wallet createWallet(UUID walletId) {
        var wallet = new Wallet(walletId);
        try {
            // save() inserts immediately, so a duplicate wallet_id is rejected inside this transaction
            return walletRepository.save(wallet);
        } catch (DataIntegrityViolationException e) {
            throw new WalletAlreadyExistsException(walletId);
        }
    }

    /**
     * Lock wallet an apply transaction
     */
    private WalletTransaction applyMovement(UUID transactionId, UUID walletId, long amount) {
        try (var lock = lockManager.acquire("wallet:" + walletId, lockTtl)) {
            try {
                var entry = txTemplate.execute(txStatus -> {
                    var applied = executeMovement(transactionId, walletId, amount);

                    // if lock expired - abort transaction, because lock guarantees weren't enforced
                    lock.check();
                    return applied;
                });
                LOGGER.info("movement applied: tx={} wallet={} amount={}", transactionId, walletId, amount);
                return entry;
            } catch (InsufficientFundsException e) {
                LOGGER.warn("transaction rejected, insufficient funds: tx={} wallet={} amount={}",
                        transactionId, walletId, amount);
                recordFailedTransaction(transactionId, walletId, amount, WalletFailedTransaction.Error.INSUFFICIENT_FUNDS);
                throw e;
            } catch (WalletNotFoundException e) {
                LOGGER.warn("transaction rejected, wallet not found: tx={} wallet={} amount={}",
                        transactionId, walletId, amount);
                recordFailedTransaction(transactionId, walletId, amount, WalletFailedTransaction.Error.WALLET_NOT_FOUND);
                throw e;
            }
        } catch (DataIntegrityViolationException e) {
            // same transaction id has been already stored
            // check if it belong the same wallet
            return resolveCommittedDuplicate(transactionId, walletId, amount, e);
        }
    }

    /**
     * Execute wallet movement, all actions in the same transaction:
     * - update wallet balance
     * - store WalletTransaction
     * - store wallet outbox event
     */
    private WalletTransaction executeMovement(UUID transactionId, UUID walletId, long amount) {
        var existing = transactionRepository.findById(transactionId);
        if (existing.isPresent()) {
            return validateWalletLedger(existing.get(), walletId, amount);
        }
        if (strictIdempotency) {
            var failed = failedTransactionRepository.findById(transactionId);
            if (failed.isPresent()) {
                throw replayFailedTransaction(failed.get(), walletId, amount);
            }
        }

        var updated = walletRepository.updateWallet(walletId, amount);
        if (updated > 0) {
            outboxRepository.save(new WalletOutboxMessage(transactionId, walletId, amount));
            return transactionRepository.save(new WalletTransaction(transactionId, walletId, amount));
        }
        // wallet update may fail because either wallet is missing or amount became negative
        // check actual reason
        if (!walletRepository.existsById(walletId)) {
            throw new WalletNotFoundException(walletId);
        }
        //  double-check transactionId - since we lock over walletId, that transactionId still may "slip" into DB
        return transactionRepository.findById(transactionId)
                .map(stored -> validateWalletLedger(stored, walletId, amount))
                .orElseThrow(() -> new InsufficientFundsException(walletId));
    }

    /**
     * Ensure specified transaction was already applied to the same wallet
     * throw IllegalTransactionException otherwise
     */
    private WalletTransaction resolveCommittedDuplicate(
            UUID transactionId,
            UUID walletId,
            long amount,
            DataIntegrityViolationException original) {
        var stored = txTemplate.execute(txStatus -> transactionRepository.findById(transactionId).orElse(null));
        if (stored == null) {
            throw original;
        }
        return validateWalletLedger(stored, walletId, amount);
    }

    /**
     * validate if WalletTransaction has specified amount / wallet
     */
    private static WalletTransaction validateWalletLedger(WalletTransaction stored, UUID walletId, long amount) {
        if (!stored.getWalletId().equals(walletId) || stored.getAmount() != amount) {
            UUID transactionId = stored.getTransactionId();
            var message = "transaction_id %s already used for wallet %s (amount %d), conflicting with wallet %s (amount %d)"
                    .formatted(transactionId, stored.getWalletId(), stored.getAmount(), walletId, amount);
            LOGGER.warn("transaction id conflict: {}", message);
            throw new IllegalTransactionException(message);
        }
        return stored;
    }

    /**
     * Strict idempotency: a duplicate of a failed movement replays the recorded error
     */
    private static RuntimeException replayFailedTransaction(WalletFailedTransaction stored, UUID walletId, long amount) {
        if (!stored.getWalletId().equals(walletId) || stored.getAmount() != amount) {
            var message = "transaction_id %s failed for wallet %s (amount %d), conflicting with wallet %s (amount %d)"
                    .formatted(stored.getTransactionId(), stored.getWalletId(), stored.getAmount(), walletId, amount);
            LOGGER.warn("transaction id conflict: {}", message);
            throw new IllegalTransactionException(message);
        }
        return switch (stored.getError()) {
            case INSUFFICIENT_FUNDS -> new InsufficientFundsException(walletId);
            case WALLET_NOT_FOUND -> new WalletNotFoundException(walletId);
        };
    }

    /**
     * Strict idempotency:: Records a state-dependent failure.
     */
    private void recordFailedTransaction(UUID transactionId, UUID walletId, long amount, WalletFailedTransaction.Error error) {
        if (!strictIdempotency) {
            return;
        }
        try {
            txTemplate.execute(txStatus ->
                    failedTransactionRepository.save(new WalletFailedTransaction(transactionId, walletId, amount, error)));
        } catch (DataIntegrityViolationException ignored) {
            // a racing duplicate recorded this failure first — the stored record replays instead
        }
    }
}
