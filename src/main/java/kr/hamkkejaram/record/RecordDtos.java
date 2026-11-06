package kr.hamkkejaram.record;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import kr.hamkkejaram.card.CardDtos.Coaching;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class RecordDtos {
    private RecordDtos() {}

    /** 기기에서 계산한 지표. 필드 정의는 docs/metrics.md (metrics-v1). */
    public record CreateRecordRequest(
            @NotBlank @Pattern(regexp = "^[0-9a-fA-F-]{36}$", message = "UUID 형식이어야 해요.") String clientRecordId,
            @NotNull Instant recordedAt,
            @NotNull @DecimalMin("10.0") @DecimalMax("1800.0") Double analyzedSeconds,
            @NotNull @DecimalMin("0.0") @DecimalMax("1800.0") Double jointPlaySeconds,
            @DecimalMin("0.0") @DecimalMax("10.0") Double responseLatencySec,
            @NotNull @Min(0) @Max(2000) Integer childBidCount,
            @NotNull @Min(0) @Max(2000) Integer respondedBidCount,
            @NotNull @Min(0) @Max(200) Integer distressEpisodeCount,
            @NotBlank @Size(max = 100) String modelVersion,
            @NotBlank @Size(max = 20) String specVersion) {}

    public record RecordResponse(long id, Instant recordedAt, double analyzedSeconds, double jointPlaySeconds,
                                 double jointPlayRatio, Double responseLatencySec, int childBidCount,
                                 int respondedBidCount, int distressEpisodeCount, double distressPerTenMinutes,
                                 ChangeTrend changeTrend, String modelVersion, String specVersion,
                                 Instant createdAt) {
        public static RecordResponse from(InteractionRecord r) {
            return new RecordResponse(r.getId(), r.getRecordedAt(), r.getAnalyzedSeconds(), r.getJointPlaySeconds(),
                    round(r.jointPlayRatio(), 3), r.getResponseLatencySec(), r.getChildBidCount(),
                    r.getRespondedBidCount(), r.getDistressCount(), round(r.distressPerTenMinutes(), 2),
                    r.getChangeTrend(), r.getModelVersion(), r.getSpecVersion(), r.getCreatedAt());
        }
    }

    public record SaveRecordResponse(RecordResponse record, MetricChanges metricChanges, Coaching coaching) {}

    public record WeekSummary(LocalDate weekStart, int recordCount, Double jointPlayRatio, Double jointPlaySeconds,
                              Double responseLatencySec, Double distressPerTenMinutes) {}

    public record WeeklySummaryResponse(List<WeekSummary> weeks) {}

    static double round(double v, int digits) {
        double f = Math.pow(10, digits);
        return Math.round(v * f) / f;
    }
}
