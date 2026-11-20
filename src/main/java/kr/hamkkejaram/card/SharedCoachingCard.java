package kr.hamkkejaram.card;

/** 어린이집 추천의 근거로 허용한 코칭 카드 문구. 전달 이력과 아이 식별 정보는 포함하지 않는다. */
public record SharedCoachingCard(String code, String title, String body) {}
