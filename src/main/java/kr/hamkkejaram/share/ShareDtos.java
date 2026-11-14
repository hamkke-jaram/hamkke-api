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

    /**
     * AI_OBSERVATIONS: 가정에서 만든 AI 관찰 기록을 볼 수 있다.
     * INSTITUTION_CAPTURE: 기관에서 촬영·분석할 수 있다(공유 설정이 켜져 있고 AI 동의가 있을 때만 담긴다).
     */
    public enum SharedItem { JOINT_PLAY, RESPONSE_LATENCY, DISTRESS, COACHING_CARDS, AI_OBSERVATIONS,
        INSTITUTION_CAPTURE }

    /** 앞의 네 값은 필수. aiObservations·institutionCapture는 생략하면 지금 값을 그대로 둔다. */
    public record ShareSettingsRequest(@NotNull Boolean jointPlay, @NotNull Boolean responseLatency,
                                       @NotNull Boolean distress, @NotNull Boolean coachingCards,
                                       Boolean aiObservations, Boolean institutionCapture) {}

    public record ShareSettingsResponse(boolean jointPlay, boolean responseLatency, boolean distress,
                                        boolean coachingCards, boolean aiObservations, boolean institutionCapture,
                                        Instant updatedAt) {
        public static ShareSettingsResponse from(ShareSetting s) {
            return new ShareSettingsResponse(s.isJointPlay(), s.isResponseLatency(), s.isDistress(),
                    s.isCoachingCards(), s.isAiObservations(), s.isInstitutionCapture(), s.getUpdatedAt());
        }
    }

    public record InviteCodeResponse(String code, Instant expiresAt) {}

    /** 발급한 코드 목록(아직 쓸 수 있는 코드만). */
    public record UsableInviteCodeResponse(String code, Instant expiresAt, Instant createdAt) {}

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
