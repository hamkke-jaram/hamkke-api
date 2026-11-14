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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

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
    private final TransactionTemplate tx;
    private final TransactionTemplate readTx;

    public RecordService(InteractionRecordRepository records, ChildAccess access, CoachingService coaching,
                         Clock clock, PlatformTransactionManager txManager) {
        this.records = records;
        this.access = access;
        this.coaching = coaching;
        this.clock = clock;
        this.tx = new TransactionTemplate(txManager);
        this.readTx = new TransactionTemplate(txManager);
        this.readTx.setReadOnly(true);
    }

    public record SaveResult(SaveRecordResponse body, boolean created) {}

    /**
     * 기록 저장(REC-03: 같은 clientRecordId 재전송은 기존 결과를 200으로 돌려준다).
     * 같은 clientRecordId 두 요청이 거의 동시에 들어오면 늦은 쪽은 UNIQUE 제약에 걸린다. 그때는 실패한 트랜잭션을
     * 되돌린 뒤 새 읽기 트랜잭션에서 먼저 저장된 기록을 찾아 같은 결과를 돌려준다(409를 내지 않는다).
     */
    public SaveResult save(AuthUser user, long childId, CreateRecordRequest req) {
        String clientId = req.clientRecordId().toLowerCase(Locale.ROOT);
        try {
            return tx.execute(status -> saveOnce(user, childId, req, clientId));
        } catch (DataIntegrityViolationException e) {
            SaveResult existing = readTx.execute(status -> records.findByChildIdAndClientRecordId(childId, clientId)
                    .map(this::existingResult).orElse(null));
            if (existing == null) throw e; // 다른 제약 위반이면 원래대로(409)
            return existing;
        }
    }

    private SaveResult existingResult(InteractionRecord r) {
        return new SaveResult(new SaveRecordResponse(RecordResponse.from(r), r.changes(), coaching.existingFor(r)),
                false);
    }

    private SaveResult saveOnce(AuthUser user, long childId, CreateRecordRequest req, String clientId) {
        access.requireOwner(user, childId);

        Optional<InteractionRecord> existing = records.findByChildIdAndClientRecordId(childId, clientId);
        if (existing.isPresent()) {
            return existingResult(existing.get());
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
    public List<RecordResponse> list(AuthUser user, long childId, int limit, Long beforeId) {
        access.requireOwner(user, childId);
        PageRequest page = PageRequest.of(0, Math.max(1, Math.min(100, limit)));
        List<InteractionRecord> found;
        if (beforeId == null) {
            found = records.findByChildIdOrderByRecordedAtDescIdDesc(childId, page);
        } else {
            InteractionRecord anchor = records.findByIdAndChildId(beforeId, childId)
                    .orElseThrow(ApiException::unknownCursor);
            found = records.findOlderThan(childId, anchor.getRecordedAt(), anchor.getId(), page);
        }
        return found.stream().map(RecordResponse::from).toList();
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
