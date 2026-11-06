package kr.hamkkejaram.record;

import kr.hamkkejaram.card.MetricFocus;

import java.util.List;
import java.util.Objects;

/**
 * 변화 추이 계산 (SPEC 6.1~6.3). 상태가 없는 순수 함수라 단위 테스트로 규칙을 고정한다.
 */
public final class TrendCalculator {
    public static final int BASELINE_SIZE = 3;
    static final double JOINT_PLAY_THRESHOLD = 0.05;
    static final double RESPONSE_THRESHOLD_SEC = 0.3;
    static final double DISTRESS_THRESHOLD_PER_10MIN = 1.0;

    private TrendCalculator() {}

    /**
     * @param improvement 지표별 개선량을 임계값으로 나눈 값(양수 = 좋아지는 방향). 비교 불가면 0.
     * @param focus       카드 선택에 쓸 관련 지표
     */
    public record Result(ChangeTrend trend, MetricChanges changes, double[] improvement, MetricFocus focus) {}

    /**
     * @param current  이번 기록
     * @param previous 이번 기록 이전의 기록(최신순, 최대 {@link #BASELINE_SIZE}개를 사용)
     */
    public static Result compute(MetricValues current, List<MetricValues> previous) {
        List<MetricValues> base = previous.stream().limit(BASELINE_SIZE).toList();
        if (base.isEmpty()) {
            MetricChanges na = new MetricChanges(MetricChange.NA, MetricChange.NA, MetricChange.NA);
            return new Result(ChangeTrend.FIRST_RECORD, na, new double[]{0, 0, 0}, MetricFocus.GENERAL);
        }

        double baseJoint = base.stream().mapToDouble(MetricValues::jointPlayRatio).average().orElse(0);
        double baseDistress = base.stream().mapToDouble(MetricValues::distressPerTenMinutes).average().orElse(0);
        var latencies = base.stream().map(MetricValues::responseLatencySec).filter(Objects::nonNull).toList();
        Double baseLatency = latencies.isEmpty() ? null
                : latencies.stream().mapToDouble(Double::doubleValue).average().orElse(0);

        // 개선량: 함께 노는 비율은 늘수록, 반응 시간·울음은 줄수록 양수
        double jointImp = (current.jointPlayRatio() - baseJoint) / JOINT_PLAY_THRESHOLD;
        Double respImp = (baseLatency == null || current.responseLatencySec() == null) ? null
                : (baseLatency - current.responseLatencySec()) / RESPONSE_THRESHOLD_SEC;
        double calmImp = (baseDistress - current.distressPerTenMinutes()) / DISTRESS_THRESHOLD_PER_10MIN;

        MetricChanges changes = new MetricChanges(classify(jointImp), respImp == null ? MetricChange.NA
                : classify(respImp), classify(calmImp));
        double[] improvement = {jointImp, respImp == null ? 0 : respImp, calmImp};

        ChangeTrend trend;
        if (changes.count(MetricChange.DOWN) >= 2 && base.size() >= 2) {
            trend = ChangeTrend.NEEDS_ATTENTION;
        } else if (changes.count(MetricChange.UP) >= 1 && changes.count(MetricChange.DOWN) == 0) {
            trend = ChangeTrend.GROWING;
        } else {
            trend = ChangeTrend.STEADY;
        }
        return new Result(trend, changes, improvement, focusFor(trend, changes, improvement, current));
    }

    private static MetricChange classify(double normalizedImprovement) {
        if (normalizedImprovement >= 1.0) return MetricChange.UP;
        if (normalizedImprovement <= -1.0) return MetricChange.DOWN;
        return MetricChange.FLAT;
    }

    private static final MetricFocus[] ORDER = {MetricFocus.JOINT_PLAY, MetricFocus.RESPONSE, MetricFocus.CALM};

    private static MetricFocus focusFor(ChangeTrend trend, MetricChanges changes, double[] imp, MetricValues cur) {
        MetricChange[] c = {changes.jointPlay(), changes.response(), changes.calm()};
        switch (trend) {
            case GROWING -> {
                int best = -1;
                for (int i = 0; i < 3; i++) {
                    if (c[i] == MetricChange.UP && (best < 0 || imp[i] > imp[best])) best = i;
                }
                return best < 0 ? MetricFocus.GENERAL : ORDER[best];
            }
            case STEADY -> {
                int worst = -1;
                for (int i = 0; i < 3; i++) {
                    if (c[i] == MetricChange.DOWN && (worst < 0 || imp[i] < imp[worst])) worst = i;
                }
                if (worst >= 0) return ORDER[worst];
                if (cur.jointPlayRatio() < 0.5) return MetricFocus.JOINT_PLAY;
                if (cur.responseLatencySec() != null && cur.responseLatencySec() > 2.0) return MetricFocus.RESPONSE;
                if (cur.distressPerTenMinutes() > 2.0) return MetricFocus.CALM;
                return MetricFocus.GENERAL;
            }
            default -> {
                return MetricFocus.GENERAL;
            }
        }
    }
}
