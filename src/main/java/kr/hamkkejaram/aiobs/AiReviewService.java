package kr.hamkkejaram.aiobs;

import kr.hamkkejaram.aiobs.AiObservationDtos.ObservationResponse;
import kr.hamkkejaram.aiobs.AiReviewDtos.PublishRequest;
import kr.hamkkejaram.aiobs.AiReviewDtos.PublishedGuidance;
import kr.hamkkejaram.aiobs.AiReviewDtos.Recommendation;
import kr.hamkkejaram.aiobs.AiReviewDtos.TeacherReview;
import kr.hamkkejaram.child.Child;
import kr.hamkkejaram.child.ChildAccess;
import kr.hamkkejaram.common.ApiException;
import kr.hamkkejaram.common.ErrorCode;
import kr.hamkkejaram.common.TimeConfig;
import kr.hamkkejaram.security.AuthUser;
import kr.hamkkejaram.share.ShareSetting;
import kr.hamkkejaram.share.ShareSettingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;

@Service
public class AiReviewService {
    private final AiObservationRepository observations;
    private final ChildAccess access;
    private final ShareSettingRepository sharing;
    private final AiConsentService consent;
    private final AiReceiptVerifier receipts;
    private final Clock clock;

    public AiReviewService(AiObservationRepository observations, ChildAccess access, ShareSettingRepository sharing,
                           AiConsentService consent, AiReceiptVerifier receipts, Clock clock) {
        this.observations = observations;
        this.access = access;
        this.sharing = sharing;
        this.consent = consent;
        this.receipts = receipts;
        this.clock = clock;
    }

    private Child requireReviewer(AuthUser user, long childId) {
        Child child = access.requireLinkedTeacher(user, childId);
        if (!sharing.findById(childId).map(ShareSetting::isAiObservations).orElse(false)
                || !consent.hasActiveConsent(childId)) throw ApiException.notFound();
        return child;
    }

    private AiObservation reviewable(long childId, long id) {
        return observations.findByIdAndChildId(id, childId)
                .filter(o -> o.getSource() == ObservationSource.HOME && o.getReviewStatus() != null)
                .orElseThrow(ApiException::notFound);
    }

    @Transactional(readOnly = true)
    public TeacherReview review(AuthUser user, long childId, long id) {
        Child child = requireReviewer(user, childId);
        AiObservation o = reviewable(childId, id);
        int age = child.ageMonths(o.getRecordedAt().atZone(TimeConfig.SERVICE_ZONE).toLocalDate());
        return new TeacherReview(user.id(), o.getReviewStatus(), age, ObservationResponse.from(o, null, user.id(), true));
    }

    @Transactional
    public ObservationResponse publish(AuthUser user, long childId, long id, PublishRequest req) {
        access.lockChild(childId);
        requireReviewer(user, childId);
        AiObservation o = reviewable(childId, id);
        String hash = fingerprint(req);
        if (o.getReviewStatus() == AiObservation.ReviewStatus.PUBLISHED) {
            if (o.isPublicationRetry(user.id(), hash)) return ObservationResponse.from(o, null, user.id(), true);
            throw new ApiException(ErrorCode.DUPLICATE_REQUEST, "이미 검토를 마친 기록이에요. 목록을 다시 확인해 주세요.");
        }
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        AiGuidanceVerifier.Guidance guidance;
        try {
            var claims = receipts.authenticatedClaims(req.receipt(), AiGuidanceVerifier.AUDIENCE, now);
            guidance = AiGuidanceVerifier.read(claims, user.id(), childId, id);
        } catch (InvalidReceiptException e) {
            throw new ApiException(ErrorCode.AI_RECEIPT_INVALID, "검토 결과의 유효 시간을 확인하고 추천 근거를 다시 불러와 주세요.");
        }
        List<Recommendation> selected = guidance.recommendations();
        if (req.selectedSourceIds() != null) {
            var keep = new HashSet<>(req.selectedSourceIds());
            selected = selected.stream().filter(r -> keep.contains(r.sourceId())).toList();
            if (selected.size() != req.selectedSourceIds().size()) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "검토한 추천에서 전달할 항목을 골라 주세요.");
            }
        }
        o.publish(user.id(), hash, new PublishedGuidance(guidance.summary(), selected, guidance.corpusVersion(), now));
        return ObservationResponse.from(o, null, user.id(), true);
    }

    private static String fingerprint(PublishRequest req) {
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            hash.update(req.receipt().getBytes(StandardCharsets.UTF_8));
            if (req.selectedSourceIds() != null) {
                hash.update((byte) 0);
                req.selectedSourceIds().stream().sorted().forEach(id -> {
                    byte[] bytes = id.getBytes(StandardCharsets.UTF_8);
                    hash.update((byte) (bytes.length >>> 8));
                    hash.update((byte) bytes.length);
                    hash.update(bytes);
                });
            }
            return HexFormat.of().formatHex(hash.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
