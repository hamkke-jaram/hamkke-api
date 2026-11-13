package kr.hamkkejaram.aiobs;

import kr.hamkkejaram.aiobs.AiObservationDtos.AiConsentRequest;
import kr.hamkkejaram.aiobs.AiObservationDtos.AiConsentResponse;
import kr.hamkkejaram.child.ChildAccess;
import kr.hamkkejaram.common.ApiException;
import kr.hamkkejaram.common.AppProperties;
import kr.hamkkejaram.common.ErrorCode;
import kr.hamkkejaram.consent.Consent;
import kr.hamkkejaram.consent.ConsentRepository;
import kr.hamkkejaram.consent.ConsentType;
import kr.hamkkejaram.security.AuthUser;
import kr.hamkkejaram.share.ShareSettingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 아이별 'AI 관찰 기록' 동의({@code CHILD_IMAGE_AI_ANALYSIS}, 법정대리인). docs/ai-observation.md 2장·7장.
 * 동의 없이는 분석 토큰이 발급되지 않고(=사진이 한 장도 나가지 않고), 철회하면 그 아이의 AI 관찰 기록이 모두 지워진다.
 */
@Service
public class AiConsentService {
    private static final Logger log = LoggerFactory.getLogger(AiConsentService.class);
    private static final ConsentType TYPE = ConsentType.CHILD_IMAGE_AI_ANALYSIS;

    private final ChildAccess access;
    private final ConsentRepository consents;
    private final AiObservationRepository observations;
    private final ShareSettingRepository shareSettings;
    private final AiSettings settings;
    private final String currentVersion;
    private final Clock clock;

    public AiConsentService(ChildAccess access, ConsentRepository consents, AiObservationRepository observations,
                            ShareSettingRepository shareSettings, AiSettings settings, AppProperties props,
                            Clock clock) {
        this.access = access;
        this.consents = consents;
        this.observations = observations;
        this.shareSettings = shareSettings;
        this.settings = settings;
        this.currentVersion = props.policy().aiAnalysisVersion();
        this.clock = clock;
    }

    /**
     * 지금 유효한 동의가 있는지: 철회되지 않았고, 현재 동의 문서 버전에 대한 것.
     * 문서 버전이 바뀌면 예전 버전의 동의로는 사진을 보내지 않는다(다시 동의를 받아야 한다).
     */
    @Transactional(readOnly = true)
    public boolean hasActiveConsent(long childId) {
        return latestActive(childId).map(c -> currentVersion.equals(c.getVersion())).orElse(false);
    }

    @Transactional(readOnly = true)
    public AiConsentResponse get(AuthUser user, long childId) {
        access.requireOwner(user, childId);
        return latestActive(childId).map(this::response).orElseGet(AiConsentResponse::none);
    }

    @Transactional
    public AiConsentResponse agree(AuthUser user, long childId, AiConsentRequest req) {
        access.requireOwner(user, childId);
        settings.requireEnabled(); // 쓸 수 없는 기능에 대한 동의는 받지 않는다
        if (!currentVersion.equals(req.version())) {
            throw new ApiException(ErrorCode.AI_CONSENT_VERSION_MISMATCH);
        }
        // DB(DATETIME(6))와 같은 마이크로초 단위로 맞춘다: 방금 한 응답과 다시 조회한 응답의 시각이 같아야 한다.
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        List<Consent> active = consents.findByChildIdAndTypeAndRevokedAtIsNull(childId, TYPE);
        Optional<Consent> current = active.stream().filter(c -> currentVersion.equals(c.getVersion())).findFirst();
        if (current.isPresent()) {
            return response(current.get()); // 이미 동의함: 처음 동의한 시각을 그대로 둔다
        }
        // 예전 버전의 동의는 새 동의로 바뀐다(기록은 지우지 않는다. 지우는 것은 보호자가 끌 때뿐이다).
        active.forEach(c -> c.revoke(now));
        Consent saved = consents.save(new Consent(user.id(), childId, TYPE, currentVersion, now));
        log.info("ai consent recorded: childId={} version={}", childId, currentVersion);
        return response(saved);
    }

    /**
     * 철회 = 삭제. 동의에 철회 시각을 남기고, 그 아이의 AI 관찰 기록(과 그 기록으로 전달된 카드 이력)을 모두 지운다.
     * 다시 켰을 때 예전 공유가 조용히 되살아나지 않도록 AI 관련 공유 설정 두 항목도 끈다.
     */
    @Transactional
    public void revoke(AuthUser user, long childId) {
        access.requireOwner(user, childId);
        Instant now = clock.instant();
        consents.findByChildIdAndTypeAndRevokedAtIsNull(childId, TYPE).forEach(c -> c.revoke(now));
        shareSettings.findById(childId).ifPresent(s -> {
            if (s.isAiObservations() || s.isInstitutionCapture()) s.updateAi(false, false, now);
        });
        consents.flush();
        int deleted = observations.deleteByChildId(childId);
        log.info("ai consent revoked: childId={} deletedObservations={}", childId, deleted);
    }

    private Optional<Consent> latestActive(long childId) {
        return consents.findByChildIdAndTypeAndRevokedAtIsNull(childId, TYPE).stream()
                .max(Comparator.comparing(Consent::getAgreedAt).thenComparing(Consent::getId));
    }

    private AiConsentResponse response(Consent c) {
        return new AiConsentResponse(currentVersion.equals(c.getVersion()), c.getVersion(), c.getAgreedAt());
    }
}
