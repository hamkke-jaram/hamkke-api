package kr.hamkkejaram.card;

import kr.hamkkejaram.support.SupportController.SupportResourceResponse;

import java.time.Instant;
import java.util.List;

public final class CardDtos {
    private CardDtos() {}

    /**
     * code는 카드마다 하나뿐인 이름(예: {@code RS-A-01}). 화면이 카드에 맞는 그림을 고를 때 쓴다.
     * 응답 필드는 뒤에 덧붙이기만 한다(앞 필드의 이름·순서는 웹이 이미 쓰고 있다).
     */
    public record CardResponse(long id, String title, String body, CardSituation situation, MetricFocus metricFocus,
                               String evidenceTag, String sourceTitle, String sourceUrl, String author,
                               String reviewer, String code) {
        public static CardResponse from(CoachingCard c) {
            return new CardResponse(c.getId(), c.getTitle(), c.getBody(), c.getSituation(), c.getMetricFocus(),
                    c.getEvidenceTag(), c.getSourceTitle(), c.getSourceUrl(), c.getAuthor(), c.getReviewer(),
                    c.getCode());
        }
    }

    public record DeliveryResponse(long id, Instant deliveredAt, Instant triedAt, CardResponse card) {
        public static DeliveryResponse from(CardDelivery d) {
            return new DeliveryResponse(d.getId(), d.getDeliveredAt(), d.getTriedAt(), CardResponse.from(d.getCard()));
        }
    }

    public record Guidance(String message, List<SupportResourceResponse> resources) {}

    /** delivery와 guidance 중 하나만 채워진다(둘 다 비는 경우는 카드 후보가 없을 때). */
    public record Coaching(DeliveryResponse delivery, Guidance guidance) {}
}
