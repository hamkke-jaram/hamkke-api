package kr.hamkkejaram.aiobs;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import kr.hamkkejaram.aiobs.ObservationContent.Moment;
import kr.hamkkejaram.card.CardDtos.Coaching;

import java.time.Instant;
import java.util.List;

public final class AiObservationDtos {
    private AiObservationDtos() {}

    /** 영수증(JWS) 길이 상한. 관찰 기록 객체가 가장 길 때도 이보다 짧다(요청 본문 전체는 64KB 제한). */
    static final int MAX_RECEIPT_CHARS = 16_000;

    public record AiConsentRequest(
            @NotNull @AssertTrue(message = "동의에 체크해 주세요.") Boolean agreed,
            @NotBlank @Size(max = 30) String version,
            JsonNode maxFrames) {
        boolean acknowledgesFrameLimit(int limit) {
            return maxFrames != null && maxFrames.isIntegralNumber() && maxFrames.canConvertToInt()
                    && maxFrames.intValue() == limit;
        }
    }

    /**
     * agreed: 지금 유효한 동의가 있는지. maxFrames: 유효한 동의가 허용하는 최대 사진 장수이며, 없으면 0.
     * version·agreedAt: 가장 최근에 한(철회하지 않은) 동의의 문서 버전과 시각. 동의한 적이 없으면 null.
     */
    public record AiConsentResponse(boolean agreed, String version, Instant agreedAt, int maxFrames) {
        static AiConsentResponse none() {
            return new AiConsentResponse(false, null, null, 0);
        }
    }

    /** 분석 토큰. hamkke-ai 서버의 {@code POST /ai/v1/analyze}에 Bearer로 보낸다. */
    public record AiSessionResponse(String token, Instant expiresAt, int maxFrames) {}

    /** receipt: AI 서버가 돌려준 영수증(JWS). recordedAt: 장면을 찍은 시각(생략하면 지금). 사진은 받지 않는다. */
    public record SaveObservationRequest(
            @NotBlank @Size(max = MAX_RECEIPT_CHARS) String receipt,
            Instant recordedAt) {}

    /**
     * 관찰 기록 한 건. summary는 없을 수 있다(null).
     * authorName: 기관 기록을 만든 선생님의 표시명(가정 기록에서는 null). mine: 지금 요청한 사람이 만든 기록인지.
     */
    public record ObservationResponse(long id, ObservationSource source, Instant recordedAt, String summary,
                                      List<ChildState> childStates, List<AdultResponse> adultResponses,
                                      List<Moment> moments, int peopleCount, String modelVersion,
                                      String promptVersion, int frameCount, String authorName, boolean mine,
                                      Instant createdAt, AiObservation.ReviewStatus reviewStatus,
                                      AiReviewDtos.PublishedGuidance publishedGuidance) {
        public static ObservationResponse from(AiObservation o, String authorName, long viewerId, boolean teacher) {
            var published = o.publishedGuidance();
            boolean originalVisible = teacher || o.getReviewStatus() == null;
            return new ObservationResponse(o.getId(), o.getSource(), o.getRecordedAt(),
                    originalVisible ? o.getSummary() : published == null ? null : published.summary(),
                    originalVisible ? o.childStates() : List.of(), originalVisible ? o.adultResponses() : List.of(),
                    originalVisible ? o.moments() : List.of(), originalVisible ? o.getPeopleCount() : 0, o.getModelVersion(),
                    o.getPromptVersion(), o.getFrameCount(), authorName, o.getCreatedBy() != null && o.getCreatedBy() == viewerId,
                    o.getCreatedAt(), o.getReviewStatus(), published);
        }
    }

    /** 영상 결과에 대한 대응은 교사가 게시한 publishedGuidance에서 제공한다. */
    public record SaveObservationResponse(ObservationResponse observation, Coaching coaching) {}
}
