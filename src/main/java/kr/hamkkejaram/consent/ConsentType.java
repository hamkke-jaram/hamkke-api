package kr.hamkkejaram.consent;

public enum ConsentType {
    TERMS_OF_SERVICE,
    PRIVACY_POLICY,
    /** 만 14세 미만 아동 정보 수집·이용에 대한 법정대리인 동의 (아동별) */
    CHILD_DATA_GUARDIAN,
    /** 'AI 관찰 기록' 사진 전송에 대한 아이별 법정대리인 동의. 문서 버전으로 사진 한도를 구분한다. */
    CHILD_IMAGE_AI_ANALYSIS
}
