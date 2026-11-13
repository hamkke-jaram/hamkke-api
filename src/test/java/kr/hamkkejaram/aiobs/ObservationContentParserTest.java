package kr.hamkkejaram.aiobs;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static kr.hamkkejaram.aiobs.TestReceipts.moment;
import static kr.hamkkejaram.aiobs.TestReceipts.obs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 관찰 기록 객체 검사와 문장 검사 (docs/ai-observation.md 6장). */
class ObservationContentParserTest {
    private static final String OK_SUMMARY = "어른과 아이가 블록을 사이에 두고 마주 앉아 있어요.";

    private static Map<String, Object> valid() {
        return obs(OK_SUMMARY, List.of("PLAYING_TOGETHER", "REFUSING"), List.of("WAITING"),
                List.of(moment(12.5, "아이가 블록을 밀어내요."), moment(40, "어른이 잠시 기다려요.")), 2);
    }

    private static void assertRejected(Object obs, String reason) {
        assertThatThrownBy(() -> ObservationContentParser.parse(obs))
                .isInstanceOf(InvalidReceiptException.class)
                .extracting(e -> ((InvalidReceiptException) e).reason()).isEqualTo(reason);
    }

    // ---------- 받아들이는 경우 ----------

    @Test
    void readsAValidObservation() {
        ObservationContent c = ObservationContentParser.parse(valid());
        assertThat(c.summary()).isEqualTo(OK_SUMMARY);
        assertThat(c.childStates()).containsExactly(ChildState.PLAYING_TOGETHER, ChildState.REFUSING);
        assertThat(c.adultResponses()).containsExactly(AdultResponse.WAITING);
        assertThat(c.moments()).extracting(ObservationContent.Moment::t).containsExactly(12.5, 40.0);
        assertThat(c.moments().get(0).note()).isEqualTo("아이가 블록을 밀어내요.");
        assertThat(c.peopleCount()).isEqualTo(2);
        assertThat(c.isEmpty()).isFalse();
    }

    @Test
    void summaryMayBeNullAndListsMayBeEmptyOrMissing() {
        Map<String, Object> o = obs(null, List.of(), List.of("TALKING"), List.of(), 0);
        ObservationContent c = ObservationContentParser.parse(o);
        assertThat(c.summary()).isNull();
        assertThat(c.childStates()).isEmpty();
        assertThat(c.moments()).isEmpty();
        assertThat(c.peopleCount()).isZero();

        o.remove("childStates");
        o.remove("moments");
        assertThat(ObservationContentParser.parse(o).adultResponses()).containsExactly(AdultResponse.TALKING);
    }

    @Test
    void everyListedTagIsAccepted() {
        for (ChildState s : ChildState.values()) {
            assertThat(ObservationContentParser.parse(obs(null, List.of(s.name()), List.of(), List.of(), 1))
                    .childStates()).containsExactly(s);
        }
        for (AdultResponse r : AdultResponse.values()) {
            assertThat(ObservationContentParser.parse(obs(null, List.of(), List.of(r.name()), List.of(), 1))
                    .adultResponses()).containsExactly(r);
        }
    }

    @Test
    void limitsAreInclusive() {
        String summary300 = "가".repeat(300);
        String note120 = "나".repeat(120);
        ObservationContent c = ObservationContentParser.parse(obs(summary300,
                List.of("CALM", "FOCUSED", "PLAYING_ALONE"), List.of("WAITING", "TALKING", "JOINING_PLAY"),
                List.of(moment(0, note120), moment(1, "둘"), moment(2, "셋"), moment(3, "넷")), 3));
        assertThat(c.summary()).hasSize(300);
        assertThat(c.moments()).hasSize(4);
        assertThat(c.childStates()).hasSize(3);
        assertThat(c.peopleCount()).isEqualTo(3);
    }

    @Test
    void whitespaceIsTidiedAndDuplicateTagsCollapse() {
        ObservationContent c = ObservationContentParser.parse(obs("  아이가 공을\n굴려요.\t ",
                List.of("CALM", "CALM"), List.of(), List.of(moment(3, "   ")), 1));
        assertThat(c.summary()).isEqualTo("아이가 공을 굴려요.");
        assertThat(c.childStates()).containsExactly(ChildState.CALM);
        assertThat(c.moments()).isEmpty(); // 빈 메모는 그 항목만 버린다
    }

    @Test
    void blankSummaryBecomesNull() {
        assertThat(ObservationContentParser.parse(obs("   ", List.of("CALM"), List.of(), List.of(), 1)).summary())
                .isNull();
    }

    // ---------- 문장 검사: 걸린 문장만 버린다 ----------

    @Test
    void summaryWithForbiddenExpressionIsDroppedButTheRestIsKept() {
        for (String word : ObservationSentenceFilter.forbiddenExamples()) {
            Map<String, Object> o = valid();
            o.put("summary", "아이가 블록을 쌓아요. " + word + " 같아 보여요.");
            ObservationContent c = ObservationContentParser.parse(o);
            assertThat(c.summary()).as(word).isNull();
            assertThat(c.childStates()).hasSize(2);
            assertThat(c.moments()).hasSize(2);
        }
    }

