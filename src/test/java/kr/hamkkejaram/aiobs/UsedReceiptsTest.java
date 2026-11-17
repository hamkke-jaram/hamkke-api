package kr.hamkkejaram.aiobs;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static kr.hamkkejaram.aiobs.TestReceipts.SECRET;
import static kr.hamkkejaram.aiobs.TestReceipts.sign;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 쓴 영수증 기억(AIO-07, V6): 무엇을 언제까지 남기고 언제 지우는지. */
class UsedReceiptsTest {
    private static final Instant NOW = Instant.parse("2026-11-17T19:47:00Z");
    private static final String JTI = "0f8fad5b-d9cb-469f-a165-70867728950e";

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final AiUsedReceiptRepository repository = mock(AiUsedReceiptRepository.class);
    private final UsedReceipts usedReceipts = new UsedReceipts(repository, clock);

    @Test
    void keepsOnlyTheJtiUntilTheReceiptStopsBeingAccepted() {
        Instant receiptExpiry = NOW.plus(Duration.ofMinutes(15));

        usedReceipts.markUsed(JTI, receiptExpiry);

        ArgumentCaptor<AiUsedReceipt> saved = ArgumentCaptor.forClass(AiUsedReceipt.class);
        verify(repository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getJti()).isEqualTo(JTI);
        // 검증이 두 서버의 시계 차이만큼 더 받아 주므로, 그 시간까지 남긴다
        assertThat(saved.getValue().getExpiresAt()).isEqualTo(receiptExpiry.plus(AiReceiptVerifier.CLOCK_SKEW));
        // 조회 없이 바로 INSERT 한다(겹치면 기본 키 위반으로 드러난다)
        assertThat(saved.getValue().isNew()).isTrue();
    }

    @Test
    void usedMeansARowExistsForThatJti() {
        when(repository.existsById(JTI)).thenReturn(true);

        assertThat(usedReceipts.isUsed(JTI)).isTrue();
        assertThat(usedReceipts.isUsed("11111111-2222-3333-4444-555555555555")).isFalse();
    }

    @Test
    void purgeRemovesRowsWhoseExpiryPassedMoreThanTheGraceAgo() {
        Instant cutoff = NOW.minus(UsedReceipts.PURGE_GRACE);
        when(repository.deleteExpiredBefore(cutoff)).thenReturn(3);

        assertThat(usedReceipts.purgeExpired()).isEqualTo(3);
        verify(repository).deleteExpiredBefore(cutoff);
        // 여유는 0보다 길어야 한다: 아직 통하는 영수증의 행을 지우면 다시 저장할 수 있게 된다
        assertThat(UsedReceipts.PURGE_GRACE).isPositive();
    }

    @Test
    void verifiedReceiptCarriesItsExpiry() {
        AiSettings settings = new AiSettings(AiReceiptVerifierTest.props(true, SECRET), false);
        Map<String, Object> claims = TestReceipts.claims(11L, 7L, "HOME");
        claims.put("jti", JTI);
        claims.put("iat", NOW.getEpochSecond());
        claims.put("exp", NOW.getEpochSecond() + 600);

        AiReceiptVerifier.Verified verified = new AiReceiptVerifier(settings, clock).verify(sign(claims));

        assertThat(verified.jti()).isEqualTo(JTI);
        assertThat(verified.expiresAt()).isEqualTo(NOW.plusSeconds(600));
    }
}
