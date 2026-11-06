package kr.hamkkejaram.share;

import kr.hamkkejaram.child.Child;
import kr.hamkkejaram.child.ChildAccess;
import kr.hamkkejaram.child.ChildRepository;
import kr.hamkkejaram.common.ApiException;
import kr.hamkkejaram.common.ErrorCode;
import kr.hamkkejaram.common.TimeConfig;
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
import kr.hamkkejaram.user.User;
import kr.hamkkejaram.user.UserRepository;
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
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ChildAccess access;
    private final ChildRepository children;
    private final ShareSettingRepository settings;
    private final InviteCodeRepository codes;
    private final TeacherLinkRepository links;
    private final UserRepository users;
    private final WeeklySummaryService weekly;
    private final Clock clock;

    public ShareService(ChildAccess access, ChildRepository children, ShareSettingRepository settings,
                        InviteCodeRepository codes, TeacherLinkRepository links, UserRepository users,
                        WeeklySummaryService weekly, Clock clock) {
        this.access = access;
        this.children = children;
        this.settings = settings;
        this.codes = codes;
        this.links = links;
        this.users = users;
        this.weekly = weekly;
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
        s.update(req.jointPlay(), req.responseLatency(), req.distress(), req.coachingCards(), clock.instant());
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
        String code = rawCode.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
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
