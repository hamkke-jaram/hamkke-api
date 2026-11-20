package kr.hamkkejaram.talk;

import kr.hamkkejaram.child.ChildAccess;
import kr.hamkkejaram.common.ApiException;
import kr.hamkkejaram.note.ObservationNoteRepository;
import kr.hamkkejaram.security.AuthUser;
import kr.hamkkejaram.talk.TalkDtos.MessageResponse;
import kr.hamkkejaram.talk.TalkDtos.SendMessageRequest;
import kr.hamkkejaram.talk.TalkDtos.UnreadResponse;
import kr.hamkkejaram.user.Role;
import kr.hamkkejaram.user.User;
import kr.hamkkejaram.user.UserRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 보호자와 연결된 선생님이 아이에 대해 주고받는 이야기(TLK).
 * 이 아이의 보호자와 연결된 선생님만 읽고 쓴다(그 밖에는 404). 선생님이 연결을 끊으면 이미 쓴 이야기는 남고
 * 그 선생님은 더는 읽지 못한다(목표·관찰 메모와 같은 규칙).
 */
@Service
public class TalkService {
    static final int MAX_PAGE = 100;

    private final ChildMessageRepository messages;
    private final ChildMessageReadRepository reads;
    private final ObservationNoteRepository notes;
    private final UserRepository users;
    private final ChildAccess access;
    private final Clock clock;

    public TalkService(ChildMessageRepository messages, ChildMessageReadRepository reads,
                       ObservationNoteRepository notes, UserRepository users, ChildAccess access, Clock clock) {
        this.messages = messages;
        this.reads = reads;
        this.notes = notes;
        this.users = users;
        this.access = access;
        this.clock = clock;
    }

    /**
     * 오래된 순 목록. afterId가 없으면 최근 limit건, 있으면 그보다 새 것(화면이 몇 초마다 새 것만 받아 간다).
     */
    @Transactional(readOnly = true)
    public List<MessageResponse> list(AuthUser user, long childId, Long afterId, int limit) {
        access.requireOwnerOrLinkedTeacher(user, childId);
        PageRequest page = PageRequest.of(0, Math.max(1, Math.min(MAX_PAGE, limit)));
        List<ChildMessage> list;
        if (afterId == null) {
            list = new ArrayList<>(messages.findByChildIdOrderByIdDesc(childId, page));
            Collections.reverse(list);
        } else {
            list = messages.findByChildIdAndIdGreaterThanOrderByIdAsc(childId, afterId, page);
        }
        Map<Long, String> names = users.findAllById(list.stream().map(ChildMessage::getAuthorId).distinct().toList())
                .stream().collect(Collectors.toMap(User::getId, User::getDisplayName));
        return list.stream().map(m -> toResponse(m, names.getOrDefault(m.getAuthorId(), fallbackName(m)), user))
                .toList();
    }

    @Transactional
    public MessageResponse send(AuthUser user, long childId, SendMessageRequest req) {
        access.requireOwnerOrLinkedTeacher(user, childId);
        if (req.observationId() != null) {
            // 이 아이의 관찰 메모에만 답할 수 있다(다른 아이의 메모 id면 없는 것처럼).
            notes.findByIdAndChildId(req.observationId(), childId).orElseThrow(ApiException::notFound);
        }
        ChildMessage m = messages.save(new ChildMessage(childId, user.id(), user.role(), req.observationId(),
                req.content().trim(), clock.instant()));
        // 내가 쓴 이야기까지는 읽은 것으로 둔다.
        markRead(childId, user.id(), m.getId());
        String name = users.findById(user.id()).map(User::getDisplayName).orElse(fallbackName(m));
        return toResponse(m, name, user);
    }

    @Transactional
    public UnreadResponse read(AuthUser user, long childId, long lastReadId) {
        access.requireOwnerOrLinkedTeacher(user, childId);
        Long latest = messages.findTopByChildIdOrderByIdDesc(childId).map(ChildMessage::getId).orElse(null);
        // 있지도 않은 앞자리까지 읽었다고 적지 않는다.
        if (latest != null) markRead(childId, user.id(), Math.min(lastReadId, latest));
        return unreadOf(user, childId, latest);
    }

    @Transactional(readOnly = true)
    public UnreadResponse unread(AuthUser user, long childId) {
        access.requireOwnerOrLinkedTeacher(user, childId);
        Long latest = messages.findTopByChildIdOrderByIdDesc(childId).map(ChildMessage::getId).orElse(null);
        return unreadOf(user, childId, latest);
    }

    private UnreadResponse unreadOf(AuthUser user, long childId, Long latest) {
        if (latest == null) return new UnreadResponse(0, null);
        long lastRead = reads.findById(new ChildMessageRead.Key(childId, user.id()))
                .map(ChildMessageRead::getLastReadId).orElse(0L);
        return new UnreadResponse(messages.countUnread(childId, user.id(), lastRead), latest);
    }

    private void markRead(long childId, long userId, long id) {
        var key = new ChildMessageRead.Key(childId, userId);
        reads.findById(key).ifPresentOrElse(
                r -> r.advance(id, clock.instant()),
                () -> reads.save(new ChildMessageRead(childId, userId, id, clock.instant())));
    }

    private static MessageResponse toResponse(ChildMessage m, String authorName, AuthUser viewer) {
        return new MessageResponse(m.getId(), m.getContent(), m.getAuthorRole(), authorName,
                m.getAuthorId().equals(viewer.id()), m.getObservationId(), m.getCreatedAt());
    }

    private static String fallbackName(ChildMessage m) {
        return m.getAuthorRole() == Role.TEACHER ? "선생님" : "보호자";
    }
}
