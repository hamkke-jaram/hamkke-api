package kr.hamkkejaram.record;

import kr.hamkkejaram.card.CardDtos.Coaching;
import kr.hamkkejaram.card.CoachingService;
import kr.hamkkejaram.child.ChildAccess;
import kr.hamkkejaram.common.ApiException;
import kr.hamkkejaram.common.ErrorCode;
import kr.hamkkejaram.record.RecordDtos.CreateRecordRequest;
import kr.hamkkejaram.record.RecordDtos.RecordResponse;
import kr.hamkkejaram.record.RecordDtos.SaveRecordResponse;
import kr.hamkkejaram.security.AuthUser;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

@Service
public class RecordService {
    public static final Set<String> SUPPORTED_SPEC_VERSIONS = Set.of("metrics-v1");
    private static final Duration CLOCK_SKEW = Duration.ofMinutes(10);

    private final InteractionRecordRepository records;
    private final ChildAccess access;
    private final CoachingService coaching;
    private final Clock clock;

    public RecordService(InteractionRecordRepository records, ChildAccess access, CoachingService coaching,
                         Clock clock) {
        this.records = records;
        this.access = access;
        this.coaching = coaching;
        this.clock = clock;
    }

    public record SaveResult(SaveRecordResponse body, boolean created) {}

    @Transactional
    public SaveResult save(AuthUser user, long childId, CreateRecordRequest req) {
        access.requireOwner(user, childId);
        String clientId = req.clientRecordId().toLowerCase(Locale.ROOT);

        Optional<InteractionRecord> existing = records.findByChildIdAndClientRecordId(childId, clientId);
        if (existing.isPresent()) {
            InteractionRecord r = existing.get();
            return new SaveResult(new SaveRecordResponse(RecordResponse.from(r), r.changes(),
                    coaching.existingFor(r)), false);
        }

        validate(req);
        Instant now = clock.instant();
        List<MetricValues> baseline = records.findBaseline(childId, req.specVersion(), req.recordedAt(),
                        PageRequest.of(0, TrendCalculator.BASELINE_SIZE))
                .stream().map(InteractionRecord::values).toList();

        InteractionRecord record = new InteractionRecord(childId, clientId, req.recordedAt(),
                req.analyzedSeconds(), req.jointPlaySeconds(), req.responseLatencySec(), req.childBidCount(),
                req.respondedBidCount(), req.distressEpisodeCount(), req.modelVersion().trim(), req.specVersion(),
                now);
        TrendCalculator.Result trend = TrendCalculator.compute(record.values(), baseline);
        record.applyTrend(trend);
        record = records.saveAndFlush(record);

        Coaching result = coaching.deliverFor(record, trend);
        return new SaveResult(new SaveRecordResponse(RecordResponse.from(record), trend.changes(), result), true);
    }

    @Transactional(readOnly = true)
    public List<RecordResponse> list(AuthUser user, long childId, int limit) {
        access.requireOwner(user, childId);
        int n = Math.max(1, Math.min(100, limit));
        return records.findByChildIdOrderByRecordedAtDescIdDesc(childId, PageRequest.of(0, n)).stream()
                .map(RecordResponse::from).toList();
    }

    @Transactional
    public void delete(AuthUser user, long childId, long recordId) {
        access.requireOwner(user, childId);
        InteractionRecord r = records.findByIdAndChildId(recordId, childId).orElseThrow(ApiException::notFound);
        records.delete(r);
    }

    private void validate(CreateRecordRequest req) {
        if (!SUPPORTED_SPEC_VERSIONS.contains(req.specVersion())) {
            throw new ApiException(ErrorCode.UNSUPPORTED_SPEC_VERSION);
        }
        if (req.jointPlaySeconds() > req.analyzedSeconds()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "함께 노는 시간은 분석 길이보다 길 수 없어요.");
        }
        if (req.respondedBidCount() > req.childBidCount()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "반응한 발화 수는 아이 발화 수보다 많을 수 없어요.");
        }
        if (req.recordedAt().isAfter(clock.instant().plus(CLOCK_SKEW))) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "촬영 시각이 미래일 수 없어요.");
        }
    }
}
