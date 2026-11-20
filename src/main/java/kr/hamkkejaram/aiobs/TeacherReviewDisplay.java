package kr.hamkkejaram.aiobs;

import kr.hamkkejaram.aiobs.AiObservationDtos.ObservationResponse;
import kr.hamkkejaram.aiobs.AiReviewDtos.Recommendation;
import kr.hamkkejaram.aiobs.ObservationContent.Moment;

import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;

/** The teacher's view of one shared observation. Role notes come from stored, attributed scenes. */
public record TeacherReviewDisplay(long observationId, Instant recordedAt, ObservationSource source,
                                   int peopleCount, String summary, List<Moment> scenes,
                                   ActorNotes<ChildState> child, CaregiverNotes caregiver,
                                   ResponseOptions responseOptions, RelatedCoaching relatedCoaching, List<Action> actions) {
    private static final Pattern CHILD_SUBJECT = Pattern.compile("^(?:아이|유아|영아|어린이|아기)(?:가|는)(?=\\s)");
    private static final Pattern CAREGIVER_SUBJECT = Pattern.compile("^(?:보호자|부모(?:님)?)(?:가|는|이|은|께서)(?=\\s)");
    private static final Pattern ADULT_SUBJECT = Pattern.compile("^(?:어른|성인)(?:이|은)(?=\\s)");
    private static final Pattern ANOTHER_SUBJECT = Pattern.compile(
            "(?<![가-힣])(?:아이(?:들)?|유아|영아|어린이|아기|보호자|부모(?:님)?|엄마|아빠|어머니|아버지|어른|성인|사람|선생님?|교사|친구|남자|여자|형|누나|언니|오빠|할머니|할아버지)(?:께서|이|가|은|는|도|만)(?=\\s|[.!?…,]|$)");

    public record ActorNotes<T>(List<Moment> notes, List<T> tags) {}

    public enum CaregiverRole { ADULT, CAREGIVER }

    public record CaregiverNotes(CaregiverRole role, List<Moment> notes, List<AdultResponse> tags) {}

    public record ResponseOptions(AiObservation.ReviewStatus status, String corpusVersion, List<ResponseItem> items) {}

    public record ResponseItem(String observation, String action, String reason, Source source) {
        private static ResponseItem from(Recommendation item) {
            return new ResponseItem(item.observation(), item.action(), item.reason(), new Source(item.sourceId(),
                    item.sourceTitle(), item.sourcePublisher(), item.sourceUrl(), item.sourceSection()));
        }
    }

    public record Source(String id, String title, String publisher, String url, String section) {}

    public record RelatedCoaching(long deliveryId, String title, String href) {}

    public enum ActionType { WRITE_PARENT_FEEDBACK, REQUEST_CONSULTATION }

    public record Action(ActionType type, String label, String href) {}

    static TeacherReviewDisplay from(long childId, ObservationResponse observation, RelatedCoaching coaching) {
        var scenes = observation.moments();
        var published = observation.publishedGuidance();
        var options = published == null ? new ResponseOptions(observation.reviewStatus(), null, List.of())
                : new ResponseOptions(observation.reviewStatus(), published.corpusVersion(),
                        published.recommendations().stream().map(ResponseItem::from).toList());
        var adultNotes = scenes.stream().filter(scene -> hasSubject(scene, CAREGIVER_SUBJECT) || hasSubject(scene, ADULT_SUBJECT)).toList();
        var caregiverRole = !adultNotes.isEmpty() && adultNotes.stream().allMatch(scene -> hasSubject(scene, CAREGIVER_SUBJECT))
                ? CaregiverRole.CAREGIVER : CaregiverRole.ADULT;
        String teacherBase = "/t/children/" + childId;
        return new TeacherReviewDisplay(observation.id(), observation.recordedAt(), observation.source(),
                observation.peopleCount(), observation.summary(), scenes,
                new ActorNotes<>(scenes.stream().filter(scene -> hasSubject(scene, CHILD_SUBJECT)).toList(), observation.childStates()),
                new CaregiverNotes(caregiverRole, adultNotes, observation.adultResponses()),
                options, coaching, List.of(new Action(ActionType.WRITE_PARENT_FEEDBACK,
                        "보호자에게 피드백 쓰기", teacherBase + "/story/new"),
                        new Action(ActionType.REQUEST_CONSULTATION, "상담 요청하기", teacherBase + "/talk?draft=consultation")));
    }

    private static boolean hasSubject(Moment scene, Pattern subject) {
        String note = scene.note().strip();
        var match = subject.matcher(note);
        // Leave notes with more than one explicit subject in the full scene list.
        return match.find() && !ANOTHER_SUBJECT.matcher(note).find(match.end());
    }
}
