package kr.hamkkejaram.aiobs;

import kr.hamkkejaram.aiobs.AiObservationDtos.ObservationResponse;
import kr.hamkkejaram.aiobs.AiReviewDtos.PublishRequest;
import kr.hamkkejaram.aiobs.AiReviewDtos.PublishedGuidance;
import kr.hamkkejaram.aiobs.AiReviewDtos.Recommendation;
import kr.hamkkejaram.aiobs.AiReviewDtos.TeacherEdit;
import kr.hamkkejaram.aiobs.AiReviewDtos.TeacherReview;
import kr.hamkkejaram.card.CardDeliveryRepository;
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
import java.util.Comparator;
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
    private final CardDeliveryRepository deliveries;
    private final Clock clock;

    public AiReviewService(AiObservationRepository observations, ChildAccess access, ShareSettingRepository sharing,
                           AiConsentService consent, AiReceiptVerifier receipts, CardDeliveryRepository deliveries,
                           Clock clock) {
        this.observations = observations;
        this.access = access;
        this.sharing = sharing;
        this.consent = consent;
        this.receipts = receipts;
        this.deliveries = deliveries;
        this.clock = clock;
    }

    private record ReviewAccess(Child child, ShareSetting settings) {}

    private ReviewAccess requireReviewer(AuthUser user, long childId) {
        Child child = access.requireLinkedTeacher(user, childId);
        ShareSetting settings = sharing.findById(childId).filter(ShareSetting::isAiObservations)
                .orElseThrow(ApiException::notFound);
        if (!consent.hasActiveConsent(childId)) throw ApiException.notFound();
        return new ReviewAccess(child, settings);
    }

    private AiObservation reviewable(long childId, long id) {
        return observations.findByIdAndChildId(id, childId)
                .filter(o -> o.getSource() == ObservationSource.HOME && o.getReviewStatus() != null)
                .orElseThrow(ApiException::notFound);
    }

    @Transactional(readOnly = true)
    public TeacherReview review(AuthUser user, long childId, long id) {
        ReviewAccess reviewer = requireReviewer(user, childId);
        AiObservation o = reviewable(childId, id);
        int age = reviewer.child().ageMonths(o.getRecordedAt().atZone(TimeConfig.SERVICE_ZONE).toLocalDate());
        ObservationResponse observation = ObservationResponse.from(o, null, user.id(), true);
        TeacherReviewDisplay.RelatedCoaching coaching = reviewer.settings().isCoachingCards()
                ? deliveries.findFirstByChildIdAndAiObservationIdOrderByDeliveredAtDescIdDesc(childId, id)
                        .map(d -> new TeacherReviewDisplay.RelatedCoaching(d.getId(), d.getCard().getTitle(),
                                "/t/children/" + childId + "/coaching/" + d.getId()))
                        .orElse(null)
                : null;
        return new TeacherReview(user.id(), o.getReviewStatus(), age, observation,
                TeacherReviewDisplay.from(childId, observation, coaching));
    }

    @Transactional
    public ObservationResponse publish(AuthUser user, long childId, long id, PublishRequest req) {
        access.lockChild(childId);
        requireReviewer(user, childId);
        AiObservation o = reviewable(childId, id);
        List<TeacherEdit> edits = req.teacherEdits() == null ? List.of() : req.teacherEdits().stream()
                .sorted(Comparator.comparing(TeacherEdit::sourceId)).toList();
        String hash = fingerprint(req, edits);
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
        var editableSources = new HashSet<>(selected.stream().map(Recommendation::sourceId).toList());
        for (TeacherEdit edit : edits) {
            if (!editableSources.remove(edit.sourceId())) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "전달할 추천만 항목마다 한 번 수정해 주세요.");
            }
        }
        o.publish(user.id(), hash, new PublishedGuidance(guidance.summary(), selected, guidance.corpusVersion(), now,
                edits, req.teacherRecommendation()));
        return ObservationResponse.from(o, null, user.id(), true);
    }

    private static String fingerprint(PublishRequest req, List<TeacherEdit> edits) {
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            hash.update(req.receipt().getBytes(StandardCharsets.UTF_8));
            if (req.selectedSourceIds() != null) {
                hash.update((byte) 0);
                req.selectedSourceIds().stream().sorted().forEach(id -> hashText(hash, id));
            }
            var recommendation = req.teacherRecommendation();
            if (!edits.isEmpty() || recommendation != null) {
                // An extended request has a separate suffix while old publication hashes remain valid.
                hash.update((byte) 0xff);
                hash.update((byte) edits.size());
                for (TeacherEdit edit : edits) {
                    hashText(hash, edit.sourceId());
                    hashText(hash, edit.action());
                    hashText(hash, edit.rationale());
                }
                hash.update((byte) (recommendation == null ? 0 : 1));
                if (recommendation != null) {
                    hashText(hash, recommendation.action());
                    hashText(hash, recommendation.rationale());
                }
            }
            return HexFormat.of().formatHex(hash.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static void hashText(MessageDigest hash, String value) {
        byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
        hash.update((byte) (bytes.length >>> 8));
        hash.update((byte) bytes.length);
        hash.update(bytes);
    }
}
