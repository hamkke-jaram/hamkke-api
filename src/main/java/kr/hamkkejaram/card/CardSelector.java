package kr.hamkkejaram.card;

import kr.hamkkejaram.record.ChangeTrend;
import kr.hamkkejaram.record.MetricChanges;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 코칭 카드 선택 인터페이스(파트 간 약속 ③). 입력은 지표 요약, 출력은 카드 ID뿐이다.
 * 구현체는 문장을 만들지 않는다. 외부 AI 선택 서비스로 바꿀 때도 이 인터페이스를 지킨다.
 */
public interface CardSelector {

    Optional<Long> select(Context ctx);

    record Context(long childId,
                   ChangeTrend trend,
                   MetricFocus focus,
                   MetricChanges changes,
                   List<CoachingCard> activeCards,
                   Map<Long, Instant> lastDeliveredAt) {}
}
