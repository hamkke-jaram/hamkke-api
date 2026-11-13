package kr.hamkkejaram.aiobs;

import kr.hamkkejaram.card.MetricFocus;

import java.util.Collection;

/**
 * 아이 모습 태그 → 카드 초점(docs/ai-observation.md 7장 "카드 고르기").
 * 태그는 카드 묶음을 고르는 데만 쓴다. 조언 문장은 만들지 않고, 사람이 쓴 카드에서 고른다.
 *
 * <ol>
 *   <li>{@code UPSET_CRYING}·{@code REFUSING}·{@code THROWING_OR_HITTING} 중 하나라도 있으면 {@code HARD_MOMENT}</li>
 *   <li>없고 {@code PLAYING_ALONE}·{@code MOVING_AROUND}가 있으면 {@code JOINT_PLAY}</li>
 *   <li>없고 {@code SEEKING_ADULT}가 있으면 {@code RESPONSE}</li>
 *   <li>그 밖에는 {@code GENERAL}</li>
 * </ol>
 */
public final class ObservationFocusRule {
    private ObservationFocusRule() {}

    public static MetricFocus focusFor(Collection<ChildState> states) {
        if (states.contains(ChildState.UPSET_CRYING) || states.contains(ChildState.REFUSING)
                || states.contains(ChildState.THROWING_OR_HITTING)) {
            return MetricFocus.HARD_MOMENT;
        }
        if (states.contains(ChildState.PLAYING_ALONE) || states.contains(ChildState.MOVING_AROUND)) {
            return MetricFocus.JOINT_PLAY;
        }
        if (states.contains(ChildState.SEEKING_ADULT)) {
            return MetricFocus.RESPONSE;
        }
        return MetricFocus.GENERAL;
    }
}
