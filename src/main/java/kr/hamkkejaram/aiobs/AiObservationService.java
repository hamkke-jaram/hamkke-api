package kr.hamkkejaram.aiobs;

import kr.hamkkejaram.aiobs.AiObservationDtos.AiSessionResponse;
import kr.hamkkejaram.aiobs.AiObservationDtos.HomeScene;
import kr.hamkkejaram.aiobs.AiObservationDtos.ObservationResponse;
import kr.hamkkejaram.aiobs.AiObservationDtos.SaveObservationRequest;
import kr.hamkkejaram.aiobs.AiObservationDtos.SaveObservationResponse;
import kr.hamkkejaram.child.ChildAccess;
import kr.hamkkejaram.common.ApiException;
import kr.hamkkejaram.common.ErrorCode;
import kr.hamkkejaram.security.AuthUser;
import kr.hamkkejaram.security.RateLimiter;
import kr.hamkkejaram.share.ShareSetting;
import kr.hamkkejaram.share.ShareSettingRepository;
import kr.hamkkejaram.user.User;
import kr.hamkkejaram.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 분석 토큰 발급과 AI 관찰 기록 저장·조회·삭제(docs/ai-observation.md 4장·7장).
 *
 * <p>서버는 사진을 받지 않는다. 관찰 기록은 AI 서버가 서명한 영수증으로만 저장된다.
 * 로그에는 관찰 문장·영수증·토큰을 남기지 않는다(아이 id, 출처, 사유 코드만).
 */
@Service
public class AiObservationService {
    private static final Logger log = LoggerFactory.getLogger(AiObservationService.class);
    private static final Duration CLOCK_SKEW = Duration.ofMinutes(10);
    static final int MAX_LIST = 50;

    private final ChildAccess access;
    private final AiConsentService consent;
    private final ShareSettingRepository shareSettings;
    private final AiSettings settings;
    private final AiSessionTokens sessionTokens;
    private final AiReceiptVerifier receipts;
    private final AiObservationRepository observations;
    private final UsedReceipts usedReceipts;
    private final UserRepository users;
    private final RateLimiter rateLimiter;
    private final Clock clock;

    public AiObservationService(ChildAccess access, AiConsentService consent, ShareSettingRepository shareSettings,
                                AiSettings settings, AiSessionTokens sessionTokens, AiReceiptVerifier receipts,
                                AiObservationRepository observations, UsedReceipts usedReceipts,
                                UserRepository users, RateLimiter rateLimiter,
                                Clock clock) {
        this.access = access;
        this.consent = consent;
        this.shareSettings = shareSettings;
        this.settings = settings;
        this.sessionTokens = sessionTokens;
        this.receipts = receipts;
        this.observations = observations;
        this.usedReceipts = usedReceipts;
        this.users = users;
        this.rateLimiter = rateLimiter;
        this.clock = clock;
    }

    /** 분석 토큰 발급. 동의가 없으면 발급하지 않는다 = 사진이 한 장도 나가지 않는다. */
    @Transactional(readOnly = true)
    public AiSessionResponse issueSession(AuthUser user, long childId) {
        CaptureAccess capture = requireCaptureAllowed(user, childId);
        rateLimiter.checkAiSession(user.id());
        AiSessionTokens.Issued issued = sessionTokens.issue(user.id(), childId, capture.source(), capture.maxFrames());
        log.info("ai session issued: childId={} source={}", childId, capture.source());
        return new AiSessionResponse(issued.token(), issued.expiresAt(), issued.maxFrames());
    }