    @Test
    void momentWithJudgingWordIsRemovedButOthersStay() {
        for (String word : ObservationSentenceFilter.judgingWords()) {
            Map<String, Object> o = valid();
            o.put("moments", List.of(moment(1, "아이가 블록을 쌓아요."), moment(2, "아이에게 " + word + " 보여요."),
                    moment(3, "어른이 웃어요.")));
            ObservationContent c = ObservationContentParser.parse(o);
            assertThat(c.moments()).as(word).extracting(ObservationContent.Moment::t).containsExactly(1.0, 3.0);
            assertThat(c.summary()).isEqualTo(OK_SUMMARY);
        }
    }

    @Test
    void whenEverythingIsDroppedTheObservationIsEmpty() {
        String bad = "아이가 " + ObservationSentenceFilter.judgingWords().get(0) + " 있어 보여요.";
        ObservationContent c = ObservationContentParser.parse(obs(bad, List.of(), List.of(),
                List.of(moment(1, bad)), 1));
        assertThat(c.isEmpty()).isTrue();
    }

    // ---------- 약속과 다른 모양: 영수증 전체를 받지 않는다 ----------

    @Test
    void rejectsWhenObsIsNotAnObject() {
        assertRejected(null, "obs_not_object");
        assertRejected("글자", "obs_not_object");
        assertRejected(List.of(), "obs_not_object");
    }

    @Test
    void rejectsUnknownOrMalformedTags() {
        Map<String, Object> o = valid();
        o.put("childStates", List.of("CALM", "SLEEPING"));
        assertRejected(o, "childStates_unknown_value");

        o = valid();
        o.put("childStates", List.of("calm")); // 대소문자까지 같아야 한다
        assertRejected(o, "childStates_unknown_value");

        o = valid();
        o.put("adultResponses", List.of("CALM")); // 아이 모습 태그를 어른 반응 자리에
        assertRejected(o, "adultResponses_unknown_value");

        o = valid();
        o.put("childStates", "CALM");
        assertRejected(o, "childStates_not_array");

        o = valid();
        o.put("adultResponses", Arrays.asList("WAITING", 3));
        assertRejected(o, "adultResponses_not_string");
    }

    @Test
    void rejectsTooManyTags() {
        Map<String, Object> o = valid();
        o.put("childStates", List.of("CALM", "FOCUSED", "PLAYING_ALONE", "SEEKING_ADULT"));
        assertRejected(o, "childStates_too_many");

        o = valid();
        o.put("adultResponses", List.of("WAITING", "TALKING", "JOINING_PLAY", "COMFORTING"));
        assertRejected(o, "adultResponses_too_many");
    }

    @Test
    void rejectsOverlongText() {
        Map<String, Object> o = valid();
        o.put("summary", "가".repeat(301));
        assertRejected(o, "summary_too_long");

        o = valid();
        o.put("moments", List.of(moment(1, "나".repeat(121))));
        assertRejected(o, "moment_note_too_long");

        o = valid();
        o.put("summary", 12);
        assertRejected(o, "summary_not_string");
    }

    @Test
    void rejectsMalformedMoments() {
        Map<String, Object> o = valid();
        List<Map<String, Object>> five = new ArrayList<>();
        for (int i = 0; i < 5; i++) five.add(moment(i, "메모 " + i));
        o.put("moments", five);
        assertRejected(o, "moments_too_many");

        o = valid();
        o.put("moments", List.of(moment(-1, "메모")));
        assertRejected(o, "moment_t_out_of_range");

        o = valid();
        o.put("moments", List.of(moment(1_000_000, "메모")));
        assertRejected(o, "moment_t_out_of_range");

        o = valid();
        o.put("moments", List.of(moment("12.5", "메모")));
        assertRejected(o, "moment_t_not_number");

        o = valid();
        o.put("moments", List.of(moment(1, null)));
        assertRejected(o, "moment_note_not_string");

        o = valid();
        o.put("moments", List.of("메모"));
        assertRejected(o, "moment_not_object");

        o = valid();
        o.put("moments", "메모");
        assertRejected(o, "moments_not_array");
    }

    @Test
    void rejectsPeopleCountOutsideZeroToThree() {
        for (Object bad : new Object[]{-1, 4, 1.5, Double.NaN}) {
            Map<String, Object> o = valid();
            o.put("peopleCount", bad);
            assertRejected(o, "peopleCount_out_of_range");
        }
        Map<String, Object> o = valid();
        o.put("peopleCount", "2");
        assertRejected(o, "peopleCount_not_number");
        o.remove("peopleCount");
        assertRejected(o, "peopleCount_not_number");
    }

    @Test
    void rejectsBrokenCharacters() {
        Map<String, Object> o = valid();
        o.put("summary", "아이가 웃어요 \uD83D"); // 짝이 없는 대리 문자
        assertRejected(o, "text_malformed");
    }
}
