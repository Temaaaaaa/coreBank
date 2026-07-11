package artem.dev.corebank.common.exception;

import java.time.Instant;
import java.util.List;

public record ApiErrorResponse(
        Instant timestamp,
        String path,
        String code,
        String message,
        List<ApiValidationError> validationErrors
) {

    public ApiErrorResponse {
        validationErrors = List.copyOf(validationErrors);
    }
}
