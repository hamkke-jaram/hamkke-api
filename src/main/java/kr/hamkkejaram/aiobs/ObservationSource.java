package kr.hamkkejaram.aiobs;

/** AI 관찰 기록이 만들어진 곳. 분석 토큰·영수증의 {@code src} 클레임 값과 같다. */
public enum ObservationSource {
    /** 보호자가 가정에서 만든 기록 */
    HOME,
    /** 연결된 선생님이 기관에서 만든 기록 */
    INSTITUTION
}
