package com.github.walletengine.wallet;

import com.github.walletengine.lock.LockManager;
import com.github.walletengine.publisher.WalletOutboxMessage;
import com.github.walletengine.publisher.WalletOutboxMessageRepository;
import com.github.walletengine.util.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.Objects;
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

    public WalletService(
            WalletRepository walletRepository,
            WalletTransactionRepository transactionRepository,
            WalletOutboxMessageRepository outboxRepository,
            WalletFailedTransactionRepository failedTransactionRepository,
            LockManager lockManager,
            PlatformTransactionManager txManager,
            @Value("${wallet.lock.ttl:5s}") Duration lockTtl,
            @Value("${wallet.idempotency.strict:true}") boolean strictIdempotency
    ) {
        this.walletRepository = walletRepository;
        this.transactionRepository = transactionRepository;
        this.outboxRepository = outboxRepository;
        this.failedTransactionRepository = failedTransactionRepository;
        this.lockManager = lockManager;
        this.txTemplate = new TransactionTemplate(txManager);
        this.lockTtl = lockTtl;
        this.strictIdempotency = strictIdempotency;
    }

    public WalletTransaction debit(UUID transactionId, UUID walletId, long amount) {
        Utils.requirePositive(amount, () -> "debit amount must be positive: " + amount);
        return applyMovement(transactionId, walletId, -amount);
    }

    public WalletTransaction credit(UUID transactionId, UUID walletId, long amount) {
        Utils.requirePositive(amount, () -> "credit amount must be positive: " + amount);
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
            // use saveAndFlush to force constraint validation inside transaction
            return walletRepository.saveAndFlush(wallet);
        } catch (DataIntegrityViolationException e) {
            throw new WalletAlreadyExistsException(walletId, e);
        }
    }

    /**
     * Lock wallet an apply transaction, returns the stored ledger row
     */
    private WalletTransaction applyMovement(UUID transactionId, UUID walletId, long amount) {
        try (var lock = lockManager.acquire("wallet:" + walletId, lockTtl)) {
            try {
                var execution = txTemplate.execute(txStatus -> {
                    var result = executeMovement(transactionId, walletId, amount);

                    // if lock expired - abort transaction, because lock guarantees weren't enforced
                    lock.check();
                    return result;
                });
                Objects.requireNonNull(execution, "this can't happen, txTemplate.execute won't return null");
                if (execution.rejection() != null) {
                    // the rejection and its strict-mode record were committed with the movement transaction
                    throw execution.rejection();
                }
                if (execution.replayed()) {
                    LOGGER.info("movement replayed: tx={} wallet={} amount={}", transactionId, walletId, amount);
                } else {
                    LOGGER.info("movement applied: tx={} wallet={} amount={}", transactionId, walletId, amount);
                }
                return execution.entry();
            } catch (InsufficientFundsException | WalletNotFoundException e) {
                LOGGER.warn("transaction rejected: tx={} wallet={} amount={} reason={}",
                        transactionId, walletId, amount, e.getMessage());
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
    private MovementExecution executeMovement(UUID transactionId, UUID walletId, long amount) {
        var existing = transactionRepository.findById(transactionId);
        if (existing.isPresent()) {
            return new MovementExecution(validateWalletLedger(existing.get(), walletId, amount), null, true);
        }
        if (strictIdempotency) {
            var failed = failedTransactionRepository.findById(transactionId);
            if (failed.isPresent()) {
                throw getTransactionError(failed.get(), walletId, amount);
            }
        }

        var updated = walletRepository.updateWallet(walletId, amount);
        if (updated > 0) {
            // snapshot the wallet balance after this movement, so a replay is reproducible from the ledger row
            var walletBalance = walletRepository.findById(walletId)
                    .orElseThrow(() -> new WalletNotFoundException(walletId))
                    .getAmount();
            outboxRepository.save(new WalletOutboxMessage(transactionId, walletId, amount));
            return new MovementExecution(
                    transactionRepository.save(new WalletTransaction(transactionId, walletId, amount, walletBalance)), null, false);
        }
        // wallet update may fail because either wallet is missing or amount became negative
        // check actual reason
        if (!walletRepository.existsById(walletId)) {
            return rejected(transactionId, walletId, amount, WalletFailedTransaction.Error.WALLET_NOT_FOUND);
        }
        //  double-check transactionId - since we lock over walletId, that transactionId still may "slip" into DB
        return transactionRepository.findById(transactionId)
                .map(wt -> validateWalletLedger(wt, walletId, amount))
                .map(tx -> new MovementExecution(tx, null, true))
                .orElseGet(() -> rejected(transactionId, walletId, amount, WalletFailedTransaction.Error.INSUFFICIENT_FUNDS));
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
        var entry = validateWalletLedger(stored, walletId, amount);
        LOGGER.info("movement replayed: tx={} wallet={} amount={}", transactionId, walletId, amount);
        return entry;
    }

    /**
     * validate if WalletTransaction has specified amount / wallet
     */
    private static WalletTransaction validateWalletLedger(WalletTransaction stored, UUID walletId, long amount) {
        if (!stored.getWalletId().equals(walletId) || stored.getAmount() != amount) {
            LOGGER.warn("transaction id conflict: tx={} storedWallet={} storedAmount={} requestedWallet={} requestedAmount={}",
                    stored.getTransactionId(), stored.getWalletId(), stored.getAmount(), walletId, amount);
            throw IllegalTransactionException.transactionAlreadyUsed(stored.getTransactionId());
        }
        return stored;
    }

    /**
     * Strict idempotency: a duplicate of a failed movement replays the recorded error
     */
    private static RuntimeException getTransactionError(WalletFailedTransaction stored, UUID walletId, long amount) {
        // wallet_id and amount must much previous transaction, otherwise - report error
        if (!stored.getWalletId().equals(walletId) || stored.getAmount() != amount) {
            LOGGER.warn("transaction id conflict: tx={} storedFailedWallet={} storedFailedAmount={} requestedWallet={} requestedAmount={}",
                    stored.getTransactionId(), stored.getWalletId(), stored.getAmount(), walletId, amount);
            throw IllegalTransactionException.transactionAlreadyUsed(stored.getTransactionId());
        }
        return toException(stored.getError(), walletId);
    }

    /**
     * State-dependent rejection. In strict mode the rejection is recorded within the movement's
     * transaction (the caller throws the exception only after that transaction committed), so a
     * duplicate request always finds the stored rejection.
     */
    private MovementExecution rejected(
            UUID transactionId, UUID walletId, long amount, WalletFailedTransaction.Error error) {
        if (strictIdempotency) {
            // on conflict do nothing: a racing duplicate may have recorded this rejection first
            failedTransactionRepository.insertIfAbsent(transactionId, walletId, amount, error.name());
        }
        return new MovementExecution(null, toException(error, walletId), false);
    }

    private static RuntimeException toException(WalletFailedTransaction.Error error, UUID walletId) {
        return switch (error) {
            case INSUFFICIENT_FUNDS -> new InsufficientFundsException(walletId);
            case WALLET_NOT_FOUND -> new WalletNotFoundException(walletId);
        };
    }

    /**
     * Movement outcome: a stored entry (newly applied or replayed for a duplicate request), or a
     * state-dependent rejection that is thrown once its transaction committed.
     */
    private record MovementExecution(WalletTransaction entry, RuntimeException rejection, boolean replayed) {
    }
}
