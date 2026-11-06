package kr.hamkkejaram.record;

import kr.hamkkejaram.common.TimeConfig;
import kr.hamkkejaram.record.RecordDtos.WeekSummary;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** 실시간 수치 대신 주 단위(월요일 시작, 한국 시간)로 묶어 보여준다. */
@Service
public class WeeklySummaryService {
    public static final int DEFAULT_WEEKS = 8;
    public static final int MAX_WEEKS = 26;

    private final InteractionRecordRepository records;
    private final Clock clock;

    public WeeklySummaryService(InteractionRecordRepository records, Clock clock) {
        this.records = records;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<WeekSummary> summarize(long childId, int weeks) {
        int n = Math.max(1, Math.min(MAX_WEEKS, weeks));
        LocalDate thisMonday = LocalDate.now(clock.withZone(TimeConfig.SERVICE_ZONE))
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate fromMonday = thisMonday.minusWeeks(n - 1L);
        var from = fromMonday.atStartOfDay(TimeConfig.SERVICE_ZONE).toInstant();

        Map<LocalDate, List<InteractionRecord>> byWeek = new TreeMap<>();
        for (InteractionRecord r : records.findByChildIdAndRecordedAtGreaterThanEqualOrderByRecordedAtAsc(childId, from)) {
            LocalDate monday = r.getRecordedAt().atZone(TimeConfig.SERVICE_ZONE).toLocalDate()
                    .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            byWeek.computeIfAbsent(monday, k -> new ArrayList<>()).add(r);
        }
        List<WeekSummary> result = new ArrayList<>();
        byWeek.forEach((monday, list) -> result.add(new WeekSummary(
                monday,
                list.size(),
                avg(list.stream().map(InteractionRecord::jointPlayRatio).toList(), 3),
                avg(list.stream().map(InteractionRecord::getJointPlaySeconds).toList(), 1),
                avg(list.stream().map(InteractionRecord::getResponseLatencySec).filter(Objects::nonNull).toList(), 2),
                avg(list.stream().map(InteractionRecord::distressPerTenMinutes).toList(), 2))));
        return result;
    }

    private static Double avg(List<Double> values, int digits) {
        if (values.isEmpty()) return null;
        return RecordDtos.round(values.stream().mapToDouble(Double::doubleValue).average().orElse(0), digits);
    }
}
