package kr.hamkkejaram.user;

import com.fasterxml.jackson.annotation.JsonInclude;
import kr.hamkkejaram.aiobs.AdultResponse;
import kr.hamkkejaram.aiobs.ChildState;
import kr.hamkkejaram.aiobs.ObservationContent.Moment;
import kr.hamkkejaram.aiobs.ObservationSource;
import kr.hamkkejaram.note.GoalStatus;
import kr.hamkkejaram.note.NoteDtos.GoalResponse;
import kr.hamkkejaram.note.NoteDtos.ObservationResponse;
import kr.hamkkejaram.record.RecordDtos.RecordResponse;
import kr.hamkkejaram.share.ShareDtos.ShareSettingsResponse;
import kr.hamkkejaram.user.MeController.ConsentResponse;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 내 데이터 내려받기(GET /me/export, ACC-11) 응답. 비밀번호 해시·토큰·영수증·분석 토큰은 담지 않는다.
 * 부모는 children, 교사는 teacher만 채워지고 다른 쪽은 응답에서 빠진다.
 */
public final class DataExportDtos {
    private DataExportDtos() {}

    public static final String FORMAT = "hamkke-jaram-export-v1";

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DataExport(String format, Instant exportedAt, String notice, Account account,
                             List<ConsentResponse> consents, List<ChildExport> children, TeacherExport teacher) {}

    public record Account(long id, String email, String displayName, Role role, Instant createdAt) {}

    /** linkedTeachers에는 선생님의 표시명과 연결 시각만 담는다(이메일 없음). */
    public record ChildExport(long id, String nickname, String birthYearMonth, Instant createdAt,
                              ShareSettingsResponse shareSettings, List<LinkedTeacher> linkedTeachers,
                              List<RecordResponse> interactionRecords, List<CardDeliveryItem> cardDeliveries,
                              List<GoalResponse> goals, List<ObservationResponse> observationNotes,
                              List<AiObservationItem> aiObservations) {}

    public record LinkedTeacher(String displayName, Instant linkedAt) {}

    public record CardDeliveryItem(long id, Instant deliveredAt, Instant triedAt, Long recordId,
                                   Long aiObservationId, long cardId, String cardTitle) {}

    /** authorName: 기관 기록을 만든 선생님의 표시명(가정 기록은 null). */
    public record AiObservationItem(long id, ObservationSource source, Instant recordedAt, String summary,
                                    List<ChildState> childStates, List<AdultResponse> adultResponses,
                                    List<Moment> moments, int peopleCount, String modelVersion,
                                    String promptVersion, int frameCount, String authorName, Instant createdAt) {}

    /** 교사: 지금 연결된 아이, 그리고 본인이 쓴 목표·관찰 메모(연결이 끊긴 아이의 것도 남아 있으면 담긴다). */
    public record TeacherExport(List<TeacherLinkItem> links, List<TeacherGoalItem> goals,
                                List<TeacherNoteItem> observationNotes) {}

    public record TeacherLinkItem(long childId, String childNickname, Instant linkedAt) {}

    public record TeacherGoalItem(long id, long childId, String title, String description, GoalStatus status,
                                  Instant createdAt, Instant updatedAt) {}

    public record TeacherNoteItem(long id, long childId, String content, LocalDate observedOn, Instant createdAt) {}
}
