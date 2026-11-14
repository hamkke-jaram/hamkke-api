package kr.hamkkejaram.security;

import kr.hamkkejaram.common.ApiException;
import kr.hamkkejaram.common.AppProperties;
import kr.hamkkejaram.common.ErrorCode;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ACC-05: 15분 안에 5번 틀리면, 5번째 실패 시각부터 15분 동안 로그인할 수 없다. */
class RateLimiterLoginLockTest {
    private static final Instant T0 = Instant.parse("2026-11-14T00:00:00Z");
    private static final String EMAIL = "lock@example.com";

    /** 테스트가 시각을 옮길 수 있는 시계. */
    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) { this.now = start; }

        void set(Instant t) { now = t; }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }

        @Override public Clock withZone(ZoneId zone) { return this; }

        @Override public Instant instant() { return now; }
    }

    private final MutableClock clock = new MutableClock(T0);

    private RateLimiter limiter() {
        AppProperties props = new AppProperties(
                new AppProperties.Jwt("x".repeat(32), "hamkke-jaram", Duration.ofMinutes(30), Duration.ofDays(14)),
                new AppProperties.Policy("v", "v", "v", "v"),
                List.of(),
                new AppProperties.RateLimit(20, 5, Duration.ofMinutes(15), 20),
                new AppProperties.Ai(false, null, 6, Duration.ofMinutes(10)));
        return new RateLimiter(props, clock);
    }

    private void failAt(RateLimiter l, int minute) {
        clock.set(T0.plus(Duration.ofMinutes(minute)));
        l.checkLoginAllowed(EMAIL);
        l.recordLoginFailure(EMAIL);
    }

    private void assertLockedAt(RateLimiter l, Duration at) {
        clock.set(T0.plus(at));
        assertThatThrownBy(() -> l.checkLoginAllowed(EMAIL))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.TOO_MANY_ATTEMPTS));
    }

    private void assertOpenAt(RateLimiter l, Duration at) {
        clock.set(T0.plus(at));
        assertThatCode(() -> l.checkLoginAllowed(EMAIL)).doesNotThrowAnyException();
    }

    @Test
    void lockLastsFifteenMinutesFromFifthFailureEvenWhenEarlyFailuresAgeOut() {
        RateLimiter l = limiter();
        for (int m : new int[]{0, 1, 2, 3}) failAt(l, m);
        assertOpenAt(l, Duration.ofMinutes(10));
        failAt(l, 14); // 5번째 실패 → 14분부터 29분까지 잠금
        assertLockedAt(l, Duration.ofMinutes(14));
        assertLockedAt(l, Duration.ofMinutes(16)); // 예전 방식이면 0분 실패가 빠져 여기서 풀렸다
        assertLockedAt(l, Duration.ofMinutes(28));
        assertLockedAt(l, Duration.ofMinutes(29).minusSeconds(1));
        assertOpenAt(l, Duration.ofMinutes(29));
    }

    @Test
    void failuresOlderThanWindowDoNotCount() {
        RateLimiter l = limiter();
        for (int m : new int[]{0, 1, 2, 3}) failAt(l, m);
        failAt(l, 20); // 0~3분 실패는 15분 창 밖 → 1번째로 센다
        assertOpenAt(l, Duration.ofMinutes(21));
    }

    @Test
    void afterLockExpiresCountingStartsOver() {
        RateLimiter l = limiter();
        for (int m : new int[]{0, 1, 2, 3, 4}) failAt(l, m);
        assertLockedAt(l, Duration.ofMinutes(18));
        assertOpenAt(l, Duration.ofMinutes(19));
        failAt(l, 19); // 잠금 뒤 첫 실패는 1번째
        assertOpenAt(l, Duration.ofMinutes(20));
    }

    @Test
    void successClearsFailuresAndLock() {
        RateLimiter l = limiter();
        for (int m : new int[]{0, 1, 2, 3}) failAt(l, m);
        l.recordLoginSuccess(EMAIL);
        failAt(l, 5);
        assertOpenAt(l, Duration.ofMinutes(6));
    }

    @Test
    void exportIsLimitedToThreePerHour() {
        RateLimiter l = limiter();
        for (int i = 0; i < RateLimiter.EXPORTS_PER_HOUR; i++) l.checkExport(1L);
        assertThatThrownBy(() -> l.checkExport(1L)).isInstanceOf(ApiException.class);
        assertThatCode(() -> l.checkExport(2L)).doesNotThrowAnyException();
        clock.set(T0.plus(Duration.ofHours(1)).plusSeconds(1));
        assertThatCode(() -> l.checkExport(1L)).doesNotThrowAnyException();
    }
}
