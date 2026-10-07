package com.github.walletengine.wallet;

import com.github.walletengine.lock.WalletLockManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

/**
 * Wallet operations. No gRPC types here; the transport layer adapts to and from these
 * signatures.
 */
@Service
public class WalletService {

    private final WalletRepository walletRepository;
    private final WalletLedgerRepository ledgerRepository;
    private final WalletLockManager lockManager;
    private final TransactionTemplate txTemplate;

    public WalletService(WalletRepository walletRepository,
                         WalletLedgerRepository ledgerRepository,
                         WalletLockManager lockManager,
                         PlatformTransactionManager txManager) {
        this.walletRepository = walletRepository;
        this.ledgerRepository = ledgerRepository;
        this.lockManager = lockManager;
        this.txTemplate = new TransactionTemplate(txManager);
    }

    /**
     * Applies a debit and returns the transactions log row for it.
     *
     * <p>Idempotent on {@code transactionId}: a repeated request returns the stored row
     * without moving the balance again. Any failure rolls the movement back and fails
     * the request — no recovery.
     *
     * @param amount positive magnitude; applied as a negative movement
     */
    public WalletLedgerEntry debit(UUID transactionId, UUID walletId, long amount) {
        var magnitude = requirePositiveAmount(amount, "debit");
        var movement = -magnitude;

        return applyMovement(transactionId, walletId, movement);
    }

    /**
     * Applies a credit and returns the transactions log row for it.
     *
     * <p>Idempotent on {@code transactionId}: a repeated request returns the stored row
     * without moving the balance again. Any failure rolls the movement back and fails
     * the request — no recovery.
     *
     * @param amount positive magnitude; applied as a positive movement
     */
    public WalletLedgerEntry credit(UUID transactionId, UUID walletId, long amount) {
        var movement = requirePositiveAmount(amount, "credit");

        return applyMovement(transactionId, walletId, movement);
    }

    /**
     * @throws WalletNotFoundException if the wallet does not exist
     */
    @Transactional(readOnly = true)
    public Wallet getWallet(UUID walletId) {
        return walletRepository.getWallet(walletId);
    }

    /**
     * Creates the wallet with amount 0 and returns it. No ledger row and no event —
     * nothing moved.
     *
     * @throws WalletAlreadyExistsException if the wallet already exists
     */
    @Transactional
    public Wallet createWallet(UUID walletId) {
        var wallet = new Wallet(walletId);
        try {
            return walletRepository.saveAndFlush(wallet);
        } catch (DataIntegrityViolationException e) {
            throw new WalletAlreadyExistsException(walletId);
        }
    }

    private static long requirePositiveAmount(long amount, String operation) {
        if (amount <= 0) {
            var message = operation + " amount must be positive: " + amount;

            throw new IllegalArgumentException(message);
        }

        return amount;
    }

    private WalletLedgerEntry applyMovement(UUID transactionId, UUID walletId, long amount) {
        lockManager.acquire(walletId);
        try {
            return txTemplate.execute(txStatus -> executeMovement(transactionId, walletId, amount));
        } finally {
            lockManager.release(walletId);
        }
    }

    private WalletLedgerEntry executeMovement(UUID transactionId, UUID walletId, long amount) {
        var existing = ledgerRepository.findByTransactionId(transactionId);
        if (existing.isPresent()) {
            return existing.get();
        }

        var updated = walletRepository.updateWallet(walletId, amount);
        if (updated == 0) {
            if (walletRepository.existsById(walletId)) {
                throw new InsufficientFundsException(walletId);
            } else {
                throw new WalletNotFoundException(walletId);
            }
        }

        var entry = new WalletLedgerEntry(transactionId, walletId, amount);

        return ledgerRepository.save(entry);
    }
}
