package kr.hamkkejaram.common;

public class ApiException extends RuntimeException {
    private final ErrorCode code;

    public ApiException(ErrorCode code) {
        this(code, code.defaultMessage());
    }

    public ApiException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ErrorCode code() { return code; }

    public static ApiException notFound() { return new ApiException(ErrorCode.NOT_FOUND); }
}
