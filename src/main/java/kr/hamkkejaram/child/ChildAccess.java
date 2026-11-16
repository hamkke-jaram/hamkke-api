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

    /**
     * 이 아이에 대한 쓰기를 줄 세운다(아이 행 잠금, 트랜잭션이 끝날 때 풀린다). 권한 확인은 하지 않으니
     * 뒤에서 require… 를 따로 부른다. 잠근 뒤에 읽어야 다른 트랜잭션이 막 커밋한 값(철회된 동의 등)을 보므로,
     * 트랜잭션의 맨 처음에 부른다.
     */
    public void lockChild(long childId) {
        children.lockById(childId);
    }
}
