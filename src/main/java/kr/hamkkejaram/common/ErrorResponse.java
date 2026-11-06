package kr.hamkkejaram.common;

import java.util.List;

public record ErrorResponse(String code, String message, List<FieldError> fieldErrors) {
    public record FieldError(String field, String message) {}

    public static ErrorResponse of(ErrorCode code) {
        return new ErrorResponse(code.name(), code.defaultMessage(), List.of());
    }

    public static ErrorResponse of(ErrorCode code, String message) {
        return new ErrorResponse(code.name(), message, List.of());
    }
}
