package kr.hamkkejaram.record;

import kr.hamkkejaram.card.MetricFocus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TrendCalculatorTest {

    private static MetricValues v(double joint, Double latency, double distress) {
        return new MetricValues(joint, latency, distress);
    }

    @Test
    void firstRecordHasNoComparison() {
        var r = TrendCalculator.compute(v(0.4, 1.5, 2), List.of());
        assertThat(r.trend()).isEqualTo(ChangeTrend.FIRST_RECORD);
        assertThat(r.changes()).isEqualTo(new MetricChanges(MetricChange.NA, MetricChange.NA, MetricChange.NA));
        assertThat(r.focus()).isEqualTo(MetricFocus.GENERAL);
    }

    @Test
    void jointPlayIncreaseIsGrowingWithJointPlayFocus() {
        var r = TrendCalculator.compute(v(0.60, 1.5, 2), List.of(v(0.40, 1.5, 2)));
        assertThat(r.trend()).isEqualTo(ChangeTrend.GROWING);
        assertThat(r.changes().jointPlay()).isEqualTo(MetricChange.UP);
        assertThat(r.focus()).isEqualTo(MetricFocus.JOINT_PLAY);
    }

    @Test
    void fasterResponseCountsAsUp() {
        var r = TrendCalculator.compute(v(0.6, 0.8, 2), List.of(v(0.6, 1.6, 2)));
        assertThat(r.changes().response()).isEqualTo(MetricChange.UP);
        assertThat(r.focus()).isEqualTo(MetricFocus.RESPONSE);
    }

    @Test
    void fewerDistressEpisodesCountsAsUp() {
        var r = TrendCalculator.compute(v(0.6, 1.0, 0.5), List.of(v(0.6, 1.0, 3.0)));
        assertThat(r.changes().calm()).isEqualTo(MetricChange.UP);
        assertThat(r.focus()).isEqualTo(MetricFocus.CALM);
    }

    @Test
    void smallChangesAreSteady() {
        var r = TrendCalculator.compute(v(0.62, 1.4, 2.2), List.of(v(0.60, 1.5, 2.0)));
        assertThat(r.trend()).isEqualTo(ChangeTrend.STEADY);
        assertThat(r.changes().count(MetricChange.FLAT)).isEqualTo(3);
    }

    @Test
    void steadyWithLowJointPlayFocusesOnJointPlay() {
        var r = TrendCalculator.compute(v(0.30, 1.0, 1.0), List.of(v(0.31, 1.0, 1.0)));
        assertThat(r.trend()).isEqualTo(ChangeTrend.STEADY);
        assertThat(r.focus()).isEqualTo(MetricFocus.JOINT_PLAY);
    }

    @Test
    void twoDownsWithOnlyOnePreviousRecordStaysSteady() {
        var r = TrendCalculator.compute(v(0.2, 3.0, 2), List.of(v(0.6, 1.0, 2)));
        assertThat(r.changes().count(MetricChange.DOWN)).isEqualTo(2);
        assertThat(r.trend()).isEqualTo(ChangeTrend.STEADY);
        assertThat(r.focus()).isEqualTo(MetricFocus.JOINT_PLAY); // 가장 크게 줄어든 지표
    }

    @Test
    void twoDownsWithEnoughHistoryNeedsAttention() {
        var r = TrendCalculator.compute(v(0.2, 3.0, 2), List.of(v(0.6, 1.0, 2), v(0.6, 1.0, 2)));
        assertThat(r.trend()).isEqualTo(ChangeTrend.NEEDS_ATTENTION);
    }

    @Test
    void missingLatencyIsNotCompared() {
        var r = TrendCalculator.compute(v(0.6, null, 2), List.of(v(0.6, 1.0, 2)));
        assertThat(r.changes().response()).isEqualTo(MetricChange.NA);
    }

    @Test
    void baselineUsesAtMostThreePreviousRecords() {
        // 최신 3개 평균 0.5 → 0.6은 UP. 오래된 4번째(0.9)가 섞이면 UP이 아니게 된다.
        var r = TrendCalculator.compute(v(0.6, 1.0, 2),
                List.of(v(0.5, 1.0, 2), v(0.5, 1.0, 2), v(0.5, 1.0, 2), v(0.9, 1.0, 2)));
        assertThat(r.changes().jointPlay()).isEqualTo(MetricChange.UP);
    }
}
