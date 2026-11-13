package kr.hamkkejaram.card;

import kr.hamkkejaram.record.ChangeTrend;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static kr.hamkkejaram.card.CardSituation.ANY;
import static kr.hamkkejaram.card.CardSituation.FIRST_RECORD;
import static kr.hamkkejaram.card.CardSituation.GROWING;
import static kr.hamkkejaram.card.CardSituation.STEADY;
import static kr.hamkkejaram.card.MetricFocus.CALM;
import static kr.hamkkejaram.card.MetricFocus.GENERAL;
import static kr.hamkkejaram.card.MetricFocus.HARD_MOMENT;
import static kr.hamkkejaram.card.MetricFocus.JOINT_PLAY;
import static kr.hamkkejaram.card.MetricFocus.RESPONSE;
import static kr.hamkkejaram.card.TestCards.card;
import static kr.hamkkejaram.card.TestCards.ctx;
import static org.assertj.core.api.Assertions.assertThat;

class RuleBasedCardSelectorTest {
    private static final Instant T = Instant.parse("2026-11-01T00:00:00Z");

    private final RuleBasedCardSelector selector = new RuleBasedCardSelector();

    private final List<CoachingCard> deck = List.of(
            card(1, FIRST_RECORD, GENERAL),
            card(2, FIRST_RECORD, GENERAL),
            card(3, GROWING, JOINT_PLAY),
            card(4, GROWING, JOINT_PLAY),
            card(5, STEADY, JOINT_PLAY),
            card(6, STEADY, CALM),
            card(7, ANY, GENERAL),
            card(8, GROWING, RESPONSE));

    @Test
    void firstRecordGetsFirstRecordCard() {
        assertThat(selector.select(ctx(ChangeTrend.FIRST_RECORD, GENERAL, deck, Map.of()))).contains(1L);
    }

    @Test
    void growingPrefersExactMetricFocus() {
        assertThat(selector.select(ctx(ChangeTrend.GROWING, JOINT_PLAY, deck, Map.of()))).contains(3L);
        assertThat(selector.select(ctx(ChangeTrend.GROWING, RESPONSE, deck, Map.of()))).contains(8L);
    }

    @Test
    void steadyPrefersExactMetricFocus() {
        assertThat(selector.select(ctx(ChangeTrend.STEADY, CALM, deck, Map.of()))).contains(6L);
        assertThat(selector.select(ctx(ChangeTrend.STEADY, JOINT_PLAY, deck, Map.of()))).contains(5L);
    }

    @Test
    void steadyWithoutMatchingFocusUsesGeneralCard() {
        assertThat(selector.select(ctx(ChangeTrend.STEADY, RESPONSE, deck, Map.of()))).contains(7L);
    }

    @Test
    void unmatchedSituationUsesAnyGeneralCardOrNothing() {
        List<CoachingCard> cards = List.of(card(5, STEADY, JOINT_PLAY), card(7, ANY, GENERAL));
        assertThat(selector.select(ctx(ChangeTrend.GROWING, RESPONSE, cards, Map.of()))).contains(7L);
        // 맞는 카드가 하나도 없으면 비워서 돌려준다(대체 규칙은 CoachingService가 맡는다).
        assertThat(selector.select(ctx(ChangeTrend.GROWING, RESPONSE, List.of(card(5, STEADY, JOINT_PLAY)),
                Map.of()))).isEmpty();
    }

    @Test
    void needsAttentionGivesGuidanceInsteadOfCard() {
        assertThat(selector.select(ctx(ChangeTrend.NEEDS_ATTENTION, JOINT_PLAY, deck, Map.of()))).isEmpty();
    }

