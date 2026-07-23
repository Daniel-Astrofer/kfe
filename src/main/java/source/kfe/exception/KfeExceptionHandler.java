package source.kfe.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import source.common.dto.ApiResponse;
import source.common.exception.ErrorCodes;
import source.common.exception.StructuredPlatformException;

@RestControllerAdvice
public class KfeExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(KfeExceptionHandler.class);

    @ExceptionHandler(StructuredPlatformException.class)
    public ResponseEntity<ApiResponse<Object>> handleStructuredPlatformException(StructuredPlatformException ex) {
        return ResponseEntity
                .status(ex.getStatus())
                .body(ApiResponse.error(ex.getMessage(), ex.getErrorCode(), ex.getData()));
    }

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

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity
                .badRequest()
                .body(ApiResponse.error(ex.getMessage(), ErrorCodes.SYS_INVALID_ARGUMENTS));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalState(IllegalStateException ex) {
        log.warn("KFE operation rejected by current state: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(ApiResponse.error(ex.getMessage(), ErrorCodes.SYS_INVALID_ARGUMENTS));
    }
}
