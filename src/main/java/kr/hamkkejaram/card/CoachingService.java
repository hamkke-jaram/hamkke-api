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
import java.util.Comparator;
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
    private final RuleBasedCardSelector ruleBased;
    private final CoachingCardRepository cards;
    private final CardDeliveryRepository deliveries;
    private final SupportController support;
    private final Clock clock;

    public CoachingService(CardSelector selector, RuleBasedCardSelector ruleBased, CoachingCardRepository cards,
                           CardDeliveryRepository deliveries, SupportController support, Clock clock) {
        this.selector = selector;
        this.ruleBased = ruleBased;
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
        CardSelector.Context ctx = new CardSelector.Context(record.getChildId(), trend.trend(), trend.focus(),
                trend.changes(), active, last);
        Optional<CoachingCard> card = selectWithFallback(ctx);
        if (card.isEmpty()) {
            return new Coaching(null, null); // 활성 카드가 한 장도 없을 때만
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

    /**
     * 카드 선택 장애 시 대체 규칙(SPEC 6.4).
     * 1) 기본 선택기가 실패(예외)하거나, 활성 카드에 없는 ID를 주거나, 아무 카드도 고르지 않으면
     * 2) 기본 선택기가 규칙 기반이 아닐 때 규칙 기반 선택기로 다시 고르고
     * 3) 그래도 없으면 상황이 맞는 GENERAL 카드 → ANY·GENERAL 카드 → 활성 카드 중 id가 가장 작은 카드 순으로 고른다.
     * 어느 단계도 예외를 밖으로 던지지 않으므로 기록 저장은 카드 선택 때문에 실패하지 않는다.
     */
    Optional<CoachingCard> selectWithFallback(CardSelector.Context ctx) {
        Optional<CoachingCard> card = tryPrimary(selector, ctx, "primary");
        if (card.isPresent()) return card;
        if (selector != ruleBased) {
            card = tryPrimary(ruleBased, ctx, "rule-based");
            if (card.isPresent()) return card;
        }
        card = fixedFallback(ctx);
        card.ifPresent(c -> log.warn("card selection fell back to fixed rule: childId={} cardId={}",
                ctx.childId(), c.getId()));
        return card;
    }

    private Optional<CoachingCard> tryPrimary(CardSelector s, CardSelector.Context ctx, String label) {
        Optional<Long> id;
        try {
            id = s.select(ctx);
        } catch (RuntimeException e) {
            log.warn("{} card selector failed: childId={}; using fallback", label, ctx.childId(), e);
            return Optional.empty();
        }
        if (id == null || id.isEmpty()) {
            log.warn("{} card selector returned no card: childId={}; using fallback", label, ctx.childId());
            return Optional.empty();
        }
        Optional<CoachingCard> card = findActive(ctx.activeCards(), id.get());
        if (card.isEmpty()) {
            log.warn("{} card selector returned unknown or inactive cardId={}: childId={}; using fallback",
                    label, id.get(), ctx.childId());
        }
        return card;
    }

    /** 외부 의존 없이 항상 같은 결과를 내는 마지막 대체 규칙. */
    static Optional<CoachingCard> fixedFallback(CardSelector.Context ctx) {
        List<CoachingCard> active = ctx.activeCards().stream().filter(CoachingCard::isActive)
                .sorted(Comparator.comparing(CoachingCard::getId)).toList();
        return active.stream()
                .filter(c -> c.getMetricFocus() == MetricFocus.GENERAL && c.getSituation().matches(ctx.trend()))
                // 상황이 정확히 맞는 카드 먼저, 그다음 ANY 카드
                .min(Comparator.comparing((CoachingCard c) -> c.getSituation() == CardSituation.ANY ? 1 : 0)
                        .thenComparing(CoachingCard::getId))
                .or(() -> active.stream().findFirst());
    }

    private static Optional<CoachingCard> findActive(List<CoachingCard> active, Long id) {
        return active.stream().filter(c -> c.isActive() && c.getId().equals(id)).findFirst();
    }
}
