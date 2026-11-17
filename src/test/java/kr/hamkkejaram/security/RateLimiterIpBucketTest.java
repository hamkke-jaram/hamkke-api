package kr.hamkkejaram.security;

import kr.hamkkejaram.common.ApiException;
import kr.hamkkejaram.common.AppProperties;
import kr.hamkkejaram.common.ErrorCode;
import kr.hamkkejaram.security.RateLimiterLoginLockTest.MutableClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * IP당 분당 요청 한도는 용도별로 따로 센다: 가입·로그인 / 토큰 갱신 / 비밀번호 확인 / 초대 코드 입력.
 * 한 용도의 한도를 다 써도 같은 IP의 다른 용도는 막히지 않는다.
 */
class RateLimiterIpBucketTest {
    private static final Instant T0 = Instant.parse("2026-11-17T00:00:00Z");
    private static final String IP = "203.0.113.7";
    private static final int LOGIN = 4;
    private static final int REFRESH = 6;
    private static final int PASSWORD = 2;
    private static final int INVITE = 3;

    private final MutableClock clock = new MutableClock(T0);
    private final RateLimiter limiter = new RateLimiter(new AppProperties(
            new AppProperties.Jwt("x".repeat(32), "hamkke-jaram", Duration.ofMinutes(30), Duration.ofDays(14)),
            new AppProperties.Policy("v", "v", "v", "v"),
            List.of(),
            new AppProperties.RateLimit(LOGIN, REFRESH, PASSWORD, INVITE, 5, Duration.ofMinutes(15), 20),
            new AppProperties.Ai(false, null, 6, Duration.ofMinutes(10))), clock);

    private void use(Consumer<String> check, String ip, int times) {
        for (int i = 0; i < times; i++) check.accept(ip);
    }

    private static void assertLimited(Consumer<String> check, String ip) {
        assertThatThrownBy(() -> check.accept(ip))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.TOO_MANY_ATTEMPTS));
    }

    private static void assertOpen(Consumer<String> check, String ip) {
        assertThatCode(() -> check.accept(ip)).doesNotThrowAnyException();
    }

    @Test
    void eachPurposeHasItsOwnLimit() {
        use(limiter::checkLoginOrSignup, IP, LOGIN);
        assertLimited(limiter::checkLoginOrSignup, IP);

        use(limiter::checkTokenRefresh, IP, REFRESH);
        assertLimited(limiter::checkTokenRefresh, IP);

        use(limiter::checkPasswordCheck, IP, PASSWORD);
        assertLimited(limiter::checkPasswordCheck, IP);

        use(limiter::checkInviteCodeAttempt, IP, INVITE);
        assertLimited(limiter::checkInviteCodeAttempt, IP);
    }

    @Test
    void usingUpOnePurposeLeavesTheOthersOpen() {
        use(limiter::checkLoginOrSignup, IP, LOGIN);
        assertLimited(limiter::checkLoginOrSignup, IP);

        // 같은 IP의 다른 용도는 처음부터 센다
        assertOpen(limiter::checkTokenRefresh, IP);
        assertOpen(limiter::checkPasswordCheck, IP);
        assertOpen(limiter::checkInviteCodeAttempt, IP);
    }

    @Test
    void manyRefreshesDoNotEatIntoLoginLimit() {
        // 예전에는 토큰 갱신이 로그인과 한 통이라, 한 IP의 여러 기기가 갱신만 해도 로그인이 막혔다
        use(limiter::checkTokenRefresh, IP, REFRESH);
        use(limiter::checkLoginOrSignup, IP, LOGIN);
        assertLimited(limiter::checkLoginOrSignup, IP);
    }

    @Test
    void limitsAreCountedPerIp() {
        use(limiter::checkPasswordCheck, IP, PASSWORD);
        assertLimited(limiter::checkPasswordCheck, IP);
        assertOpen(limiter::checkPasswordCheck, "203.0.113.8");
    }

    @Test
    void windowSlidesAfterOneMinuteAndRejectedRequestsAreNotCounted() {
        use(limiter::checkInviteCodeAttempt, IP, INVITE); // 0초에 한도를 다 씀
        clock.set(T0.plusSeconds(30));
        assertLimited(limiter::checkInviteCodeAttempt, IP);
        assertLimited(limiter::checkInviteCodeAttempt, IP); // 거절된 요청은 세지 않는다

        clock.set(T0.plusSeconds(59));
        assertLimited(limiter::checkInviteCodeAttempt, IP);
        clock.set(T0.plusSeconds(61)); // 0초의 요청들이 창 밖으로 나감
        use(limiter::checkInviteCodeAttempt, IP, INVITE);
        assertLimited(limiter::checkInviteCodeAttempt, IP);
    }

    @Test
    void cleanupKeepsRecentRequestsAndDropsOldOnes() {
        use(limiter::checkPasswordCheck, IP, PASSWORD);
        limiter.cleanup(); // 아직 창 안: 지워지면 안 된다
        assertLimited(limiter::checkPasswordCheck, IP);

        clock.set(T0.plusSeconds(61));
        limiter.cleanup();
        use(limiter::checkPasswordCheck, IP, PASSWORD);
        assertLimited(limiter::checkPasswordCheck, IP);
    }

    @Test
    void olderFourArgumentShapeFillsDefaultLimits() {
        var old = new AppProperties.RateLimit(20, 5, Duration.ofMinutes(15), 20);
        assertThat(old.authRequestsPerMinute()).isEqualTo(20);
        assertThat(old.refreshRequestsPerMinute())
                .isEqualTo(AppProperties.RateLimit.DEFAULT_REFRESH_REQUESTS_PER_MINUTE);
        assertThat(old.passwordRequestsPerMinute())
                .isEqualTo(AppProperties.RateLimit.DEFAULT_PASSWORD_REQUESTS_PER_MINUTE);
        assertThat(old.inviteRequestsPerMinute())
                .isEqualTo(AppProperties.RateLimit.DEFAULT_INVITE_REQUESTS_PER_MINUTE);
        assertThat(old.loginFailuresBeforeLock()).isEqualTo(5);
        assertThat(old.aiSessionsPerHour()).isEqualTo(20);
    }
}
