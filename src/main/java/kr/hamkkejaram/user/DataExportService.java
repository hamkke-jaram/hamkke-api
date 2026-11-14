package kr.hamkkejaram.user;

import jakarta.persistence.EntityManager;
import kr.hamkkejaram.aiobs.AiObservation;
import kr.hamkkejaram.aiobs.ObservationSource;
import kr.hamkkejaram.card.CardDelivery;
import kr.hamkkejaram.child.Child;
import kr.hamkkejaram.child.ChildRepository;
import kr.hamkkejaram.common.ApiException;
import kr.hamkkejaram.common.ErrorCode;
import kr.hamkkejaram.consent.ConsentRepository;
import kr.hamkkejaram.note.ChildGoal;
import kr.hamkkejaram.note.NoteDtos.GoalResponse;
import kr.hamkkejaram.note.NoteDtos.ObservationResponse;
import kr.hamkkejaram.note.ObservationNote;
import kr.hamkkejaram.record.InteractionRecord;
import kr.hamkkejaram.record.RecordDtos.RecordResponse;
import kr.hamkkejaram.share.ShareDtos.ShareSettingsResponse;
import kr.hamkkejaram.share.ShareSetting;
import kr.hamkkejaram.share.TeacherLink;
import kr.hamkkejaram.user.DataExportDtos.Account;
import kr.hamkkejaram.user.DataExportDtos.AiObservationItem;
import kr.hamkkejaram.user.DataExportDtos.CardDeliveryItem;
import kr.hamkkejaram.user.DataExportDtos.ChildExport;
import kr.hamkkejaram.user.DataExportDtos.DataExport;
import kr.hamkkejaram.user.DataExportDtos.LinkedTeacher;
import kr.hamkkejaram.user.DataExportDtos.TeacherExport;
import kr.hamkkejaram.user.DataExportDtos.TeacherGoalItem;
import kr.hamkkejaram.user.DataExportDtos.TeacherLinkItem;
import kr.hamkkejaram.user.DataExportDtos.TeacherNoteItem;
import kr.hamkkejaram.user.MeController.ConsentResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 내 데이터 내려받기(ACC-11, 개인정보 열람권). 요청한 사람 본인의 데이터만 JSON 한 덩어리로 모은다.
 * 비밀번호 해시·리프레시 토큰·영수증·분석 토큰(session_jti)은 담지 않고, 내용은 로그에 남기지 않는다.
 * 다른 화면의 목록 조회(페이지 나눔)와 섞이지 않도록 조회문을 여기서 직접 쓴다.
 */
@Service
public class DataExportService {
    static final String NOTICE = "함께자람에 저장된 내 데이터예요. 영상·음성·사진은 서버에 저장하지 않아 담겨 있지 않아요.";
    private static final String TEACHER_FALLBACK = "선생님";

    private final UserRepository users;
    private final ChildRepository children;
    private final ConsentRepository consents;
    private final EntityManager em;
    private final Clock clock;

