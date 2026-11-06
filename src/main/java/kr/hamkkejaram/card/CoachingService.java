package kr.hamkkejaram.card;

import kr.hamkkejaram.card.CardDtos.Coaching;
import kr.hamkkejaram.card.CardDtos.DeliveryResponse;
import kr.hamkkejaram.card.CardDtos.Guidance;
import kr.hamkkejaram.common.ApiException;
import kr.hamkkejaram.record.ChangeTrend;
import kr.hamkkejaram.record.InteractionRecord;
import kr.hamkkejaram.record.TrendCalculator;
import kr.hamkkejaram.support.SupportController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class CoachingService {
    private static final Logger log = LoggerFactory.getLogger(CoachingService.class);

    static final String GUIDANCE_MESSAGE = "최근 기록에서 몇 가지 변화가 함께 보였어요. "
            + "기록은 그날의 컨디션이나 촬영 환경에 따라서도 달라질 수 있어요. "
            + "마음이 쓰이신다면 혼자 고민하지 마시고 아래 기관에 편하게 이야기해 보세요. "
            + "어린이집 선생님과 함께 살펴보는 것도 도움이 돼요.";

    private final CardSelector selector;
    private final CoachingCardRepository cards;
    private final CardDeliveryRepository deliveries;
    private final SupportController support;
    private final Clock clock;

    public CoachingService(CardSelector selector, CoachingCardRepository cards, CardDeliveryRepository deliveries,
                           SupportController support, Clock clock) {
        this.selector = selector;
        this.cards = cards;
        this.deliveries = deliveries;
        this.support = support;
        this.clock = clock;
    }

    /** 새 기록에 맞는 코칭을 고르고 전달 이력을 남긴다. 선택이 실패해도 기록 저장은 막지 않는다. */
    @Transactional
    public Coaching deliverFor(InteractionRecord record, TrendCalculator.Result trend) {
        if (trend.trend() == ChangeTrend.NEEDS_ATTENTION) {
            return new Coaching(null, guidance());
        }
        List<CoachingCard> active = cards.findByActiveTrueOrderByIdAsc();
        Map<Long, Instant> last = new HashMap<>();
        for (Object[] row : deliveries.lastDeliveredByCard(record.getChildId())) {
            last.put((Long) row[0], (Instant) row[1]);
        }
        Optional<Long> cardId;
        try {
            cardId = selector.select(new CardSelector.Context(record.getChildId(), trend.trend(), trend.focus(),
                    trend.changes(), active, last));
        } catch (RuntimeException e) {
            log.warn("card selection failed; continuing without a card", e);
            cardId = Optional.empty();
        }
        Optional<CoachingCard> card = cardId.flatMap(id -> active.stream().filter(c -> c.getId().equals(id)).findFirst());
        if (card.isEmpty()) {
            return new Coaching(null, null);
        }
        CardDelivery delivery = deliveries.save(new CardDelivery(record.getChildId(), card.get(), record.getId(),
                clock.instant()));
        return new Coaching(DeliveryResponse.from(delivery), null);
    }

    /** 이미 저장된 기록의 코칭(재전송 응답용). */
    @Transactional(readOnly = true)
    public Coaching existingFor(InteractionRecord record) {
        if (record.getChangeTrend() == ChangeTrend.NEEDS_ATTENTION) {
            return new Coaching(null, guidance());
        }
        return new Coaching(deliveries.findByRecordId(record.getId()).map(DeliveryResponse::from).orElse(null), null);
    }

    @Transactional(readOnly = true)
    public List<DeliveryResponse> recent(long childId, int limit) {
        return deliveries.findRecent(childId, PageRequest.of(0, limit)).stream().map(DeliveryResponse::from).toList();
    }

    @Transactional
    public DeliveryResponse markTried(long childId, long deliveryId) {
        CardDelivery d = deliveries.findByIdAndChildId(deliveryId, childId).orElseThrow(ApiException::notFound);
        d.markTried(clock.instant());
        return DeliveryResponse.from(d);
    }

    public Guidance guidance() {
        return new Guidance(GUIDANCE_MESSAGE, support.all());
    }
}
