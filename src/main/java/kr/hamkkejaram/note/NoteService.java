package kr.hamkkejaram.note;

import kr.hamkkejaram.card.CardDelivery;
import kr.hamkkejaram.card.CardDeliveryRepository;
import kr.hamkkejaram.child.ChildAccess;
import kr.hamkkejaram.common.ApiException;
import kr.hamkkejaram.common.ErrorCode;
import kr.hamkkejaram.common.TimeConfig;
import kr.hamkkejaram.note.NoteDtos.AttachedCard;
import kr.hamkkejaram.note.NoteDtos.CreateGoalRequest;
import kr.hamkkejaram.note.NoteDtos.CreateObservationRequest;
import kr.hamkkejaram.note.NoteDtos.GoalResponse;
import kr.hamkkejaram.note.NoteDtos.ObservationResponse;
import kr.hamkkejaram.note.NoteDtos.UpdateGoalRequest;
import kr.hamkkejaram.security.AuthUser;
import kr.hamkkejaram.share.ShareSettingRepository;
import kr.hamkkejaram.user.User;
import kr.hamkkejaram.user.UserRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 교사가 쓰는 아동 목표와 관찰 메모(선생님 이야기). 부모는 읽기만 한다.
 * 이야기에는 제목과 이 아이가 받은 코칭 카드를 붙일 수 있다(카드는 보호자가 '받은 코칭 카드' 공유를 켠 경우만).
 */
@Service
public class NoteService {
    private final ChildGoalRepository goals;
    private final ObservationNoteRepository notes;
    private final UserRepository users;
    private final ChildAccess access;
    private final CardDeliveryRepository deliveries;
    private final ShareSettingRepository shareSettings;
    private final Clock clock;

    public NoteService(ChildGoalRepository goals, ObservationNoteRepository notes, UserRepository users,
                       ChildAccess access, CardDeliveryRepository deliveries, ShareSettingRepository shareSettings,
                       Clock clock) {
        this.goals = goals;
        this.notes = notes;
        this.users = users;
        this.access = access;
        this.deliveries = deliveries;
        this.shareSettings = shareSettings;
        this.clock = clock;
    }

    // ---------- 목표 ----------

    @Transactional(readOnly = true)
    public List<GoalResponse> listGoals(AuthUser user, long childId) {
        access.requireOwnerOrLinkedTeacher(user, childId);
        List<ChildGoal> list = goals.findByChildIdOrderByCreatedAtDesc(childId);
        Map<Long, String> names = names(list.stream().map(ChildGoal::getTeacherId).toList());
        return list.stream().map(g -> GoalResponse.from(g, names.getOrDefault(g.getTeacherId(), "선생님"))).toList();
    }

    @Transactional
    public GoalResponse createGoal(AuthUser user, long childId, CreateGoalRequest req) {
        access.requireLinkedTeacher(user, childId);
        ChildGoal g = goals.save(new ChildGoal(childId, user.id(), req.title().trim(),
                req.description() == null ? "" : req.description().trim(), clock.instant()));
        return GoalResponse.from(g, nameOf(user.id()));
    }

    @Transactional
    public GoalResponse updateGoal(AuthUser user, long childId, long goalId, UpdateGoalRequest req) {
        access.requireLinkedTeacher(user, childId);
        ChildGoal g = ownGoal(user, childId, goalId);
        g.update(req.title() == null ? null : req.title().trim(),
                req.description() == null ? null : req.description().trim(), req.status(), clock.instant());
        return GoalResponse.from(g, nameOf(user.id()));
    }

    @Transactional
    public void deleteGoal(AuthUser user, long childId, long goalId) {
        access.requireLinkedTeacher(user, childId);
        goals.delete(ownGoal(user, childId, goalId));
    }

    // ---------- 관찰 메모 ----------

