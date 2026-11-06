package kr.hamkkejaram.child;

import kr.hamkkejaram.common.ApiException;
import kr.hamkkejaram.security.AuthUser;
import kr.hamkkejaram.share.TeacherLinkRepository;
import org.springframework.stereotype.Component;

/**
 * 아동 단위 접근 권한 검사. 권한이 없으면 존재 여부를 드러내지 않도록 404로 응답한다.
 */
@Component
public class ChildAccess {
    private final ChildRepository children;
    private final TeacherLinkRepository links;

    public ChildAccess(ChildRepository children, TeacherLinkRepository links) {
        this.children = children;
        this.links = links;
    }

    /** 본인 아동의 부모만. */
    public Child requireOwner(AuthUser user, long childId) {
        if (!user.isParent()) throw ApiException.notFound();
        return children.findByIdAndParentId(childId, user.id()).orElseThrow(ApiException::notFound);
    }

    /** 연결된 교사만. */
    public Child requireLinkedTeacher(AuthUser user, long childId) {
        if (!user.isTeacher() || !links.existsByTeacherIdAndChildId(user.id(), childId)) {
            throw ApiException.notFound();
        }
        return children.findById(childId).orElseThrow(ApiException::notFound);
    }

    /** 본인 아동의 부모 또는 연결된 교사. */
    public Child requireOwnerOrLinkedTeacher(AuthUser user, long childId) {
        return user.isParent() ? requireOwner(user, childId) : requireLinkedTeacher(user, childId);
    }
}
