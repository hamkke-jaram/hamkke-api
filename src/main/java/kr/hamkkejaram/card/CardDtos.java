package kr.hamkkejaram.card;

import kr.hamkkejaram.support.SupportController.SupportResourceResponse;

import java.time.Instant;
import java.util.List;

public final class CardDtos {
    private CardDtos() {}

    public record CardResponse(long id, String title, String body, CardSituation situation, MetricFocus metricFocus,
                               String evidenceTag, String sourceTitle, String sourceUrl, String author,
                               String reviewer) {
        public static CardResponse from(CoachingCard c) {
            return new CardResponse(c.getId(), c.getTitle(), c.getBody(), c.getSituation(), c.getMetricFocus(),
                    c.getEvidenceTag(), c.getSourceTitle(), c.getSourceUrl(), c.getAuthor(), c.getReviewer());
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