    @Test
    void skipsTheLastThreeDeliveredCards() {
        List<CoachingCard> cards = List.of(
                card(21, GROWING, JOINT_PLAY), card(22, GROWING, JOINT_PLAY),
                card(23, GROWING, JOINT_PLAY), card(24, GROWING, JOINT_PLAY));
        // 최근 3장(22·23·24)은 건너뛰고, 아직 받지 않은 21을 고른다.
        Map<Long, Instant> last = Map.of(22L, T, 23L, T.plusSeconds(60), 24L, T.plusSeconds(120));
        assertThat(selector.select(ctx(ChangeTrend.GROWING, JOINT_PLAY, cards, last))).contains(21L);

        // 4장 모두 받았다면 최근 3장을 뺀 가장 오래된 카드(21)가 다시 나온다.
        Map<Long, Instant> all = Map.of(21L, T, 22L, T.plusSeconds(60), 23L, T.plusSeconds(120),
                24L, T.plusSeconds(180));
        assertThat(selector.select(ctx(ChangeTrend.GROWING, JOINT_PLAY, cards, all))).contains(21L);
    }

    @Test
    void rotationPrefersNeverDeliveredThenOldest() {
        List<CoachingCard> cards = List.of(
                card(31, GROWING, JOINT_PLAY), card(32, GROWING, JOINT_PLAY), card(33, GROWING, JOINT_PLAY),
                card(34, GROWING, JOINT_PLAY), card(35, GROWING, JOINT_PLAY));
        // 31은 오래전에, 33·34·35는 최근에 받았다 → 받은 적 없는 32가 먼저
        Map<Long, Instant> last = Map.of(31L, T, 33L, T.plusSeconds(60), 34L, T.plusSeconds(120),
                35L, T.plusSeconds(180));
        assertThat(selector.select(ctx(ChangeTrend.GROWING, JOINT_PLAY, cards, last))).contains(32L);
    }

    /** AI 관찰 기록(가정)의 카드: 상황은 ANY·STEADY에서, 초점은 태그로 정한 것(docs/ai-observation.md 7장). */
    @Test
    void hardMomentFocusPicksHardMomentCardsFromAnyOrSteady() {
        List<CoachingCard> cards = List.of(
                card(5, STEADY, JOINT_PLAY), card(7, ANY, GENERAL), card(14, STEADY, GENERAL),
                card(50, GROWING, HARD_MOMENT), card(51, ANY, HARD_MOMENT), card(52, ANY, HARD_MOMENT),
                card(53, STEADY, HARD_MOMENT));
        // 초점이 정확히 맞는 카드 먼저, 그중 STEADY 카드 먼저. GROWING 카드(50)는 후보가 아니다.
        assertThat(selector.select(ctx(ChangeTrend.STEADY, HARD_MOMENT, cards, Map.of()))).contains(53L);
        // 최근 받은 3장(51·52·53)을 건너뛰면 GENERAL 카드로 넘어간다
        Map<Long, Instant> last = Map.of(51L, T, 52L, T.plusSeconds(60), 53L, T.plusSeconds(120));
        assertThat(selector.select(ctx(ChangeTrend.STEADY, HARD_MOMENT, cards, last))).contains(14L);
    }

    @Test
    void hardMomentCardsAreNeverPickedForMetricRecords() {
        List<CoachingCard> cards = List.of(card(51, ANY, HARD_MOMENT), card(7, ANY, GENERAL));
        for (MetricFocus focus : List.of(JOINT_PLAY, RESPONSE, CALM, GENERAL)) {
            for (ChangeTrend trend : List.of(ChangeTrend.FIRST_RECORD, ChangeTrend.GROWING, ChangeTrend.STEADY)) {
                assertThat(selector.select(ctx(trend, focus, cards, Map.of()))).contains(7L);
            }
        }
    }

    @Test
    void whenEveryCandidateIsRecentTheOldestIsReused() {
        List<CoachingCard> cards = List.of(card(41, GROWING, JOINT_PLAY), card(42, GROWING, JOINT_PLAY));
        Map<Long, Instant> last = Map.of(41L, T.plusSeconds(60), 42L, T);
        assertThat(selector.select(ctx(ChangeTrend.GROWING, JOINT_PLAY, cards, last))).contains(42L);
    }
}
