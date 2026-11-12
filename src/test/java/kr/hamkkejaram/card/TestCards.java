package kr.hamkkejaram.card;

import kr.hamkkejaram.record.ChangeTrend;
import kr.hamkkejaram.record.MetricChange;
import kr.hamkkejaram.record.MetricChanges;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 단위 테스트용 카드·선택 맥락 생성 도우미. 카드는 시드 SQL로만 들어가므로 엔티티에 생성자를 두지 않았다. */
final class TestCards {
    private TestCards() {}

    static CoachingCard card(long id, CardSituation situation, MetricFocus focus) {
        return card(id, situation, focus, true);
    }

    static CoachingCard card(long id, CardSituation situation, MetricFocus focus, boolean active) {
        CoachingCard c = new CoachingCard();
        ReflectionTestUtils.setField(c, "id", id);
        ReflectionTestUtils.setField(c, "code", "TEST-" + id);
        ReflectionTestUtils.setField(c, "title", "카드 " + id);
        ReflectionTestUtils.setField(c, "body", "본문 " + id);
        ReflectionTestUtils.setField(c, "situation", situation);
        ReflectionTestUtils.setField(c, "metricFocus", focus);
        ReflectionTestUtils.setField(c, "evidenceTag", "근거 태그");
        ReflectionTestUtils.setField(c, "sourceTitle", "출처 제목");
        ReflectionTestUtils.setField(c, "author", "함께자람 콘텐츠팀");
        ReflectionTestUtils.setField(c, "active", active);
        return c;
    }

    static CardSelector.Context ctx(ChangeTrend trend, MetricFocus focus, List<CoachingCard> cards,
                                    Map<Long, Instant> lastDelivered) {
        MetricChanges changes = new MetricChanges(MetricChange.FLAT, MetricChange.FLAT, MetricChange.FLAT);
        return new CardSelector.Context(1L, trend, focus, changes, cards, lastDelivered);
    }
}
