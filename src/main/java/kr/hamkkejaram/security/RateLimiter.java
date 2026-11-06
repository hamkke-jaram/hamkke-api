package kr.hamkkejaram.security;

import kr.hamkkejaram.common.ApiException;
import kr.hamkkejaram.common.AppProperties;
import kr.hamkkejaram.common.ErrorCode;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 단일 인스턴스용 메모리 기반 제한기.
 * - 인증 API: IP당 분당 요청 수 제한
 * - 로그인: 이메일당 실패 횟수가 넘으면 일정 시간 잠금
 * 서버를 여러 대로 늘리면 Redis 기반으로 바꾼다.
 */
@Component
public class RateLimiter {
    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final AppProperties.RateLimit props;
    private final Clock clock;
    private final Map<String, Deque<Instant>> requestsByIp = new ConcurrentHashMap<>();
    private final Map<String, Deque<Instant>> failuresByEmail = new ConcurrentHashMap<>();

    public RateLimiter(AppProperties appProperties, Clock clock) {
        this.props = appProperties.rateLimit();
        this.clock = clock;
    }

    public void checkAuthRequest(String ip) {
        Instant now = clock.instant();
        Deque<Instant> q = requestsByIp.computeIfAbsent(ip, k -> new ArrayDeque<>());
        synchronized (q) {
            prune(q, now.minus(WINDOW));
            if (q.size() >= props.authRequestsPerMinute()) {
                throw new ApiException(ErrorCode.TOO_MANY_ATTEMPTS);
            }
            q.addLast(now);
        }
    }

    public void checkLoginAllowed(String email) {
        Deque<Instant> q = failuresByEmail.get(email);
        if (q == null) return;
        synchronized (q) {
            prune(q, clock.instant().minus(props.loginLockDuration()));
            if (q.size() >= props.loginFailuresBeforeLock()) {
                throw new ApiException(ErrorCode.TOO_MANY_ATTEMPTS);
            }
        }
    }

    public void recordLoginFailure(String email) {
        Deque<Instant> q = failuresByEmail.computeIfAbsent(email, k -> new ArrayDeque<>());
        synchronized (q) {
            q.addLast(clock.instant());
        }
    }

    public void recordLoginSuccess(String email) {
        failuresByEmail.remove(email);
    }

    @Scheduled(fixedDelay = 300_000)
    void cleanup() {
        Instant now = clock.instant();
        requestsByIp.entrySet().removeIf(e -> {
            synchronized (e.getValue()) {
                prune(e.getValue(), now.minus(WINDOW));
                return e.getValue().isEmpty();
            }
        });
        failuresByEmail.entrySet().removeIf(e -> {
            synchronized (e.getValue()) {
                prune(e.getValue(), now.minus(props.loginLockDuration()));
                return e.getValue().isEmpty();
            }
        });
    }

    private static void prune(Deque<Instant> q, Instant cutoff) {
        while (!q.isEmpty() && q.peekFirst().isBefore(cutoff)) q.pollFirst();
    }
}
