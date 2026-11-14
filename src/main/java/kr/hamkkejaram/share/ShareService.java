package kr.hamkkejaram.share;

import kr.hamkkejaram.child.Child;
import kr.hamkkejaram.child.ChildAccess;
import kr.hamkkejaram.child.ChildRepository;
import kr.hamkkejaram.common.ApiException;
import kr.hamkkejaram.common.ErrorCode;
import kr.hamkkejaram.common.TimeConfig;
import kr.hamkkejaram.aiobs.AiConsentService;
import kr.hamkkejaram.record.RecordDtos.WeekSummary;
import kr.hamkkejaram.record.WeeklySummaryService;
import kr.hamkkejaram.security.AuthUser;
import kr.hamkkejaram.share.ShareDtos.ChildBrief;
import kr.hamkkejaram.share.ShareDtos.InviteCodeResponse;
import kr.hamkkejaram.share.ShareDtos.LinkedTeacherResponse;
import kr.hamkkejaram.share.ShareDtos.ShareSettingsRequest;
import kr.hamkkejaram.share.ShareDtos.ShareSettingsResponse;
import kr.hamkkejaram.share.ShareDtos.SharedItem;
import kr.hamkkejaram.share.ShareDtos.SharedSummaryResponse;
import kr.hamkkejaram.share.ShareDtos.SharedWeek;
import kr.hamkkejaram.share.ShareDtos.TeacherChildResponse;
import kr.hamkkejaram.share.ShareDtos.UsableInviteCodeResponse;
import kr.hamkkejaram.user.User;
import kr.hamkkejaram.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ShareService {
    static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // 0·O·1·I 제외
    static final int CODE_LENGTH = 8;
    static final Duration CODE_TTL = Duration.ofHours(72);
    static final int MAX_USABLE_CODES = 3;
    /** 만료되었거나 사용된 코드를 이만큼 지난 뒤 지운다(누가 썼는지도 함께 사라진다). */
    static final Duration STALE_CODE_RETENTION = Duration.ofDays(7);
    private static final Logger log = LoggerFactory.getLogger(ShareService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ChildAccess access;
    private final ChildRepository children;
    private final ShareSettingRepository settings;
    private final InviteCodeRepository codes;
    private final TeacherLinkRepository links;
    private final UserRepository users;
    private final WeeklySummaryService weekly;
    private final AiConsentService aiConsent;
    private final Clock clock;

    public ShareService(ChildAccess access, ChildRepository children, ShareSettingRepository settings,
                        InviteCodeRepository codes, TeacherLinkRepository links, UserRepository users,
                        WeeklySummaryService weekly, AiConsentService aiConsent, Clock clock) {
        this.access = access;
        this.children = children;
        this.settings = settings;
        this.codes = codes;
        this.links = links;
        this.users = users;
        this.weekly = weekly;
        this.aiConsent = aiConsent;
        this.clock = clock;
    }

    // ---------- 부모 ----------

    @Transactional
    public ShareSettingsResponse getSettings(AuthUser user, long childId) {
        access.requireOwner(user, childId);
        return ShareSettingsResponse.from(settingOf(childId));
    }

    @Transactional
    public ShareSettingsResponse updateSettings(AuthUser user, long childId, ShareSettingsRequest req) {
        access.requireOwner(user, childId);
        ShareSetting s = settingOf(childId);
        Instant now = clock.instant();
        s.update(req.jointPlay(), req.responseLatency(), req.distress(), req.coachingCards(), now);
        s.updateAi(req.aiObservations(), req.institutionCapture(), now);
        return ShareSettingsResponse.from(s);
    }

    @Transactional
    public InviteCodeResponse issueInviteCode(AuthUser user, long childId) {
        access.requireOwner(user, childId);
        Instant now = clock.instant();
        if (codes.countUsable(childId, now) >= MAX_USABLE_CODES) {
            throw new ApiException(ErrorCode.INVITE_LIMIT_REACHED);
        }
        String code;
        do {
            code = randomCode();
        } while (codes.existsByCode(code));
        InviteCode saved = codes.save(new InviteCode(childId, code, now, now.plus(CODE_TTL)));
        return new InviteCodeResponse(saved.getCode(), saved.getExpiresAt());
    }

    /** 아직 쓸 수 있는(쓰지 않았고 만료 전) 코드만, 최신 발급순. */
    @Transactional(readOnly = true)
    public List<UsableInviteCodeResponse> usableInviteCodes(AuthUser user, long childId) {
        access.requireOwner(user, childId);
        return codes.findUsable(childId, clock.instant()).stream()
                .map(c -> new UsableInviteCodeResponse(c.getCode(), c.getExpiresAt(), c.getCreatedAt()))
                .toList();
    }

    /** 아직 쓸 수 있는 코드를 취소한다(행을 지운다). 이후 그 코드로 연결하면 INVITE_CODE_INVALID. */
    @Transactional
    public void revokeInviteCode(AuthUser user, long childId, String rawCode) {
        access.requireOwner(user, childId);
        Instant now = clock.instant();
        // 연결(link)과 같은 행 잠금을 써서, 선생님이 막 쓰는 중인 코드와 엇갈리지 않게 한다.
        InviteCode invite = codes.findByCodeForUpdate(normalize(rawCode))
                .filter(c -> c.getChildId().equals(childId) && c.isUsable(now))
                .orElseThrow(ApiException::notFound);
        codes.delete(invite);
    }

    @Transactional(readOnly = true)
    public List<LinkedTeacherResponse> linkedTeachers(AuthUser user, long childId) {
        access.requireOwner(user, childId);
        List<TeacherLink> list = links.findByChildIdOrderByLinkedAtAsc(childId);
        Map<Long, User> byId = users.findAllById(list.stream().map(TeacherLink::getTeacherId).toList()).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        return list.stream()
                .filter(l -> byId.containsKey(l.getTeacherId()))
                .map(l -> new LinkedTeacherResponse(l.getTeacherId(), byId.get(l.getTeacherId()).getDisplayName(),
                        l.getLinkedAt()))
                .toList();
    }

    @Transactional
    public void unlinkTeacher(AuthUser user, long childId, long teacherId) {
        access.requireOwner(user, childId);
        if (links.deleteLink(teacherId, childId) == 0) throw ApiException.notFound();
    }

    // ---------- 교사 ----------

    @Transactional(noRollbackFor = ApiException.class)
    public TeacherChildResponse link(AuthUser user, String rawCode) {
        user.requireTeacher();
        String code = normalize(rawCode);
        Instant now = clock.instant();
        InviteCode invite = codes.findByCodeForUpdate(code)
                .filter(c -> c.isUsable(now))
                .orElseThrow(() -> new ApiException(ErrorCode.INVITE_CODE_INVALID));
        if (links.existsByTeacherIdAndChildId(user.id(), invite.getChildId())) {
            throw new ApiException(ErrorCode.ALREADY_LINKED);
        }
        invite.markUsed(user.id(), now);
        TeacherLink link = links.save(new TeacherLink(user.id(), invite.getChildId(), now));
        Child child = children.findById(invite.getChildId()).orElseThrow(ApiException::notFound);
        return new TeacherChildResponse(child.getId(), child.getNickname(), child.ageMonths(today()),
                link.getLinkedAt());
    }

    /** 선생님이 스스로 연결을 끊는다. 이미 쓴 목표·관찰 메모는 남는다(TCH-07). */
    @Transactional
    public void unlinkSelf(AuthUser user, long childId) {
        user.requireTeacher();
        if (links.deleteLink(user.id(), childId) == 0) throw ApiException.notFound();
    }

    @Transactional(readOnly = true)
    public List<TeacherChildResponse> teacherChildren(AuthUser user) {
        user.requireTeacher();
        LocalDate today = today();
        List<TeacherLink> list = links.findByTeacherIdOrderByLinkedAtDesc(user.id());
        Map<Long, Child> byId = children.findAllById(list.stream().map(TeacherLink::getChildId).toList()).stream()
                .collect(Collectors.toMap(Child::getId, Function.identity()));
        return list.stream()
                .filter(l -> byId.containsKey(l.getChildId()))
                .map(l -> {
                    Child c = byId.get(l.getChildId());
                    return new TeacherChildResponse(c.getId(), c.getNickname(), c.ageMonths(today), l.getLinkedAt());
                })
                .toList();
    }

    @Transactional
    public SharedSummaryResponse sharedSummary(AuthUser user, long childId, int weeks) {
        Child child = access.requireLinkedTeacher(user, childId);
        ShareSetting s = settingOf(childId);
        List<SharedItem> items = new ArrayList<>();
        if (s.isJointPlay()) items.add(SharedItem.JOINT_PLAY);
        if (s.isResponseLatency()) items.add(SharedItem.RESPONSE_LATENCY);
        if (s.isDistress()) items.add(SharedItem.DISTRESS);
        if (s.isCoachingCards()) items.add(SharedItem.COACHING_CARDS);
        if (s.isAiObservations()) items.add(SharedItem.AI_OBSERVATIONS);
        if (s.isInstitutionCapture() && aiConsent.hasActiveConsent(childId)) items.add(SharedItem.INSTITUTION_CAPTURE);

        boolean anyMetric = s.isJointPlay() || s.isResponseLatency() || s.isDistress();
        List<SharedWeek> shared = !anyMetric ? List.of() : weekly.summarize(childId, weeks).stream()
                .map(w -> filter(w, s)).toList();
        return new SharedSummaryResponse(new ChildBrief(child.getId(), child.getNickname(), child.ageMonths(today())),
                items, shared);
    }

    private static SharedWeek filter(WeekSummary w, ShareSetting s) {
        return new SharedWeek(w.weekStart(), w.recordCount(),
                s.isJointPlay() ? w.jointPlayRatio() : null,
                s.isJointPlay() ? w.jointPlaySeconds() : null,
                s.isResponseLatency() ? w.responseLatencySec() : null,
                s.isDistress() ? w.distressPerTenMinutes() : null);
    }

    // ---------- 정리 ----------

    /** 만료된 지 7일 넘은 코드와 사용된 지 7일 넘은 코드를 지운다. 매일 04:27(서버 시간대). */
    @Scheduled(cron = "0 27 4 * * *")
    @Transactional
    public int purgeStaleInviteCodes() {
        int deleted = codes.deleteStaleBefore(clock.instant().minus(STALE_CODE_RETENTION));
        if (deleted > 0) {
            log.info("Purged {} stale invite codes", deleted);
        }
        return deleted;
    }

    private static String normalize(String rawCode) {
        return rawCode.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
    }

    private ShareSetting settingOf(long childId) {
        return settings.findById(childId)
                .orElseGet(() -> settings.save(ShareSetting.allPrivate(childId, clock.instant())));
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(TimeConfig.SERVICE_ZONE));
    }

    static String randomCode() {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) sb.append(CODE_ALPHABET.charAt(RANDOM.nextInt(CODE_ALPHABET.length())));
        return sb.toString();
    }
}
