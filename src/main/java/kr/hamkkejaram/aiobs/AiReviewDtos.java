package kr.hamkkejaram.aiobs;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

public final class AiReviewDtos {
    private AiReviewDtos() {}

    public record Recommendation(String observation, String action, String reason, String sourceId,
                                 String sourceTitle, String sourcePublisher, String sourceUrl, String sourceSection) {}

    public record PublishedGuidance(String summary, List<Recommendation> recommendations, String corpusVersion,
                                    Instant reviewedAt) {
        public PublishedGuidance {
            recommendations = List.copyOf(recommendations);
        }
    }

    public record TeacherReview(long reviewerId, AiObservation.ReviewStatus status, int ageMonths,
                                AiObservationDtos.ObservationResponse observation) {}

    /** Omitted IDs includes all signed recommendations. An empty list publishes the summary alone. */
    public record PublishRequest(@NotBlank @Size(max = 48000) String receipt,
                                 @Size(max = 2) List<@NotBlank @Size(max = 100) String> selectedSourceIds) {}
}
