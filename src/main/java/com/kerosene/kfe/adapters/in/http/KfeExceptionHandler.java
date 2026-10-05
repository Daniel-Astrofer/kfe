package com.kerosene.kfe.adapters.in.http;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import com.kerosene.common.dto.ApiResponse;
import com.kerosene.common.exception.ErrorCodes;
import com.kerosene.common.exception.StructuredPlatformException;

/** Converts expected KFE application exceptions into the common API error envelope. */
@RestControllerAdvice
public class KfeExceptionHandler {

    /** Logger for state conflicts that are rejected at the HTTP boundary. */
    private static final Logger log = LoggerFactory.getLogger(KfeExceptionHandler.class);

    /** Preserves structured status, error code, message, and optional payload from domain-facing errors. */
    @ExceptionHandler(StructuredPlatformException.class)
    public ResponseEntity<ApiResponse<Object>> handleStructuredPlatformException(StructuredPlatformException ex) {
        return ResponseEntity
                .status(ex.getStatus())
                .body(ApiResponse.error(ex.getMessage(), ex.getErrorCode(), ex.getData()));
    }

    /** Maps Spring status exceptions while ensuring an empty reason receives a stable client message. */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiResponse<Void>> handleResponseStatusException(ResponseStatusException ex) {
        HttpStatus status = HttpStatus.valueOf(ex.getStatusCode().value());
        String message = ex.getReason() == null || ex.getReason().isBlank()
                ? "KFE request rejected."
                : ex.getReason();
        return ResponseEntity
                .status(status)
                .body(ApiResponse.error(message, ErrorCodes.SYS_INVALID_ARGUMENTS));
    }

    /** Maps invalid caller input to HTTP 400 and the platform invalid-arguments code. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity
                .badRequest()
                .body(ApiResponse.error(ex.getMessage(), ErrorCodes.SYS_INVALID_ARGUMENTS));
    }

    /** Maps operations that conflict with current state to HTTP 409 and records a warning. */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalState(IllegalStateException ex) {
        log.warn("KFE operation rejected by current state: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(ApiResponse.error(ex.getMessage(), ErrorCodes.SYS_INVALID_ARGUMENTS));
    }
}
