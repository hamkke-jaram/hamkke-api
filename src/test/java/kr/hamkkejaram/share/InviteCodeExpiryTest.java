package kr.hamkkejaram.share;

import kr.hamkkejaram.aiobs.AiConsentService;
import kr.hamkkejaram.child.Child;
import kr.hamkkejaram.child.ChildAccess;
import kr.hamkkejaram.child.ChildRepository;
import kr.hamkkejaram.common.ApiException;
import kr.hamkkejaram.common.ErrorCode;
import kr.hamkkejaram.record.WeeklySummaryService;
import kr.hamkkejaram.security.AuthUser;
import kr.hamkkejaram.user.Role;
import kr.hamkkejaram.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 초대 코드는 발급 후 72시간까지만 쓸 수 있다(SHR-02, TCH-01). */
class InviteCodeExpiryTest {
    private static final Instant ISSUED = Instant.parse("2026-11-01T00:00:00Z");
    private static final String CODE = "ABCD2345";
    private static final AuthUser TEACHER = new AuthUser(20L, Role.TEACHER);

    private final ChildRepository children = mock(ChildRepository.class);
    private final InviteCodeRepository codes = mock(InviteCodeRepository.class);
    private final TeacherLinkRepository links = mock(TeacherLinkRepository.class);

    private InviteCode invite() {
        return new InviteCode(7L, CODE, ISSUED, ISSUED.plus(ShareService.CODE_TTL));
    }

    private ShareService serviceAt(Instant now) {
        return new ShareService(mock(ChildAccess.class), children, mock(ShareSettingRepository.class), codes, links,
                mock(UserRepository.class), mock(WeeklySummaryService.class), mock(AiConsentService.class),
                Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    void ttlIsSeventyTwoHours() {
        assertThat(ShareService.CODE_TTL).hasHours(72);
    }

    @Test
    void isUsableUntilJustBeforeExpiry() {
        InviteCode c = invite();
        assertThat(c.isUsable(ISSUED)).isTrue();
        assertThat(c.isUsable(ISSUED.plus(ShareService.CODE_TTL).minusSeconds(1))).isTrue();
        assertThat(c.isUsable(ISSUED.plus(ShareService.CODE_TTL))).isFalse();
        assertThat(c.isUsable(ISSUED.plus(ShareService.CODE_TTL).plusSeconds(1))).isFalse();
    }

    @Test
    void usedCodeIsNotUsable() {
        InviteCode c = invite();
        c.markUsed(TEACHER.id(), ISSUED.plusSeconds(60));
        assertThat(c.isUsable(ISSUED.plusSeconds(120))).isFalse();
    }

    @Test
    void expiredCodeIsRejectedWhenLinking() {
        InviteCode c = invite();
        when(codes.findByCodeForUpdate(CODE)).thenReturn(Optional.of(c));
        ShareService service = serviceAt(ISSUED.plus(ShareService.CODE_TTL).plusSeconds(1));

        assertThatThrownBy(() -> service.link(TEACHER, CODE))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCode.INVITE_CODE_INVALID);
        verify(links, never()).save(any());
        assertThat(ReflectionTestUtils.getField(c, "usedAt")).isNull();
    }

    @Test
    void codeIsAcceptedJustBeforeExpiry() {
        InviteCode c = invite();
        Instant now = ISSUED.plus(ShareService.CODE_TTL).minusSeconds(1);
        when(codes.findByCodeForUpdate(CODE)).thenReturn(Optional.of(c));
        when(links.existsByTeacherIdAndChildId(TEACHER.id(), 7L)).thenReturn(false);
        when(links.save(any(TeacherLink.class))).thenAnswer(inv -> inv.getArgument(0));
        Child child = new Child(1L, "하늘", YearMonth.of(2023, 5), ISSUED);
        ReflectionTestUtils.setField(child, "id", 7L);
        when(children.findById(7L)).thenReturn(Optional.of(child));

        var res = serviceAt(now).link(TEACHER, CODE.substring(0, 4) + "-" + CODE.substring(4).toLowerCase());
        assertThat(res.id()).isEqualTo(7L);
        assertThat(ReflectionTestUtils.getField(c, "usedAt")).isEqualTo(now);
    }
}
