package com.github.walletengine.wallet;

import com.github.walletengine.lock.WalletLockManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

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

    public WalletLedgerEntry debit(UUID transactionId, UUID walletId, long amount) {
        return applyMovement(transactionId, walletId, -amount);
    }

    public WalletLedgerEntry credit(UUID transactionId, UUID walletId, long amount) {
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
            return walletRepository.save(wallet);
        } catch (DataIntegrityViolationException e) {
            throw new WalletAlreadyExistsException(walletId);
        }
    }

    private WalletLedgerEntry applyMovement(UUID transactionId, UUID walletId, long amount) {
        lockManager.acquire(walletId);
        try {
            return txTemplate.execute(txStatus -> executeMovement(transactionId, walletId, amount));
        } finally {
            lockManager.release(walletId);
        }
    }

    /**
     * Update wallet balance and save ledger entry
     */
    private WalletLedgerEntry executeMovement(UUID transactionId, UUID walletId, long amount) {
        var existing = ledgerRepository.findById(transactionId);
        if (existing.isPresent()) {
            var stored = existing.get();
            if (!stored.getWalletId().equals(walletId) || stored.getAmount() != amount) {
                throw new IllegalArgumentException("transaction_id already used: " + transactionId);
            }
            return stored;
        }

        var updated = walletRepository.updateWallet(walletId, amount);
        if (updated == 0) {
            var walletExists = walletRepository.existsById(walletId);
            if (walletExists) {
                throw new InsufficientFundsException(walletId);
            } else {
                throw new WalletNotFoundException(walletId);
            }
        }

        return ledgerRepository.save(new WalletLedgerEntry(transactionId, walletId, amount));
    }
}
