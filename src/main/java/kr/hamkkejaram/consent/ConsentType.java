package kr.hamkkejaram.consent;

public enum ConsentType {
    TERMS_OF_SERVICE,
    PRIVACY_POLICY,
    /** 만 14세 미만 아동 정보 수집·이용에 대한 법정대리인 동의 (아동별) */
    CHILD_DATA_GUARDIAN,
    /** 'AI 관찰 기록'을 위해 장면 사진(최대 6장)을 AI 서버로 보내는 데 대한 법정대리인 동의 (아동별) */
    CHILD_IMAGE_AI_ANALYSIS
}
