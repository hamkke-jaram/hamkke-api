package kr.hamkkejaram.card;

/**
 * 카드가 다루는 초점. CALM은 울음·감정 폭발 지표에 대응한다.
 * HARD_MOMENT('힘든 순간 함께하기')는 AI 관찰 기록의 아이 모습 태그로만 고른다(docs/ai-observation.md 7장).
 */
public enum MetricFocus {
    JOINT_PLAY,
    RESPONSE,
    CALM,
    GENERAL,
    HARD_MOMENT
}
