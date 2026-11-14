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

    /** 목록 커서(beforeId)가 가리키는 항목이 그 목록에 없을 때(지워졌거나 다른 아이의 항목). */
    public static ApiException unknownCursor() {
        return new ApiException(ErrorCode.VALIDATION_FAILED, "목록 기준 항목을 찾지 못했어요. 목록을 처음부터 다시 불러와 주세요.");
    }
}
