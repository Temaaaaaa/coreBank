package artem.dev.corebank.common.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final Clock clock;

    public GlobalExceptionHandler(Clock clock) {
        this.clock = clock;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(
            MethodArgumentNotValidException exception,
            HttpServletRequest request
    ) {
        List<ApiValidationError> validationErrors = exception.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(error -> new ApiValidationError(error.getField(), error.getDefaultMessage()))
                .toList();

        return buildResponse(
                HttpStatus.BAD_REQUEST,
                request,
                "VALIDATION_FAILED",
                "Request validation failed",
                validationErrors
        );
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleMalformedJson(
            HttpMessageNotReadableException exception,
            HttpServletRequest request
    ) {
        return buildResponse(
                HttpStatus.BAD_REQUEST,
                request,
                "MALFORMED_JSON",
                "Request body contains malformed JSON",
                List.of()
        );
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleNotFound(
            ResourceNotFoundException exception,
            HttpServletRequest request
    ) {
        return buildResponse(
                HttpStatus.NOT_FOUND,
                request,
                exception.getErrorCode(),
                exception.getMessage(),
                List.of()
        );
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidParameter(
            MethodArgumentTypeMismatchException exception,
            HttpServletRequest request
    ) {
        return buildResponse(
                HttpStatus.BAD_REQUEST,
                request,
                "INVALID_REQUEST",
                "Request parameter has an invalid format",
                List.of()
        );
    }

    @ExceptionHandler(DuplicateResourceException.class)
    public ResponseEntity<ApiErrorResponse> handleDuplicate(
            DuplicateResourceException exception,
            HttpServletRequest request
    ) {
        return buildResponse(
                HttpStatus.CONFLICT,
                request,
                exception.getErrorCode(),
                exception.getMessage(),
                List.of()
        );
    }

    @ExceptionHandler(BusinessRuleException.class)
    public ResponseEntity<ApiErrorResponse> handleBusinessRule(
            BusinessRuleException exception,
            HttpServletRequest request
    ) {
        HttpStatus status = isConflict(exception.getErrorCode())
                ? HttpStatus.CONFLICT
                : HttpStatus.BAD_REQUEST;
        return buildResponse(
                status,
                request,
                exception.getErrorCode(),
                exception.getMessage(),
                List.of()
        );
    }

    private boolean isConflict(String errorCode) {
        return "ACCOUNT_NOT_ACTIVE".equals(errorCode)
                || "INSUFFICIENT_FUNDS".equals(errorCode)
                || "SAME_ACCOUNT_TRANSFER".equals(errorCode)
                || "CURRENCY_MISMATCH".equals(errorCode);
    }

    @ExceptionHandler(InternalOperationException.class)
    public ResponseEntity<ApiErrorResponse> handleInternalOperation(
            InternalOperationException exception,
            HttpServletRequest request
    ) {
        LOGGER.error(
                "Internal operation failed while processing {} {}: {}",
                request.getMethod(),
                request.getRequestURI(),
                exception.getErrorCode()
        );
        return buildResponse(
                HttpStatus.INTERNAL_SERVER_ERROR,
                request,
                exception.getErrorCode(),
                exception.getMessage(),
                List.of()
        );
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(
            Exception exception,
            HttpServletRequest request
    ) {
        LOGGER.error(
                "Unexpected error while processing {} {}",
                request.getMethod(),
                request.getRequestURI(),
                exception
        );

        return buildResponse(
                HttpStatus.INTERNAL_SERVER_ERROR,
                request,
                "INTERNAL_SERVER_ERROR",
                "An unexpected error occurred",
                List.of()
        );
    }

    private ResponseEntity<ApiErrorResponse> buildResponse(
            HttpStatus status,
            HttpServletRequest request,
            String code,
            String message,
            List<ApiValidationError> validationErrors
    ) {
        ApiErrorResponse response = new ApiErrorResponse(
                Instant.now(clock),
                request.getRequestURI(),
                code,
                message,
                validationErrors
        );
        return ResponseEntity.status(status).body(response);
    }
}
