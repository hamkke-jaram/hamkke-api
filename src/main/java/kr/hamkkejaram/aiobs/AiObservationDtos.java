package kr.hamkkejaram.aiobs;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import kr.hamkkejaram.aiobs.ObservationContent.Moment;
import kr.hamkkejaram.common.TimeConfig;
import kr.hamkkejaram.card.CardDtos.Coaching;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class AiObservationDtos {
    private AiObservationDtos() {}

    /** 영수증(JWS) 길이 상한. 관찰 기록 객체가 가장 길 때도 이보다 짧다(요청 본문 전체는 64KB 제한). */
    static final int MAX_RECEIPT_CHARS = 16_000;

    public record AiConsentRequest(
            @NotNull @AssertTrue(message = "동의에 체크해 주세요.") Boolean agreed,
            @NotBlank @Size(max = 30) String version) {}

    /**
     * agreed: 지금 유효한 동의가 있는지(철회되지 않았고 현재 동의 문서 버전에 대한 것).
     * version·agreedAt: 가장 최근에 한(철회하지 않은) 동의의 문서 버전과 시각. 동의한 적이 없으면 null.
     */
    public record AiConsentResponse(boolean agreed, String version, Instant agreedAt) {
        static AiConsentResponse none() {
            return new AiConsentResponse(false, null, null);
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
     * childLine·parentLine: 선생님용 장면 요약의 아이 한 줄·어른 한 줄(없을 수 있다). hiddenFromTeacher: 보호자가 선생님에게
     * 보내지 않기로 한 기록인지(선생님에게는 이런 기록이 아예 오지 않으므로 늘 false).
     */
    public record ObservationResponse(long id, ObservationSource source, Instant recordedAt, String summary,
                                      List<ChildState> childStates, List<AdultResponse> adultResponses,
                                      List<Moment> moments, int peopleCount, String modelVersion,
                                      String promptVersion, int frameCount, String authorName, boolean mine,
                                      Instant createdAt, AiObservation.ReviewStatus reviewStatus,
                                      AiReviewDtos.PublishedGuidance publishedGuidance,
                                      String childLine, String parentLine, boolean hiddenFromTeacher) {
        public static ObservationResponse from(AiObservation o, String authorName, long viewerId, boolean teacher) {
            var published = o.publishedGuidance();
            boolean originalVisible = teacher || o.getReviewStatus() == null;
            return new ObservationResponse(o.getId(), o.getSource(), o.getRecordedAt(),
                    originalVisible ? o.getSummary() : published == null ? null : published.summary(),
                    originalVisible ? o.childStates() : List.of(), originalVisible ? o.adultResponses() : List.of(),
                    originalVisible ? o.moments() : List.of(), originalVisible ? o.getPeopleCount() : 0, o.getModelVersion(),
                    o.getPromptVersion(), o.getFrameCount(), authorName, o.getCreatedBy() != null && o.getCreatedBy() == viewerId,
                    o.getCreatedAt(), o.getReviewStatus(), published,
                    originalVisible ? o.getChildLine() : null, originalVisible ? o.getParentLine() : null,
                    !teacher && o.isHiddenFromTeacher());
        }
    }

    /**
     * 선생님용 가정 장면 요약 한 건. 날짜(일 단위)·태그·아이 한 줄·어른 한 줄만 담는다.
     * 원본 요약 문장, 시각별 메모, 정확한 시각, 사람 수, 모델 정보는 담지 않는다(개인정보를 줄여 넘긴다).
     * 한 줄은 없을 수 있다(null): 예전 기록이거나 문장 검사에 걸리면 태그만 넘어간다.
     * reviewStatus: 선생님 검토 상태(검토함으로 이어 줄 때 쓴다).
     * hiddenFromTeacher: 보호자가 선생님에게 보내지 않기로 했는지(보호자 미리보기용. 선생님에게는 그런 기록이 오지 않는다).
     */
    public record HomeScene(long id, LocalDate observedOn, List<ChildState> childStates,
                            List<AdultResponse> adultResponses, String childLine, String parentLine,
                            AiObservation.ReviewStatus reviewStatus, boolean hiddenFromTeacher) {
        static HomeScene from(AiObservation o) {
            return new HomeScene(o.getId(), o.getRecordedAt().atZone(TimeConfig.SERVICE_ZONE).toLocalDate(),
                    o.childStates(), o.adultResponses(), o.getChildLine(), o.getParentLine(), o.getReviewStatus(),
                    o.isHiddenFromTeacher());
        }
    }

    /** hidden=true: 이 가정 기록을 선생님에게 보내지 않는다. */
    public record TeacherVisibilityRequest(@NotNull Boolean hidden) {}

    /** 영상 결과에 대한 대응은 교사가 게시한 publishedGuidance에서 제공한다. */
    public record SaveObservationResponse(ObservationResponse observation, Coaching coaching) {}
}
