package kr.hamkkejaram.support;

/** 정보가 어디까지 확인됐는지. 화면은 이 값으로 '공개 기관 정보'와 '확인된 정보'를 나눠 보인다(SUP-04). */
public enum FacilityStatus {
    /** 공공데이터를 그대로 옮긴 정보 */
    PUBLIC_DATA,
    /** 함께자람이 기관에 직접 확인한 정보 */
    CONFIRMED
}
