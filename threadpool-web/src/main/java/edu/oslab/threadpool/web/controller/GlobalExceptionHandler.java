package edu.oslab.threadpool.web.controller;

import edu.oslab.threadpool.DeadlockDetectedException;
import edu.oslab.threadpool.web.dto.ErrorResponse;
import edu.oslab.threadpool.web.error.ConcurrentRunLimitExceededException;
import edu.oslab.threadpool.web.error.RunCapacityExceededException;
import edu.oslab.threadpool.web.error.RunNotFoundException;
import edu.oslab.threadpool.web.error.RunValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

/**
 * Maps domain failures onto the documented status codes and a stable error body.
 *
 * <p>The catch-all deliberately handles {@link RuntimeException} rather than {@link Throwable}: an
 * SSE client that disconnects mid-stream surfaces as an {@code IOException}, which the container
 * already handles and which must not be reported as a server error.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** A dependency cycle, reported with the offending path. */
    @ExceptionHandler(DeadlockDetectedException.class)
    public ResponseEntity<ErrorResponse> handleDependencyCycle(DeadlockDetectedException exception) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(new ErrorResponse("DEPENDENCY_CYCLE", exception.getMessage(), exception.cycle()));
    }

    @ExceptionHandler(RunNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleMissingRun(RunNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("RUN_NOT_FOUND", exception.getMessage()));
    }

    @ExceptionHandler(ConcurrentRunLimitExceededException.class)
    public ResponseEntity<ErrorResponse> handleConcurrencyLimit(ConcurrentRunLimitExceededException exception) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(new ErrorResponse("TOO_MANY_RUNS", exception.getMessage()));
    }

    @ExceptionHandler(RunCapacityExceededException.class)
    public ResponseEntity<ErrorResponse> handleCapacity(RunCapacityExceededException exception) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(new ErrorResponse("RUN_CAPACITY_EXCEEDED", exception.getMessage()));
    }

    @ExceptionHandler(RunValidationException.class)
    public ResponseEntity<ErrorResponse> handleValidation(RunValidationException exception) {
        return ResponseEntity.badRequest().body(new ErrorResponse("INVALID_RUN_DEFINITION", exception.getMessage()));
    }

    /** Bad task definitions and rejected batches: duplicate ids, unknown dependencies, unknown tasks. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(new ErrorResponse("INVALID_REQUEST", exception.getMessage()));
    }

    /** Wrong run state, for example starting a run twice or failing a task of a finished run. */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorResponse> handleIllegalState(IllegalStateException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("INVALID_RUN_STATE", exception.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadableBody(HttpMessageNotReadableException exception) {
        return ResponseEntity.badRequest().body(new ErrorResponse("MALFORMED_REQUEST", "Request body is not valid JSON"));
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(RuntimeException exception) {
        LOGGER.error("Unhandled failure while serving a run request", exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("INTERNAL_ERROR", exception.getMessage() == null
                        ? exception.getClass().getSimpleName()
                        : exception.getMessage(),
                        List.of()));
    }
}
