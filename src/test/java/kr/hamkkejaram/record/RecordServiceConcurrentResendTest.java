package kr.hamkkejaram.record;

import kr.hamkkejaram.card.CardDtos.Coaching;
import kr.hamkkejaram.card.CoachingService;
import kr.hamkkejaram.child.ChildAccess;
import kr.hamkkejaram.record.RecordDtos.CreateRecordRequest;
import kr.hamkkejaram.security.AuthUser;
import kr.hamkkejaram.user.Role;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * REC-03: 같은 clientRecordId 두 요청이 거의 동시에 들어와 늦은 쪽이 UNIQUE 제약에 걸려도
 * 409가 아니라 먼저 저장된 기록의 결과(created=false → 200)를 돌려준다.
 */
class RecordServiceConcurrentResendTest {
    private static final Instant NOW = Instant.parse("2026-11-10T03:00:00Z");
    private static final AuthUser PARENT = new AuthUser(1L, Role.PARENT);
    private static final long CHILD = 7L;

    private final InteractionRecordRepository records = mock(InteractionRecordRepository.class);
    private final CoachingService coaching = mock(CoachingService.class);
    private final PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
    private final RecordService service;

    RecordServiceConcurrentResendTest() {
        when(txManager.getTransaction(any())).thenAnswer(inv -> new SimpleTransactionStatus());
        service = new RecordService(records, mock(ChildAccess.class), coaching, Clock.fixed(NOW, ZoneOffset.UTC),
                txManager);
    }

    private CreateRecordRequest request(String clientId) {
        return new CreateRecordRequest(clientId, NOW.minusSeconds(600), 120.0, 60.0, 1.2, 10, 8, 1,
                "pose_landmarker_lite+audio-heuristic-1", "metrics-v1");
    }

    private InteractionRecord savedByTheOtherRequest(String clientId) {
        InteractionRecord r = new InteractionRecord(CHILD, clientId, NOW.minusSeconds(600), 120, 60, 1.2, 10, 8, 1,
                "pose_landmarker_lite+audio-heuristic-1", "metrics-v1", NOW.minusSeconds(1));
        r.applyTrend(TrendCalculator.compute(r.values(), List.of()));
        ReflectionTestUtils.setField(r, "id", 99L);
        return r;
    }

    @Test
    void lateDuplicateInsertReturnsTheRecordSavedFirst() {
        String clientId = UUID.randomUUID().toString();
        InteractionRecord winner = savedByTheOtherRequest(clientId);
        // 처음 확인할 때는 아직 없고(다른 요청이 커밋 전), 저장은 UNIQUE 제약에 걸린다. 다시 읽으면 있다.
        when(records.findByChildIdAndClientRecordId(CHILD, clientId))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winner));
        when(records.findBaseline(anyLong(), anyString(), any(), any())).thenReturn(List.of());
        when(records.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("uq_records_client"));
        when(coaching.existingFor(winner)).thenReturn(new Coaching(null, null));

        RecordService.SaveResult result = service.save(PARENT, CHILD, request(clientId.toUpperCase()));

        assertThat(result.created()).isFalse();
        assertThat(result.body().record().id()).isEqualTo(99L);
        verify(coaching, never()).deliverFor(any(), any());
        verify(txManager).rollback(any()); // 실패한 저장 트랜잭션은 되돌린다
        verify(records, times(2)).findByChildIdAndClientRecordId(eq(CHILD), eq(clientId));
    }

    @Test
    void otherIntegrityViolationsStillSurface() {
        String clientId = UUID.randomUUID().toString();
        when(records.findByChildIdAndClientRecordId(CHILD, clientId)).thenReturn(Optional.empty());
        when(records.findBaseline(anyLong(), anyString(), any(), any())).thenReturn(List.of());
        when(records.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("fk_records_child"));

        assertThatThrownBy(() -> service.save(PARENT, CHILD, request(clientId)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
