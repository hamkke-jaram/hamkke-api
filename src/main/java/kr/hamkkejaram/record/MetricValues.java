package kr.hamkkejaram.record;

/**
 * 비교용으로 정규화한 지표 값.
 *
 * @param jointPlayRatio         함께 노는 시간 / 분석 길이 (0~1, 높을수록 좋아지는 방향)
 * @param responseLatencySec     반응 시간 중앙값(초, 없으면 null, 낮을수록 좋아지는 방향)
 * @param distressPerTenMinutes  10분당 울음·감정 폭발 횟수 (낮을수록 좋아지는 방향)
 */
public record MetricValues(double jointPlayRatio, Double responseLatencySec, double distressPerTenMinutes) {}
