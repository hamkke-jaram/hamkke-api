package kr.hamkkejaram.card;

import kr.hamkkejaram.record.ChangeTrend;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** 규칙 기반 카드 선택 v1 (SPEC 6.3). */
@Component
public class RuleBasedCardSelector implements CardSelector {
    static final int RECENT_SKIP = 3;

    @Override
    public Optional<Long> select(Context ctx) {
        if (ctx.trend() == ChangeTrend.NEEDS_ATTENTION) {
            return Optional.empty(); // 카드 대신 상담·기관 안내
        }
        List<CoachingCard> candidates = ctx.activeCards().stream()
                .filter(c -> c.getSituation().matches(ctx.trend()))
                .filter(c -> c.getMetricFocus() == ctx.focus() || c.getMetricFocus() == MetricFocus.GENERAL)
                .toList();
        if (candidates.isEmpty()) {
            candidates = ctx.activeCards().stream()
                    .filter(c -> c.getSituation() == CardSituation.ANY && c.getMetricFocus() == MetricFocus.GENERAL)
                    .toList();
        }
        // 최근에 받은 카드 3장은 다른 후보가 있으면 건너뛴다(같은 카드 반복 방지).
        List<Long> recent = ctx.lastDeliveredAt().entrySet().stream()
                .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                .limit(RECENT_SKIP).map(java.util.Map.Entry::getKey).toList();
        List<CoachingCard> fresh = candidates.stream().filter(c -> !recent.contains(c.getId())).toList();
        if (!fresh.isEmpty()) candidates = fresh;

        Comparator<CoachingCard> order = Comparator
                // 관련 지표가 정확히 맞는 카드 먼저
                .comparing((CoachingCard c) -> c.getMetricFocus() == ctx.focus() ? 0 : 1)
                // 상황이 정확히 맞는 카드 먼저
                .thenComparing(c -> c.getSituation().name().equals(ctx.trend().name()) ? 0 : 1)
                // 받은 적 없거나 가장 오래전에 받은 카드 먼저
                .thenComparing(c -> ctx.lastDeliveredAt().getOrDefault(c.getId(), Instant.MIN))
                .thenComparing(CoachingCard::getId);
        return candidates.stream().min(order).map(CoachingCard::getId);
    }
}
