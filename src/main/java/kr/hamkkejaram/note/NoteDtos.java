package kr.hamkkejaram.note;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;

public final class NoteDtos {
    private NoteDtos() {}

    public record CreateGoalRequest(@NotBlank @Size(max = 60) String title, @Size(max = 500) String description) {}

    public record UpdateGoalRequest(@Size(min = 1, max = 60) String title, @Size(max = 500) String description,
                                    GoalStatus status) {}

    public record GoalResponse(long id, String title, String description, GoalStatus status, long teacherId,
                               String teacherName, Instant createdAt, Instant updatedAt) {
        public static GoalResponse from(ChildGoal g, String teacherName) {
            return new GoalResponse(g.getId(), g.getTitle(), g.getDescription(), g.getStatus(), g.getTeacherId(),
                    teacherName, g.getCreatedAt(), g.getUpdatedAt());
        }
    }

    public record CreateObservationRequest(@NotBlank @Size(max = 1000) String content, @NotNull LocalDate observedOn) {}

    public record ObservationResponse(long id, String content, LocalDate observedOn, long teacherId,
                                      String teacherName, Instant createdAt) {
        public static ObservationResponse from(ObservationNote n, String teacherName) {
            return new ObservationResponse(n.getId(), n.getContent(), n.getObservedOn(), n.getTeacherId(),
                    teacherName, n.getCreatedAt());
        }
    }
}
