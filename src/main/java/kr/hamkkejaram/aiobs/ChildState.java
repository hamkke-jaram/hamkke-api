package kr.hamkkejaram.aiobs;

/**
 * 아이 모습 태그(docs/ai-observation.md 6장). 화면에 보인 모습을 적는 말이며 아이를 평가하거나 나누는 값이 아니다.
 * 화면 표시 문구는 주석과 같다.
 */
public enum ChildState {
    /** 어른과 함께 놀아요 */
    PLAYING_TOGETHER,
    /** 혼자 놀아요 */
    PLAYING_ALONE,
    /** 한 가지에 푹 빠져 있어요 */
    FOCUSED,
    /** 자리를 자주 옮겨요 */
    MOVING_AROUND,
    /** 어른에게 다가가요 */
    SEEKING_ADULT,
    /** 울거나 속상해해요 */
    UPSET_CRYING,
    /** 싫다고 표현해요 */
    REFUSING,
    /** 물건을 던지거나 쳐요 */
    THROWING_OR_HITTING,
    /** 차분해요 */
    CALM
}
