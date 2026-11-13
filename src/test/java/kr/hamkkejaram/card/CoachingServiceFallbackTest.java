package kr.hamkkejaram.card;

import kr.hamkkejaram.card.CardDtos.Coaching;
import kr.hamkkejaram.record.ChangeTrend;
import kr.hamkkejaram.record.InteractionRecord;
import kr.hamkkejaram.record.MetricChange;
import kr.hamkkejaram.record.MetricChanges;
import kr.hamkkejaram.record.TrendCalculator;
import kr.hamkkejaram.support.SupportController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static kr.hamkkejaram.card.CardSituation.ANY;
import static kr.hamkkejaram.card.CardSituation.FIRST_RECORD;
import static kr.hamkkejaram.card.CardSituation.GROWING;
import static kr.hamkkejaram.card.CardSituation.STEADY;
import static kr.hamkkejaram.card.MetricFocus.CALM;
import static kr.hamkkejaram.card.MetricFocus.GENERAL;
import static kr.hamkkejaram.card.MetricFocus.HARD_MOMENT;
import static kr.hamkkejaram.card.MetricFocus.JOINT_PLAY;
import static kr.hamkkejaram.card.TestCards.card;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 카드 선택 장애 시 대체 규칙: 선택기가 실패해도 기록에는 카드 1장이 전달된다. */
class CoachingServiceFallbackTest {
    private static final Instant NOW = Instant.parse("2026-11-10T00:00:00Z");

