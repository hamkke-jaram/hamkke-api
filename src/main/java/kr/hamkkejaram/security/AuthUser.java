package kr.hamkkejaram.security;

import kr.hamkkejaram.common.ApiException;
import kr.hamkkejaram.common.ErrorCode;
import kr.hamkkejaram.user.Role;

/** 인증된 요청의 주체. 컨트롤러에서 {@code @AuthenticationPrincipal AuthUser}로 받는다. */
public record AuthUser(long id, Role role) {

    public boolean isParent() { return role == Role.PARENT; }

    public boolean isTeacher() { return role == Role.TEACHER; }

    public AuthUser requireParent() {
        if (!isParent()) throw new ApiException(ErrorCode.FORBIDDEN_ROLE);
        return this;
    }

    public AuthUser requireTeacher() {
        if (!isTeacher()) throw new ApiException(ErrorCode.FORBIDDEN_ROLE);
        return this;
    }
}
