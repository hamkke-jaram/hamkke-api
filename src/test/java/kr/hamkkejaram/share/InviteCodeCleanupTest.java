package kr.hamkkejaram.share;

import kr.hamkkejaram.aiobs.AiConsentService;
import kr.hamkkejaram.child.ChildAccess;
import kr.hamkkejaram.child.ChildRepository;
import kr.hamkkejaram.record.WeeklySummaryService;
import kr.hamkkejaram.user.UserRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 만료·사용된 초대 코드는 7일이 지나면 지운다(누가 썼는지도 함께 사라진다). */
class InviteCodeCleanupTest {
    private static final Instant NOW = Instant.parse("2026-11-14T19:27:00Z");

    @Test
    void purgeUsesSevenDayCutoffFromTheServiceClock() {
        InviteCodeRepository codes = mock(InviteCodeRepository.class);
        Instant cutoff = NOW.minus(Duration.ofDays(7));
        when(codes.deleteStaleBefore(cutoff)).thenReturn(4);
        ShareService service = new ShareService(mock(ChildAccess.class), mock(ChildRepository.class),
                mock(ShareSettingRepository.class), codes, mock(TeacherLinkRepository.class),
                mock(UserRepository.class), mock(WeeklySummaryService.class), mock(AiConsentService.class),
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(service.purgeStaleInviteCodes()).isEqualTo(4);
        verify(codes).deleteStaleBefore(cutoff);
        assertThat(ShareService.STALE_CODE_RETENTION).hasDays(7);
    }
}
