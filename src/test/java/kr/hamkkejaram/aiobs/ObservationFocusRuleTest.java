package kr.hamkkejaram.aiobs;

import kr.hamkkejaram.card.MetricFocus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static kr.hamkkejaram.aiobs.ChildState.CALM;
import static kr.hamkkejaram.aiobs.ChildState.FOCUSED;
import static kr.hamkkejaram.aiobs.ChildState.MOVING_AROUND;
import static kr.hamkkejaram.aiobs.ChildState.PLAYING_ALONE;
import static kr.hamkkejaram.aiobs.ChildState.PLAYING_TOGETHER;
import static kr.hamkkejaram.aiobs.ChildState.REFUSING;
import static kr.hamkkejaram.aiobs.ChildState.SEEKING_ADULT;
import static kr.hamkkejaram.aiobs.ChildState.THROWING_OR_HITTING;
import static kr.hamkkejaram.aiobs.ChildState.UPSET_CRYING;
import static org.assertj.core.api.Assertions.assertThat;

/** 아이 모습 태그 → 카드 초점 (docs/ai-observation.md 7장 "카드 고르기"). */
class ObservationFocusRuleTest {

    @Test
    void anyHardMomentTagWins() {
        assertThat(ObservationFocusRule.focusFor(List.of(UPSET_CRYING))).isEqualTo(MetricFocus.HARD_MOMENT);
        assertThat(ObservationFocusRule.focusFor(List.of(REFUSING))).isEqualTo(MetricFocus.HARD_MOMENT);
        assertThat(ObservationFocusRule.focusFor(List.of(THROWING_OR_HITTING))).isEqualTo(MetricFocus.HARD_MOMENT);
        // 다른 태그가 함께 있어도, 순서가 뒤여도 먼저 본다
        assertThat(ObservationFocusRule.focusFor(List.of(PLAYING_ALONE, SEEKING_ADULT, REFUSING)))
                .isEqualTo(MetricFocus.HARD_MOMENT);
        assertThat(ObservationFocusRule.focusFor(List.of(PLAYING_TOGETHER, CALM, UPSET_CRYING)))
                .isEqualTo(MetricFocus.HARD_MOMENT);
    }

    @Test
    void playingAloneOrMovingAroundLeadsToJointPlay() {
        assertThat(ObservationFocusRule.focusFor(List.of(PLAYING_ALONE))).isEqualTo(MetricFocus.JOINT_PLAY);
        assertThat(ObservationFocusRule.focusFor(List.of(MOVING_AROUND))).isEqualTo(MetricFocus.JOINT_PLAY);
        // 어른에게 다가가는 모습이 함께 있어도 함께 놀기 쪽이 먼저
        assertThat(ObservationFocusRule.focusFor(List.of(SEEKING_ADULT, MOVING_AROUND)))
                .isEqualTo(MetricFocus.JOINT_PLAY);
    }

    @Test
    void seekingAdultLeadsToResponse() {
        assertThat(ObservationFocusRule.focusFor(List.of(SEEKING_ADULT))).isEqualTo(MetricFocus.RESPONSE);
        assertThat(ObservationFocusRule.focusFor(List.of(CALM, SEEKING_ADULT, FOCUSED))).isEqualTo(MetricFocus.RESPONSE);
    }

    @Test
    void everythingElseIsGeneral() {
        assertThat(ObservationFocusRule.focusFor(List.of())).isEqualTo(MetricFocus.GENERAL);
        assertThat(ObservationFocusRule.focusFor(List.of(PLAYING_TOGETHER))).isEqualTo(MetricFocus.GENERAL);
        assertThat(ObservationFocusRule.focusFor(List.of(FOCUSED, CALM))).isEqualTo(MetricFocus.GENERAL);
    }

    @Test
    void everyTagMapsToSomeFocus() {
        for (ChildState s : ChildState.values()) {
            assertThat(ObservationFocusRule.focusFor(List.of(s))).isNotNull();
        }
    }
}
