package kr.hamkkejaram.consent;

import kr.hamkkejaram.common.ApiException;
import kr.hamkkejaram.common.AppProperties;
import kr.hamkkejaram.common.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 계정 단위 동의(이용약관·개인정보 처리방침)의 재동의와, 주인이 없어진 동의 기록의 파기.
 *
 * <ul>
 *   <li>재동의(ACC-10): 문서 버전({@code app.policy.*})이 바뀌면, 가장 최근에 한(철회하지 않은) 동의의 버전이
 *       현재 버전과 다른 항목을 {@code pendingConsents}로 알려 주고, {@code POST /me/consents}로 새 버전 동의를 받는다.</li>
 *   <li>보관·파기(ACC-12): 탈퇴·아이 삭제로 주인이 없어진 동의 기록은 철회 시각부터
 *       {@code app.policy.consent-retention}(기본 3년)이 지나면 매일 새벽 지운다.</li>
 * </ul>
 */
@Service
public class ConsentService {
    private static final Logger log = LoggerFactory.getLogger(ConsentService.class);

    /** 계정 단위로 다시 동의를 받는 항목. 아이별 동의(법정대리인·AI)는 아이 화면에서 따로 다룬다. */
    public static final Set<ConsentType> ACCOUNT_TYPES =
            EnumSet.of(ConsentType.TERMS_OF_SERVICE, ConsentType.PRIVACY_POLICY);
    /** 아이별 동의. child_id가 비어 있으면 그 아이가 삭제된 것이다. */
    static final List<ConsentType> CHILD_TYPES =
            List.of(ConsentType.CHILD_DATA_GUARDIAN, ConsentType.CHILD_IMAGE_AI_ANALYSIS);

    private final ConsentRepository consents;
    private final AppProperties.Policy policy;
    private final Duration retention;
    private final Clock clock;

    public ConsentService(ConsentRepository consents, AppProperties props,
                          @Value("${app.policy.consent-retention:1095d}") Duration retention, Clock clock) {
        if (retention.isNegative() || retention.isZero()) {
            throw new IllegalStateException("app.policy.consent-retention must be positive");
        }
        this.consents = consents;
        this.policy = props.policy();
        this.retention = retention;
        this.clock = clock;
    }

    public String currentVersion(ConsentType type) {
        return switch (type) {
            case TERMS_OF_SERVICE -> policy.termsVersion();
            case PRIVACY_POLICY -> policy.privacyVersion();
            case CHILD_DATA_GUARDIAN -> policy.guardianVersion();
            case CHILD_IMAGE_AI_ANALYSIS -> policy.aiAnalysisVersion();
        };
    }

    /** 다시 동의를 받아야 하는 계정 단위 항목(이용약관 → 개인정보 순). 평소에는 빈 목록. */
    @Transactional(readOnly = true)
    public List<ConsentType> pendingConsents(long userId) {
        List<Consent> mine = consents.findByUserIdOrderByAgreedAtDesc(userId);
        List<ConsentType> pending = new ArrayList<>();
        for (ConsentType type : List.of(ConsentType.TERMS_OF_SERVICE, ConsentType.PRIVACY_POLICY)) {
            Optional<Consent> latest = mine.stream()
                    .filter(c -> c.getType() == type && c.getChildId() == null && c.getRevokedAt() == null)
                    .max(Comparator.comparing(Consent::getAgreedAt).thenComparing(Consent::getId));
            if (latest.isEmpty() || !currentVersion(type).equals(latest.get().getVersion())) {
                pending.add(type);
            }
        }
        return pending;
    }

    /**
     * 계정 단위 동의를 남긴다. 현재 문서 버전에만 동의할 수 있다(아니면 400 VALIDATION_FAILED).
     * 이미 현재 버전에 동의해 있으면 새로 남기지 않는다(같은 요청을 여러 번 보내도 된다).
     */
    @Transactional
    public void agree(long userId, ConsentType type, String version) {
        if (!ACCOUNT_TYPES.contains(type)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "여기서는 이용약관과 개인정보 처리방침 동의만 받아요.");
        }
        String current = currentVersion(type);
        if (!current.equals(version)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "동의 문서가 바뀌었어요. 새로고침한 뒤 다시 확인해 주세요.");
        }
        boolean already = consents.findByUserIdOrderByAgreedAtDesc(userId).stream()
                .anyMatch(c -> c.getType() == type && c.getChildId() == null && c.getRevokedAt() == null
                        && current.equals(c.getVersion()));
        if (!already) {
            consents.save(new Consent(userId, null, type, current, clock.instant()));
        }
    }

    public Duration retention() {
        return retention;
    }

    /**
     * 주인이 없어진 동의 기록 파기. 대상은 철회 시각이 보관 기간보다 오래된 기록 중
     * (a) 계정이 지워진 기록(user_id 없음) 또는 (b) 아이가 지워진 아이별 동의(child_id 없음)다.
     * 계정이 남아 있는 사람의 계정 단위 동의는 지우지 않는다(내 동의 이력에서 계속 볼 수 있다).
     */
    @Scheduled(cron = "0 37 4 * * *")
    @Transactional
    public int purgeOrphanedConsents() {
        Instant cutoff = clock.instant().minus(retention);
        int deleted = consents.deleteOrphanedRevokedBefore(cutoff, CHILD_TYPES);
        if (deleted > 0) {
            log.info("Purged {} orphaned consent rows past retention", deleted);
        }
        return deleted;
    }
}
