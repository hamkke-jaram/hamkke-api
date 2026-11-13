package kr.hamkkejaram.common;

import org.springframework.http.HttpStatus;

/** API 에러 코드. 이름과 메시지에도 표현 규칙(SPEC 2.1)을 적용한다. */
public enum ErrorCode {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "입력값을 다시 확인해 주세요."),
    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST, "요청 형식이 올바르지 않아요."),
    UNSUPPORTED_SPEC_VERSION(HttpStatus.BAD_REQUEST, "지원하지 않는 지표 버전이에요. 앱을 새로고침해 주세요."),
    AI_RECEIPT_INVALID(HttpStatus.BAD_REQUEST, "관찰 기록을 확인하지 못했어요. 처음부터 다시 해 주세요."),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "로그인이 필요해요."),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 맞지 않아요."),
    INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "로그인이 만료됐어요. 다시 로그인해 주세요."),
    FORBIDDEN_ROLE(HttpStatus.FORBIDDEN, "이 기능을 쓸 수 없는 계정이에요."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "찾을 수 없어요."),
    EMAIL_TAKEN(HttpStatus.CONFLICT, "이미 가입된 이메일이에요."),
    ALREADY_LINKED(HttpStatus.CONFLICT, "이미 연결된 아이예요."),
    DUPLICATE_REQUEST(HttpStatus.CONFLICT, "이미 처리된 요청이에요. 새로고침해 주세요."),
    PAYLOAD_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "요청이 너무 커요."),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "JSON 요청만 받아요. 영상·파일은 서버로 보내지 않아요."),
    CONSENT_REQUIRED(HttpStatus.UNPROCESSABLE_ENTITY, "필수 약관에 동의해 주세요."),
    GUARDIAN_CONSENT_REQUIRED(HttpStatus.UNPROCESSABLE_ENTITY, "법정대리인 동의가 필요해요."),
    AI_CONSENT_REQUIRED(HttpStatus.UNPROCESSABLE_ENTITY, "AI 관찰 기록을 쓰려면 먼저 보호자 동의가 필요해요."),
    AI_CONSENT_VERSION_MISMATCH(HttpStatus.UNPROCESSABLE_ENTITY, "동의 내용이 바뀌었어요. 새로고침한 뒤 다시 확인해 주세요."),
    INVITE_CODE_INVALID(HttpStatus.UNPROCESSABLE_ENTITY, "초대 코드를 확인해 주세요. 코드는 72시간 동안 한 번만 쓸 수 있어요."),
    CHILD_LIMIT_REACHED(HttpStatus.UNPROCESSABLE_ENTITY, "아이는 5명까지 등록할 수 있어요."),
    INVITE_LIMIT_REACHED(HttpStatus.UNPROCESSABLE_ENTITY, "사용하지 않은 초대 코드가 너무 많아요. 잠시 후 다시 발급해 주세요."),
    TOO_MANY_ATTEMPTS(HttpStatus.TOO_MANY_REQUESTS, "시도가 너무 많아요. 잠시 후 다시 해 주세요."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "지원하지 않는 요청이에요."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "잠시 문제가 생겼어요. 다시 시도해 주세요."),
    AI_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "AI 관찰 기록은 지금 쓸 수 없어요. 놀이 기록은 그대로 남길 수 있어요.");

    private final HttpStatus status;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus status() { return status; }

    public String defaultMessage() { return defaultMessage; }
}
