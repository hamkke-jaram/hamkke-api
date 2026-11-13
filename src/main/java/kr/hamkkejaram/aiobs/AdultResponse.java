package kr.hamkkejaram.aiobs;

/** 어른 반응 태그(docs/ai-observation.md 6장). 화면 표시 문구는 주석과 같다. */
public enum AdultResponse {
    /** 놀이에 함께해요 */
    JOINING_PLAY,
    /** 말을 걸어 줘요 */
    TALKING,
    /** 기다려 줘요 */
    WAITING,
    /** 안아 주거나 달래 줘요 */
    COMFORTING,
    /** 다른 놀이로 이끌어요 */
    REDIRECTING,
    /** 안 되는 것을 알려 줘요 */
    SETTING_LIMIT
}
