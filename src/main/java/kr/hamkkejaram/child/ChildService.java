package kr.hamkkejaram.child;

import kr.hamkkejaram.child.ChildDtos.ChildResponse;
import kr.hamkkejaram.child.ChildDtos.CreateChildRequest;
import kr.hamkkejaram.child.ChildDtos.UpdateChildRequest;
import kr.hamkkejaram.common.ApiException;
import kr.hamkkejaram.common.AppProperties;
import kr.hamkkejaram.common.ErrorCode;
import kr.hamkkejaram.common.TimeConfig;
import kr.hamkkejaram.consent.Consent;
import kr.hamkkejaram.consent.ConsentRepository;
import kr.hamkkejaram.consent.ConsentType;
import kr.hamkkejaram.security.AuthUser;
import kr.hamkkejaram.share.ShareSetting;
import kr.hamkkejaram.share.ShareSettingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
public class ChildService {
    public static final int MAX_CHILDREN_PER_PARENT = 5;
    private static final int MAX_AGE_MONTHS = 83; // 만 6세 11개월까지

    private final ChildRepository children;
    private final ConsentRepository consents;
    private final ShareSettingRepository shareSettings;
    private final ChildAccess access;
    private final AppProperties props;
    private final Clock clock;

    public ChildService(ChildRepository children, ConsentRepository consents, ShareSettingRepository shareSettings,
                        ChildAccess access, AppProperties props, Clock clock) {
        this.children = children;
        this.consents = consents;
        this.shareSettings = shareSettings;
        this.access = access;
        this.props = props;
        this.clock = clock;
    }

    @Transactional
    public ChildResponse create(AuthUser user, CreateChildRequest req) {
        user.requireParent();
        var consent = req.guardianConsent();
        if (consent == null || !Boolean.TRUE.equals(consent.agreed())
                || !props.policy().guardianVersion().equals(consent.version())) {
            throw new ApiException(ErrorCode.GUARDIAN_CONSENT_REQUIRED);
        }
        if (children.countByParentId(user.id()) >= MAX_CHILDREN_PER_PARENT) {
            throw new ApiException(ErrorCode.CHILD_LIMIT_REACHED);
        }
        YearMonth birth = parseBirth(req.birthYearMonth());
        Instant now = clock.instant();
        Child child = children.save(new Child(user.id(), req.nickname().trim(), birth, now));
        consents.save(new Consent(user.id(), child.getId(), ConsentType.CHILD_DATA_GUARDIAN,
                props.policy().guardianVersion(), now));
        shareSettings.save(ShareSetting.allPrivate(child.getId(), now));
        return ChildResponse.from(child, today());
    }

    @Transactional(readOnly = true)
    public List<ChildResponse> list(AuthUser user) {
        user.requireParent();
        LocalDate today = today();
        return children.findByParentIdOrderByIdAsc(user.id()).stream()
                .map(c -> ChildResponse.from(c, today)).toList();
    }

    @Transactional(readOnly = true)
    public ChildResponse get(AuthUser user, long childId) {
        return ChildResponse.from(access.requireOwner(user, childId), today());
    }

    @Transactional
    public ChildResponse update(AuthUser user, long childId, UpdateChildRequest req) {
        Child child = access.requireOwner(user, childId);
        child.update(req.nickname() == null ? null : req.nickname().trim(),
                req.birthYearMonth() == null ? null : parseBirth(req.birthYearMonth()));
        return ChildResponse.from(child, today());
    }

    /**
     * 아동 삭제 = 법정대리인 동의 철회. 관련 데이터(AI 관찰 기록 포함)는 DB의 ON DELETE CASCADE로 함께 지워진다.
     * 그 아동에게 걸린 다른 동의(AI 관찰 기록 동의)에도 철회 시각을 남긴다.
     */
    @Transactional
    public void delete(AuthUser user, long childId) {
        Child child = access.requireOwner(user, childId);
        Instant now = clock.instant();
        consents.findByChildIdAndRevokedAtIsNull(child.getId()).forEach(c -> c.revoke(now));
        consents.flush();
        children.delete(child);
    }

    private YearMonth parseBirth(String value) {
        YearMonth birth = YearMonth.parse(value);
        YearMonth now = YearMonth.from(today());
        long months = ChronoUnit.MONTHS.between(birth, now);
        if (months < 0 || months > MAX_AGE_MONTHS) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "생년월은 만 0~6세 범위로 입력해 주세요.");
        }
        return birth;
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(TimeConfig.SERVICE_ZONE));
    }
}
