package artem.dev.corebank.common.exception;

public class InternalOperationException extends RuntimeException {

    private final String errorCode;

    public InternalOperationException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
