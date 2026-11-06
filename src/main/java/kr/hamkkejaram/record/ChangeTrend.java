package kr.hamkkejaram.record;

/** 이전 기록과 비교한 전체 변화 추이. 평가가 아니라 "어떤 안내를 줄지" 고르는 데만 쓴다. */
public enum ChangeTrend {
    FIRST_RECORD,
    GROWING,
    STEADY,
    NEEDS_ATTENTION
}
