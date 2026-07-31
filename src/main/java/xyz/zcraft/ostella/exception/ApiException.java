package xyz.zcraft.ostella.exception;

import lombok.Getter;
import xyz.zcraft.ostella.network.ErrorCode;

@Getter
public class ApiException extends RuntimeException {
    private final ErrorCode errorCode;
    private final Exception wrappedException;

    public ApiException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
        this.wrappedException = null;
    }

    public ApiException(ErrorCode errorCode, String message, Exception wrappedException) {
        super(message);
        this.errorCode = errorCode;
        this.wrappedException = wrappedException;
    }

    public ApiException(ErrorCode errorCode) {
        super(getDefaultMessage(errorCode));
        this.errorCode = errorCode;
        this.wrappedException = null;
    }

    public ApiException(ErrorCode errorCode, Exception wrappedException) {
        super(getDefaultMessage(errorCode));
        this.errorCode = errorCode;
        this.wrappedException = wrappedException;
    }

    private static String getDefaultMessage(ErrorCode errorCode) {
        if (errorCode == null) return "Unknown error";
        return errorCode.name().toLowerCase().replace('_', ' ');
    }
}
