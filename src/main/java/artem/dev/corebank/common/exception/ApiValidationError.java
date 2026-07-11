package artem.dev.corebank.common.exception;

public record ApiValidationError(
        String field,
        String message
) {
}
