package com.github.walletengine.wallet;

import com.github.walletengine.lock.LockAcquisitionTimeoutException;
import io.grpc.Status;
import org.springframework.grpc.server.advice.GrpcAdvice;
import org.springframework.grpc.server.advice.GrpcExceptionHandler;

/**
 * Maps wallet failures to gRPC status codes for every method of the service. The one
 * place where {@link WalletService} exceptions become transport errors.
 *
 * <p>Unmapped exceptions fall through to the framework default ({@code UNKNOWN}).
 */
@GrpcAdvice
public class WalletGrpcServiceExceptionAdvice {

    @GrpcExceptionHandler(WalletNotFoundException.class)
    public Status onWalletNotFound(WalletNotFoundException e) {
        var description = e.getMessage();

        return Status.NOT_FOUND.withDescription(description);
    }

    @GrpcExceptionHandler(InsufficientFundsException.class)
    public Status onInsufficientFunds(InsufficientFundsException e) {
        var description = e.getMessage();

        return Status.FAILED_PRECONDITION.withDescription(description);
    }

    @GrpcExceptionHandler(WalletAlreadyExistsException.class)
    public Status onWalletAlreadyExists(WalletAlreadyExistsException e) {
        var description = e.getMessage();

        return Status.ALREADY_EXISTS.withDescription(description);
    }

    @GrpcExceptionHandler(LockAcquisitionTimeoutException.class)
    public Status onLockTimeout(LockAcquisitionTimeoutException e) {
        var description = e.getMessage();

        return Status.ABORTED.withDescription(description);
    }

    @GrpcExceptionHandler(IllegalArgumentException.class)
    public Status onInvalidArgument(IllegalArgumentException e) {
        var description = e.getMessage();

        return Status.INVALID_ARGUMENT.withDescription(description);
    }
}
