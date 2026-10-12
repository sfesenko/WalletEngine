package com.github.walletengine.wallet;

import com.github.walletengine.lock.LockFailedException;
import io.grpc.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
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

    private static final Logger LOGGER = LoggerFactory.getLogger(WalletGrpcServiceExceptionAdvice.class);

    private static Status mapException(Status status, Exception e) {
        LOGGER.warn("request failed, mapped {} to {}: {}", e.getClass().getSimpleName(), status.getCode(), e.getMessage());
        return mapError(status, e.getMessage());
    }

    private static Status mapError(Status notFound, String errorMessage) {
        return notFound.withDescription(errorMessage);
    }

    @GrpcExceptionHandler(WalletNotFoundException.class)
    public Status onWalletNotFound(WalletNotFoundException e) {
        return mapException(Status.NOT_FOUND, e);
    }

    @GrpcExceptionHandler(InsufficientFundsException.class)
    public Status onInsufficientFunds(InsufficientFundsException e) {
        return mapException(Status.FAILED_PRECONDITION, e);
    }

    @GrpcExceptionHandler(WalletAlreadyExistsException.class)
    public Status onWalletAlreadyExists(WalletAlreadyExistsException e) {
        return mapException(Status.ALREADY_EXISTS, e);
    }

    @GrpcExceptionHandler(LockFailedException.class)
    public Status onLockFailed(LockFailedException e) {
        return mapException(Status.ABORTED, e);
    }

    @GrpcExceptionHandler(DataIntegrityViolationException.class)
    public Status onDataIntegrityViolation(DataIntegrityViolationException e) {
        LOGGER.warn("unexpected database constraint violation", e);
        return mapError(Status.ABORTED, "unexpected database constraint violation");
    }

    @GrpcExceptionHandler(DataAccessResourceFailureException.class)
    public Status onResourceFailure(DataAccessResourceFailureException e) {
        LOGGER.warn("backing service unavailable", e);
        return mapError(Status.UNAVAILABLE, "backing service unavailable");
    }

    @GrpcExceptionHandler(IllegalArgumentException.class)
    public Status onInvalidArgument(IllegalArgumentException e) {
        return mapException(Status.INVALID_ARGUMENT, e);
    }
}
