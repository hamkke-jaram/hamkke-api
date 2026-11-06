package kr.hamkkejaram.share;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class ShareDtos {
    private ShareDtos() {}

    public enum SharedItem { JOINT_PLAY, RESPONSE_LATENCY, DISTRESS, COACHING_CARDS }

    public record ShareSettingsRequest(@NotNull Boolean jointPlay, @NotNull Boolean responseLatency,
                                       @NotNull Boolean distress, @NotNull Boolean coachingCards) {}

    public record ShareSettingsResponse(boolean jointPlay, boolean responseLatency, boolean distress,
                                        boolean coachingCards, Instant updatedAt) {
        public static ShareSettingsResponse from(ShareSetting s) {
            return new ShareSettingsResponse(s.isJointPlay(), s.isResponseLatency(), s.isDistress(),
                    s.isCoachingCards(), s.getUpdatedAt());
        }
    }

    public record InviteCodeResponse(String code, Instant expiresAt) {}

    public record LinkedTeacherResponse(long teacherId, String displayName, Instant linkedAt) {}

    public record LinkRequest(@NotBlank @Size(max = 20) String code) {}

    public record TeacherChildResponse(long id, String nickname, int ageMonths, Instant linkedAt) {}

    /** 공유가 꺼진 항목은 응답에 아예 넣지 않는다. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SharedWeek(LocalDate weekStart, int recordCount, Double jointPlayRatio, Double jointPlaySeconds,
                             Double responseLatencySec, Double distressPerTenMinutes) {}

    public record ChildBrief(long id, String nickname, int ageMonths) {}

    public record SharedSummaryResponse(ChildBrief child, List<SharedItem> sharedItems, List<SharedWeek> weeks) {}
}