    /** 영수증을 검증해 관찰 기록을 저장한다. 가정 기록은 선생님 검토 대기로 저장한다. */
    @Transactional
    public SaveObservationResponse save(AuthUser user, long childId, SaveObservationRequest req) {
        // 동의 철회(AiConsentService.revoke)와 줄 세운다(AIO-03). 잠근 뒤에 동의를 읽어야 막 커밋된 철회를 본다.
        access.lockChild(childId);
        CaptureAccess capture = requireCaptureAllowed(user, childId);
        ObservationSource source = capture.source();

        AiReceiptVerifier.Verified receipt;
        try {
            receipt = receipts.verify(req.receipt());
        } catch (InvalidReceiptException e) {
            log.warn("ai receipt rejected: childId={} reason={}", childId, e.reason());
            throw new ApiException(ErrorCode.AI_RECEIPT_INVALID);
        }
        // 영수증은 "이 사용자가 이 아이를 이 자리에서" 분석한 것이어야 한다.
        if (receipt.userId() != user.id() || receipt.childId() != childId || receipt.source() != source) {
            log.warn("ai receipt rejected: childId={} reason=subject_mismatch", childId);
            throw new ApiException(ErrorCode.AI_RECEIPT_INVALID);
        }
        if (receipt.frameCount() > capture.maxFrames()) {
            log.warn("ai receipt rejected: childId={} reason=consent_frame_limit", childId);
            throw new ApiException(ErrorCode.AI_RECEIPT_INVALID);
        }
        if (receipt.content().isEmpty()) {
            // 문장 검사에서 모두 버려져 남길 것이 없다
            log.warn("ai receipt rejected: childId={} reason=nothing_to_store", childId);
            throw new ApiException(ErrorCode.AI_RECEIPT_INVALID);
        }

        // DB(DATETIME(6))와 같은 마이크로초 단위로 맞춘다: 저장 응답과 목록 조회의 시각이 같아야 한다.
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Instant recordedAt = req.recordedAt() == null ? now : req.recordedAt().truncatedTo(ChronoUnit.MICROS);
        if (recordedAt.isAfter(now.plus(CLOCK_SKEW))) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "기록 시각이 미래일 수 없어요.");
        }
        // 영수증 하나는 한 번만 저장한다(AIO-07). 그 영수증으로 만든 기록을 지운 뒤에도 마찬가지다:
        // 쓴 jti 는 기록과 따로 남아 있어서(UsedReceipts), 지운 기록을 같은 영수증으로 되살릴 수 없다.
        // 두 번째 확인은 V6 이전에 저장된 기록(쓴 영수증 표에 없는 jti)을 위한 것이다.
        if (usedReceipts.isUsed(receipt.jti()) || observations.existsBySessionJti(receipt.jti())) {
            log.warn("ai receipt rejected: childId={} reason=already_used", childId);
            throw new ApiException(ErrorCode.DUPLICATE_REQUEST);
        }
        // 기록과 같은 트랜잭션에 남긴다: 아래 저장이 되돌려지면 이 표시도 함께 되돌려진다.
        // 동시에 들어온 같은 영수증은 아이 행 잠금이 줄 세우고, 그래도 겹치면 DB의 기본 키·UNIQUE(session_jti)가 막는다
        // → 409 DUPLICATE_REQUEST
        usedReceipts.markUsed(receipt.jti(), receipt.expiresAt());
        AiObservation saved = observations.saveAndFlush(new AiObservation(childId, user.id(), source, recordedAt,
                receipt.content(), receipt.modelVersion(), receipt.promptVersion(), receipt.frameCount(),
                receipt.jti(), now));

        log.info("ai observation saved: childId={} source={} frames={}", childId, source, saved.getFrameCount());
        return new SaveObservationResponse(ObservationResponse.from(saved, authorName(saved, null), user.id(), user.isTeacher()), null);
    }

    /** 보호자는 전부. 선생님은 기관 기록은 항상, 가정 기록은 보호자가 공유를 켠 경우만. */
    @Transactional(readOnly = true)
    public List<ObservationResponse> list(AuthUser user, long childId, int limit) {
        return list(user, childId, limit, null);
    }

    /** beforeId가 있으면 그 기록보다 오래된 것만(같은 최신순). 기준 기록도 그 사람이 볼 수 있는 기록이어야 한다. */
    @Transactional(readOnly = true)
    public List<ObservationResponse> list(AuthUser user, long childId, int limit, Long beforeId) {
        access.requireOwnerOrLinkedTeacher(user, childId);
        Set<ObservationSource> sources = EnumSet.allOf(ObservationSource.class);
        if (user.isTeacher()) {
            boolean homeShared = shareSettings.findById(childId).map(ShareSetting::isAiObservations).orElse(false);
            if (!homeShared || !consent.hasActiveConsent(childId)) sources = EnumSet.of(ObservationSource.INSTITUTION);
        }
        int n = Math.max(1, Math.min(MAX_LIST, limit));
        // 보호자가 선생님에게 보내지 않기로 한 기록은 선생님 목록에서 뺀다.
        boolean includeHidden = !user.isTeacher();
        List<AiObservation> found;
        if (beforeId == null) {
            found = observations.findRecent(childId, sources, includeHidden, PageRequest.of(0, n));
        } else {
            Set<ObservationSource> visible = sources;
            AiObservation anchor = observations.findByIdAndChildId(beforeId, childId)
                    .filter(o -> visible.contains(o.getSource()))
                    .filter(o -> includeHidden || !o.isHiddenFromTeacher())
                    .orElseThrow(ApiException::unknownCursor);
            found = observations.findOlderThan(childId, sources, includeHidden, anchor.getRecordedAt(), anchor.getId(),
                    PageRequest.of(0, n));
        }

        Map<Long, String> names = users.findAllById(found.stream()
                        .filter(o -> o.getSource() == ObservationSource.INSTITUTION)
                        .map(AiObservation::getCreatedBy).filter(Objects::nonNull).distinct().toList())
                .stream().collect(Collectors.toMap(User::getId, User::getDisplayName));
        return found.stream().map(o -> ObservationResponse.from(o, authorName(o, names), user.id(), user.isTeacher())).toList();
    }

    /**
     * 가정 장면 요약 목록(최신순). 원본 관찰 대신 줄인 형태만 돌려준다.
     * <ul>
     *   <li>보호자: 자기 아이의 가정 기록 전부(선생님에게 보내지 않기로 한 것 포함 — 미리보기 용도).</li>
     *   <li>선생님: 'AI 관찰 기록' 공유가 켜져 있고 AI 동의가 살아 있을 때만. 아니면 404(공유 여부를 드러내지 않음).
     *       보호자가 보내지 않기로 한 기록은 빠진다.</li>
     * </ul>
     */
    @Transactional(readOnly = true)
    public List<HomeScene> homeScenes(AuthUser user, long childId, int limit, Long beforeId) {
        access.requireOwnerOrLinkedTeacher(user, childId);
        if (user.isTeacher()) {
            boolean homeShared = shareSettings.findById(childId).map(ShareSetting::isAiObservations).orElse(false);
            if (!homeShared || !consent.hasActiveConsent(childId)) throw ApiException.notFound();
        }
        Set<ObservationSource> home = EnumSet.of(ObservationSource.HOME);
        boolean includeHidden = !user.isTeacher();
        int n = Math.max(1, Math.min(MAX_LIST, limit));
        List<AiObservation> found;
        if (beforeId == null) {
            found = observations.findRecent(childId, home, includeHidden, PageRequest.of(0, n));
        } else {
            AiObservation anchor = observations.findByIdAndChildId(beforeId, childId)
                    .filter(o -> o.getSource() == ObservationSource.HOME)
                    .filter(o -> includeHidden || !o.isHiddenFromTeacher())
                    .orElseThrow(ApiException::unknownCursor);
            found = observations.findOlderThan(childId, home, includeHidden, anchor.getRecordedAt(), anchor.getId(),
                    PageRequest.of(0, n));
        }
        return found.stream().map(HomeScene::from).toList();
    }

    /**
     * 보호자가 가정 기록 한 건을 선생님에게 보낼지 정한다. 숨기면 선생님 화면(목록, 코칭의 장면 요약, 검토함) 어디에도
     * 나오지 않는다. 기관 기록은 선생님이 만든 것이라 대상이 아니다(404).
     */
    @Transactional
    public ObservationResponse setTeacherVisibility(AuthUser user, long childId, long observationId, boolean hidden) {
        access.requireOwner(user, childId);
        AiObservation o = observations.findByIdAndChildId(observationId, childId)
                .filter(x -> x.getSource() == ObservationSource.HOME)
                .orElseThrow(ApiException::notFound);
        o.setHiddenFromTeacher(hidden);
        return ObservationResponse.from(o, null, user.id(), false);
    }

    /** 보호자는 전부, 선생님은 자기가 만든 기록만 지울 수 있다. */
    @Transactional
    public void delete(AuthUser user, long childId, long observationId) {
        access.lockChild(childId);
        access.requireOwnerOrLinkedTeacher(user, childId);
        AiObservation o = observations.findByIdAndChildId(observationId, childId).orElseThrow(ApiException::notFound);
        if (user.isTeacher() && !Objects.equals(o.getCreatedBy(), user.id())) {
            throw ApiException.notFound();
        }
        // 이 기록으로 전달된 카드 이력은 DB의 ON DELETE CASCADE로 함께 지워진다.
        // 쓴 영수증 표시(UsedReceipts)는 지우지 않는다: 지운 기록을 같은 영수증으로 다시 저장할 수 없어야 한다(AIO-07).
        observations.delete(o);
    }

    private record CaptureAccess(ObservationSource source, int maxFrames) {}

    /**
     * 이 사용자가 이 아이의 장면 사진을 AI 서버로 보내도 되는지 확인하고, 기록의 출처를 정한다.
     * <ul>
     *   <li>보호자(본인 아이): AI 동의가 있어야 한다. 없으면 422 {@code AI_CONSENT_REQUIRED}.</li>
     *   <li>선생님(연결됨): AI 동의와 '기관 촬영' 공유 설정이 모두 있어야 한다. 없으면 404(존재를 드러내지 않음).</li>
     *   <li>기능이 꺼져 있으면 503 {@code AI_UNAVAILABLE}.</li>
     * </ul>
     */
    private CaptureAccess requireCaptureAllowed(AuthUser user, long childId) {
        access.requireOwnerOrLinkedTeacher(user, childId);
        int consentLimit = consent.activeFrameLimit(childId);
        if (user.isTeacher()) {
            boolean captureOn = shareSettings.findById(childId).map(ShareSetting::isInstitutionCapture).orElse(false);
            if (!captureOn || consentLimit == 0) throw ApiException.notFound();
            settings.requireEnabled();
            return new CaptureAccess(ObservationSource.INSTITUTION,
                    Math.min(consentLimit, settings.frameLimit(ObservationSource.INSTITUTION)));
        }
        settings.requireEnabled();
        if (consentLimit == 0) throw new ApiException(ErrorCode.AI_CONSENT_REQUIRED);
        return new CaptureAccess(ObservationSource.HOME, Math.min(consentLimit, settings.frameLimit(ObservationSource.HOME)));
    }

    /** 기관 기록에만 만든 선생님의 표시명을 붙인다. 가정 기록에는 보호자 이름을 싣지 않는다. */
    private String authorName(AiObservation o, Map<Long, String> names) {
        if (o.getSource() != ObservationSource.INSTITUTION || o.getCreatedBy() == null) return null;
        if (names != null) return names.get(o.getCreatedBy());
        return users.findById(o.getCreatedBy()).map(User::getDisplayName).orElse(null);
    }
}
