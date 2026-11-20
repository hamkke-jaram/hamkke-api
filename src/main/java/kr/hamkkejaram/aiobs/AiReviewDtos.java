package kr.hamkkejaram.aiobs;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

public final class AiReviewDtos {
    private AiReviewDtos() {}

    public record Recommendation(String observation, String action, String reason, String sourceId,
                                 String sourceTitle, String sourcePublisher, String sourceUrl, String sourceSection) {}

    public record TeacherEdit(@NotBlank @Size(max = 100) String sourceId,
                              @NotBlank @Size(max = 500) String action, @Size(max = 500) String rationale) {
        public TeacherEdit {
            action = trimToNull(action);
            rationale = trimToNull(rationale);
        }
    }

    public record TeacherRecommendation(@NotBlank @Size(max = 500) String action, @Size(max = 500) String rationale) {
        public TeacherRecommendation {
            action = trimToNull(action);
            rationale = trimToNull(rationale);
        }
    }

    public record PublishedGuidance(String summary, List<Recommendation> recommendations, String corpusVersion,
                                    Instant reviewedAt,
                                    @JsonInclude(JsonInclude.Include.NON_EMPTY) List<TeacherEdit> teacherEdits,
                                    @JsonInclude(JsonInclude.Include.NON_NULL) TeacherRecommendation teacherRecommendation) {
        public PublishedGuidance {
            recommendations = List.copyOf(recommendations);
            teacherEdits = teacherEdits == null ? List.of() : List.copyOf(teacherEdits);
        }
    }

    public record TeacherReview(long reviewerId, AiObservation.ReviewStatus status, int ageMonths,
                                AiObservationDtos.ObservationResponse observation, TeacherReviewDisplay display) {}

    /** Omitted IDs select all signed recommendations. Teacher text is saved separately from signed evidence. */
    public record PublishRequest(@NotBlank @Size(max = 48000) String receipt,
                                 @Size(max = 2) List<@NotBlank @Size(max = 100) String> selectedSourceIds,
                                 @Size(max = 2) List<@NotNull @Valid TeacherEdit> teacherEdits,
                                 @Valid TeacherRecommendation teacherRecommendation) {}

    private static String trimToNull(String value) {
        if (value == null) return null;
        String normalized = value.strip();
        return normalized.isEmpty() ? null : normalized;
    }
}