    public DataExportService(UserRepository users, ChildRepository children, ConsentRepository consents,
                             EntityManager em, Clock clock) {
        this.users = users;
        this.children = children;
        this.consents = consents;
        this.em = em;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public DataExport export(long userId) {
        User u = users.findById(userId).orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED));
        Account account = new Account(u.getId(), u.getEmail(), u.getDisplayName(), u.getRole(), u.getCreatedAt());
        List<ConsentResponse> consentList = consents.findByUserIdOrderByAgreedAtDesc(userId).stream()
                .map(ConsentResponse::from).toList();
        if (u.getRole() == Role.PARENT) {
            List<ChildExport> list = children.findByParentIdOrderByIdAsc(userId).stream()
                    .map(this::childExport).toList();
            return new DataExport(DataExportDtos.FORMAT, clock.instant(), NOTICE, account, consentList, list, null);
        }
        return new DataExport(DataExportDtos.FORMAT, clock.instant(), NOTICE, account, consentList, null,
                teacherExport(userId));
    }

    private ChildExport childExport(Child c) {
        long childId = c.getId();
        List<TeacherLink> links = em.createQuery(
                        "select l from TeacherLink l where l.childId = :c order by l.linkedAt asc", TeacherLink.class)
                .setParameter("c", childId).getResultList();
        List<InteractionRecord> records = em.createQuery(
                        "select r from InteractionRecord r where r.childId = :c order by r.recordedAt desc, r.id desc",
                        InteractionRecord.class)
                .setParameter("c", childId).getResultList();
        List<CardDelivery> deliveries = em.createQuery(
                        "select d from CardDelivery d join fetch d.card where d.childId = :c "
                                + "order by d.deliveredAt desc, d.id desc", CardDelivery.class)
                .setParameter("c", childId).getResultList();
        List<ChildGoal> goals = em.createQuery(
                        "select g from ChildGoal g where g.childId = :c order by g.createdAt desc, g.id desc",
                        ChildGoal.class)
                .setParameter("c", childId).getResultList();
        List<ObservationNote> notes = em.createQuery(
                        "select n from ObservationNote n where n.childId = :c order by n.observedOn desc, n.id desc",
                        ObservationNote.class)
                .setParameter("c", childId).getResultList();
        List<AiObservation> observations = em.createQuery(
                        "select o from AiObservation o where o.childId = :c order by o.recordedAt desc, o.id desc",
                        AiObservation.class)
                .setParameter("c", childId).getResultList();

        Set<Long> userIds = new HashSet<>();
        links.forEach(l -> userIds.add(l.getTeacherId()));
        goals.forEach(g -> userIds.add(g.getTeacherId()));
        notes.forEach(n -> userIds.add(n.getTeacherId()));
        observations.stream().filter(o -> o.getSource() == ObservationSource.INSTITUTION && o.getCreatedBy() != null)
                .forEach(o -> userIds.add(o.getCreatedBy()));
        Map<Long, String> names = names(userIds);

        ShareSetting setting = em.find(ShareSetting.class, childId);
        return new ChildExport(childId, c.getNickname(), c.getBirthYearMonth(), c.getCreatedAt(),
                setting == null ? null : ShareSettingsResponse.from(setting),
                links.stream().map(l -> new LinkedTeacher(nameOf(names, l.getTeacherId()), l.getLinkedAt())).toList(),
                records.stream().map(RecordResponse::from).toList(),
                deliveries.stream().map(d -> new CardDeliveryItem(d.getId(), d.getDeliveredAt(), d.getTriedAt(),
                        d.getRecordId(), d.getAiObservationId(), d.getCard().getId(), d.getCard().getTitle())).toList(),
                goals.stream().map(g -> GoalResponse.from(g, nameOf(names, g.getTeacherId()))).toList(),
                notes.stream().map(n -> ObservationResponse.from(n, nameOf(names, n.getTeacherId()))).toList(),
                observations.stream().map(o -> new AiObservationItem(o.getId(), o.getSource(), o.getRecordedAt(),
                        o.getSummary(), o.childStates(), o.adultResponses(), o.moments(), o.getPeopleCount(),
                        o.getModelVersion(), o.getPromptVersion(), o.getFrameCount(),
                        o.getSource() == ObservationSource.INSTITUTION && o.getCreatedBy() != null
                                ? nameOf(names, o.getCreatedBy()) : null,
                        o.getCreatedAt())).toList());
    }

    private TeacherExport teacherExport(long teacherId) {
        List<TeacherLink> links = em.createQuery(
                        "select l from TeacherLink l where l.teacherId = :t order by l.linkedAt desc", TeacherLink.class)
                .setParameter("t", teacherId).getResultList();
        Map<Long, String> nicknames = children.findAllById(links.stream().map(TeacherLink::getChildId).toList())
                .stream().collect(Collectors.toMap(Child::getId, Child::getNickname));
        List<ChildGoal> goals = em.createQuery(
                        "select g from ChildGoal g where g.teacherId = :t order by g.createdAt desc, g.id desc",
                        ChildGoal.class)
                .setParameter("t", teacherId).getResultList();
        List<ObservationNote> notes = em.createQuery(
                        "select n from ObservationNote n where n.teacherId = :t order by n.observedOn desc, n.id desc",
                        ObservationNote.class)
                .setParameter("t", teacherId).getResultList();
        return new TeacherExport(
                links.stream().map(l -> new TeacherLinkItem(l.getChildId(),
                        nicknames.get(l.getChildId()), l.getLinkedAt())).toList(),
                goals.stream().map(g -> new TeacherGoalItem(g.getId(), g.getChildId(), g.getTitle(),
                        g.getDescription(), g.getStatus(), g.getCreatedAt(), g.getUpdatedAt())).toList(),
                notes.stream().map(n -> new TeacherNoteItem(n.getId(), n.getChildId(), n.getContent(),
                        n.getObservedOn(), n.getCreatedAt())).toList());
    }

    private Map<Long, String> names(Collection<Long> ids) {
        if (ids.isEmpty()) return Map.of();
        return users.findAllById(ids).stream().collect(Collectors.toMap(User::getId, User::getDisplayName));
    }

    private static String nameOf(Map<Long, String> names, Long id) {
        return names.getOrDefault(id, TEACHER_FALLBACK);
    }
}