    @Transactional(readOnly = true)
    public List<ObservationResponse> listNotes(AuthUser user, long childId, int limit, Long beforeId) {
        access.requireOwnerOrLinkedTeacher(user, childId);
        PageRequest page = PageRequest.of(0, Math.max(1, Math.min(100, limit)));
        List<ObservationNote> list;
        if (beforeId == null) {
            list = notes.findByChildIdOrderByObservedOnDescIdDesc(childId, page);
        } else {
            ObservationNote anchor = notes.findByIdAndChildId(beforeId, childId)
                    .orElseThrow(ApiException::unknownCursor);
            list = notes.findOlderThan(childId, anchor.getObservedOn(), anchor.getId(), page);
        }
        return noteResponses(user, childId, list);
    }

    @Transactional(readOnly = true)
    public ObservationResponse getNote(AuthUser user, long childId, long noteId) {
        access.requireOwnerOrLinkedTeacher(user, childId);
        ObservationNote note = notes.findByIdAndChildId(noteId, childId).orElseThrow(ApiException::notFound);
        return noteResponses(user, childId, List.of(note)).getFirst();
    }

    private List<ObservationResponse> noteResponses(AuthUser user, long childId, List<ObservationNote> list) {
        Map<Long, String> names = names(list.stream().map(ObservationNote::getTeacherId).toList());
        Map<Long, AttachedCard> cards = user.isParent() || areCardsShared(childId)
                ? deliveries.findAllById(list.stream().map(ObservationNote::getCardDeliveryId)
                                .filter(java.util.Objects::nonNull).distinct().toList())
                        .stream().collect(Collectors.toMap(CardDelivery::getId, AttachedCard::from))
                : Map.of();
        return list.stream()
                .map(o -> ObservationResponse.from(o, names.getOrDefault(o.getTeacherId(), "선생님"),
                        o.getCardDeliveryId() == null ? null : cards.get(o.getCardDeliveryId())))
                .toList();
    }

    @Transactional
    public ObservationResponse createNote(AuthUser user, long childId, CreateObservationRequest req) {
        access.requireLinkedTeacher(user, childId);
        LocalDate today = LocalDate.now(clock.withZone(TimeConfig.SERVICE_ZONE));
        if (req.observedOn().isAfter(today)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "관찰일은 오늘이나 이전 날짜로 입력해 주세요.");
        }
        AttachedCard card = null;
        if (req.cardDeliveryId() != null) {
            // 이 아이가 받은 카드이고, 보호자가 카드 공유를 켠 경우만(카드 목록을 볼 수 있는 것과 같은 규칙). 아니면 없는 것처럼 404.
            if (!areCardsShared(childId)) throw ApiException.notFound();
            CardDelivery d = deliveries.findByIdAndChildId(req.cardDeliveryId(), childId)
                    .orElseThrow(ApiException::notFound);
            card = AttachedCard.from(d);
        }
        String title = req.title() == null || req.title().isBlank() ? null : req.title().strip();
        ObservationNote n = notes.save(new ObservationNote(childId, user.id(), title, req.content().trim(),
                req.observedOn(), req.cardDeliveryId(), clock.instant()));
        return ObservationResponse.from(n, nameOf(user.id()), card);
    }

    @Transactional
    public void deleteNote(AuthUser user, long childId, long noteId) {
        access.requireLinkedTeacher(user, childId);
        ObservationNote n = notes.findByIdAndChildId(noteId, childId)
                .filter(x -> x.getTeacherId().equals(user.id()))
                .orElseThrow(ApiException::notFound);
        notes.delete(n);
    }

    private boolean areCardsShared(long childId) {
        return shareSettings.findById(childId).map(s -> s.isCoachingCards()).orElse(false);
    }

    private ChildGoal ownGoal(AuthUser user, long childId, long goalId) {
        return goals.findByIdAndChildId(goalId, childId)
                .filter(g -> g.getTeacherId().equals(user.id()))
                .orElseThrow(ApiException::notFound);
    }

    private Map<Long, String> names(Collection<Long> ids) {
        return users.findAllById(ids.stream().distinct().toList()).stream()
                .collect(Collectors.toMap(User::getId, User::getDisplayName));
    }

    private String nameOf(long id) {
        return users.findById(id).map(User::getDisplayName).orElse("선생님");
    }
}
