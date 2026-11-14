package kr.hamkkejaram.note;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;

public final class NoteDtos {
    private NoteDtos() {}

    public record CreateGoalRequest(@NotBlank @Size(max = 60) String title, @Size(max = 500) String description) {}

    /** 공백(전각 공백 포함)이 아닌 글자가 하나 이상 있어야 한다. null은 @Pattern 검사 대상이 아니다. */
    static final String NOT_BLANK = "(?sU).*\\S.*";

    /** 수정은 보낸 항목만 바꾼다. 제목은 생략할 수 있지만, 보냈다면 공백만으로 둘 수 없다. */
    public record UpdateGoalRequest(
            @Size(min = 1, max = 60)
            @Pattern(regexp = NOT_BLANK, message = "목표 제목을 한 글자 이상 입력해 주세요.") String title,
            @Size(max = 500) String description,
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