    private final CoachingCardRepository cards = mock(CoachingCardRepository.class);
    private final CardDeliveryRepository deliveries = mock(CardDeliveryRepository.class);
    private final SupportController support = mock(SupportController.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final RuleBasedCardSelector ruleBased = new RuleBasedCardSelector();
    private final AtomicLong ids = new AtomicLong(500);

    private final List<CoachingCard> deck = List.of(
            card(1, FIRST_RECORD, GENERAL),
            card(3, GROWING, JOINT_PLAY),
            card(5, STEADY, JOINT_PLAY),
            card(7, ANY, GENERAL),
            card(9, GROWING, GENERAL));

    @BeforeEach
    void setUp() {
        when(cards.findByActiveTrueOrderByIdAsc()).thenReturn(deck);
        when(deliveries.lastDeliveredByCard(anyLong())).thenReturn(List.of());
        when(deliveries.save(any(CardDelivery.class))).thenAnswer(inv -> {
            CardDelivery d = inv.getArgument(0);
            ReflectionTestUtils.setField(d, "id", ids.incrementAndGet());
            return d;
        });
        when(support.all()).thenReturn(List.of());
    }

    private CoachingService service(CardSelector primary) {
        return new CoachingService(primary, ruleBased, cards, deliveries, support, clock);
    }

    private static InteractionRecord record() {
        InteractionRecord r = new InteractionRecord(1L, "00000000-0000-0000-0000-000000000001",
                NOW.minusSeconds(60), 120, 60, 1.2, 10, 8, 1, "test-model", "metrics-v1", NOW);
        ReflectionTestUtils.setField(r, "id", 100L);
        return r;
    }

    private static TrendCalculator.Result trend(ChangeTrend t, MetricFocus focus) {
        return new TrendCalculator.Result(t, new MetricChanges(MetricChange.UP, MetricChange.FLAT, MetricChange.FLAT),
                new double[3], focus);
    }

    @Test
    void throwingSelectorStillDeliversRuleBasedCard() {
        CardSelector broken = ctx -> {
            throw new IllegalStateException("selector down");
        };
        Coaching c = service(broken).deliverFor(record(), trend(ChangeTrend.GROWING, JOINT_PLAY));
        assertThat(c.guidance()).isNull();
        assertThat(c.delivery()).isNotNull();
        assertThat(c.delivery().card().id()).isEqualTo(3L); // 규칙 기반과 같은 결과
        verify(deliveries).save(any(CardDelivery.class));
    }

    @Test
    void unknownCardIdStillDeliversCard() {
        CardSelector wrongId = ctx -> Optional.of(999L);
        Coaching c = service(wrongId).deliverFor(record(), trend(ChangeTrend.GROWING, JOINT_PLAY));
        assertThat(c.delivery()).isNotNull();
        assertThat(c.delivery().card().id()).isEqualTo(3L);
    }

    @Test
    void inactiveCardIdStillDeliversActiveCard() {
        List<CoachingCard> withInactive = List.of(card(2, GROWING, JOINT_PLAY, false), card(3, GROWING, JOINT_PLAY));
        when(cards.findByActiveTrueOrderByIdAsc()).thenReturn(withInactive);
        Coaching c = service(ctx -> Optional.of(2L)).deliverFor(record(), trend(ChangeTrend.GROWING, JOINT_PLAY));
        assertThat(c.delivery().card().id()).isEqualTo(3L);
    }

    @Test
    void validSelectionIsUsedAsIs() {
        Coaching c = service(ctx -> Optional.of(7L)).deliverFor(record(), trend(ChangeTrend.GROWING, JOINT_PLAY));
        assertThat(c.delivery().card().id()).isEqualTo(7L);
    }

    @Test
    void whenRuleBasedAlsoFailsFixedRulePicksGeneralCardForSituation() {
        RuleBasedCardSelector brokenRules = new RuleBasedCardSelector() {
            @Override
            public Optional<Long> select(Context ctx) {
                throw new IllegalStateException("rules down");
            }
        };
        CoachingService s = new CoachingService(ctx -> {
            throw new IllegalStateException("selector down");
        }, brokenRules, cards, deliveries, support, clock);

        // GROWING: 상황이 정확히 맞는 GENERAL 카드(9)가 ANY 카드(7)보다 먼저
        assertThat(s.deliverFor(record(), trend(ChangeTrend.GROWING, JOINT_PLAY)).delivery().card().id())
                .isEqualTo(9L);
        // STEADY: 상황이 맞는 GENERAL 카드가 없으면 ANY·GENERAL 카드
        assertThat(s.deliverFor(record(), trend(ChangeTrend.STEADY, CALM)).delivery().card().id())
                .isEqualTo(7L);
    }

    @Test
    void ruleBasedEmptyResultFallsBackToAnyActiveCard() {
        when(cards.findByActiveTrueOrderByIdAsc()).thenReturn(List.of(card(5, STEADY, JOINT_PLAY)));
        Coaching c = service(ruleBased).deliverFor(record(), trend(ChangeTrend.GROWING, CALM));
        assertThat(c.delivery()).isNotNull();
        assertThat(c.delivery().card().id()).isEqualTo(5L);
    }

    @Test
    void noActiveCardsReturnsEmptyCoachingWithoutFailing() {
        when(cards.findByActiveTrueOrderByIdAsc()).thenReturn(List.of());
        Coaching c = service(ctx -> {
            throw new IllegalStateException("selector down");
        }).deliverFor(record(), trend(ChangeTrend.GROWING, JOINT_PLAY));
        assertThat(c.delivery()).isNull();
        assertThat(c.guidance()).isNull();
        verify(deliveries, never()).save(any(CardDelivery.class));
    }

    // ---------- AI 관찰 기록(가정)의 카드 ----------

    @Test
    void observationDeliversCardForFocusAndLinksItToTheObservation() {
        when(cards.findByActiveTrueOrderByIdAsc()).thenReturn(List.of(
                card(5, STEADY, JOINT_PLAY), card(7, ANY, GENERAL), card(17, ANY, HARD_MOMENT),
                card(18, ANY, HARD_MOMENT)));
        Coaching c = service(ruleBased).deliverForObservation(1L, 77L, HARD_MOMENT);
        assertThat(c.guidance()).isNull();
        assertThat(c.delivery().card().id()).isEqualTo(17L);
        assertThat(c.delivery().card().metricFocus()).isEqualTo(HARD_MOMENT);

        org.mockito.ArgumentCaptor<CardDelivery> saved = org.mockito.ArgumentCaptor.forClass(CardDelivery.class);
        verify(deliveries).save(saved.capture());
        assertThat(saved.getValue().getAiObservationId()).isEqualTo(77L);
        assertThat(saved.getValue().getRecordId()).isNull();
        assertThat(saved.getValue().getChildId()).isEqualTo(1L);
        assertThat(saved.getValue().getDeliveredAt()).isEqualTo(NOW);
    }

    @Test
    void observationSkipsCardsDeliveredRecently() {
        when(cards.findByActiveTrueOrderByIdAsc()).thenReturn(List.of(
                card(17, ANY, HARD_MOMENT), card(18, ANY, HARD_MOMENT), card(19, ANY, HARD_MOMENT),
                card(20, ANY, HARD_MOMENT)));
        when(deliveries.lastDeliveredByCard(anyLong())).thenReturn(List.of(
                new Object[]{17L, NOW.minusSeconds(300)}, new Object[]{18L, NOW.minusSeconds(200)},
                new Object[]{19L, NOW.minusSeconds(100)}));
        assertThat(service(ruleBased).deliverForObservation(1L, 78L, HARD_MOMENT).delivery().card().id())
                .isEqualTo(20L);
    }

    @Test
    void observationStillGetsACardWhenSelectorsFail() {
        RuleBasedCardSelector brokenRules = new RuleBasedCardSelector() {
            @Override
            public Optional<Long> select(Context ctx) {
                throw new IllegalStateException("rules down");
            }
        };
        CoachingService s = new CoachingService(ctx -> {
            throw new IllegalStateException("selector down");
        }, brokenRules, cards, deliveries, support, clock);
        // 고정 규칙: ANY·GENERAL 카드(7). 관찰 기록 저장은 카드 선택 때문에 실패하지 않는다.
        assertThat(s.deliverForObservation(1L, 79L, HARD_MOMENT).delivery().card().id()).isEqualTo(7L);
    }

    @Test
    void observationWithoutActiveCardsReturnsEmptyCoaching() {
        when(cards.findByActiveTrueOrderByIdAsc()).thenReturn(List.of());
        Coaching c = service(ruleBased).deliverForObservation(1L, 80L, GENERAL);
        assertThat(c.delivery()).isNull();
        assertThat(c.guidance()).isNull();
        verify(deliveries, never()).save(any(CardDelivery.class));
    }

    @Test
    void needsAttentionReturnsGuidanceNotCard() {
        CardSelector primary = mock(CardSelector.class);
        Coaching c = service(primary).deliverFor(record(), trend(ChangeTrend.NEEDS_ATTENTION, JOINT_PLAY));
        assertThat(c.delivery()).isNull();
        assertThat(c.guidance()).isNotNull();
        assertThat(c.guidance().message()).isEqualTo(CoachingService.GUIDANCE_MESSAGE);
        verify(primary, never()).select(any());
        verify(deliveries, never()).save(any(CardDelivery.class));
    }
}
