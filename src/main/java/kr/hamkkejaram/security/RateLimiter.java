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
 * - 로그인: 이메일당 15분 안에 실패가 5번 쌓이면, 5번째 실패 시각부터 15분 동안 잠금(ACC-05)
 * - AI 분석 토큰: 사용자당 시간당 발급 수 제한
 * - 내 데이터 내려받기: 사용자당 시간당 3회
 * 서버를 여러 대로 늘리면 Redis 기반으로 바꾼다.
 */
@Component
public class RateLimiter {
    private static final Duration WINDOW = Duration.ofMinutes(1);
    private static final Duration AI_SESSION_WINDOW = Duration.ofHours(1);
    private static final Duration EXPORT_WINDOW = Duration.ofHours(1);
    /** 내 데이터 내려받기(GET /me/export) 사용자당 시간당 횟수. */
    public static final int EXPORTS_PER_HOUR = 3;

    private final AppProperties.RateLimit props;
    private final Clock clock;
    private final Map<String, Deque<Instant>> requestsByIp = new ConcurrentHashMap<>();
    private final Map<String, Deque<Instant>> failuresByEmail = new ConcurrentHashMap<>();
    private final Map<String, Instant> lockedUntilByEmail = new ConcurrentHashMap<>();
    private final Map<Long, Deque<Instant>> aiSessionsByUser = new ConcurrentHashMap<>();
    private final Map<Long, Deque<Instant>> exportsByUser = new ConcurrentHashMap<>();

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

    /** AI 분석 토큰 발급: 사용자당 1시간에 정해진 횟수까지(docs/ai-observation.md 7장). */
    public void checkAiSession(long userId) {
        Instant now = clock.instant();
        Deque<Instant> q = aiSessionsByUser.computeIfAbsent(userId, k -> new ArrayDeque<>());
        synchronized (q) {
            prune(q, now.minus(AI_SESSION_WINDOW));
            if (q.size() >= props.aiSessionsPerHour()) {
                throw new ApiException(ErrorCode.TOO_MANY_ATTEMPTS);
            }
            q.addLast(now);
        }
    }

    /** 내 데이터 내려받기: 사용자당 1시간에 3회까지. */
    public void checkExport(long userId) {
        Instant now = clock.instant();
        Deque<Instant> q = exportsByUser.computeIfAbsent(userId, k -> new ArrayDeque<>());
        synchronized (q) {
            prune(q, now.minus(EXPORT_WINDOW));
            if (q.size() >= EXPORTS_PER_HOUR) {
                throw new ApiException(ErrorCode.TOO_MANY_ATTEMPTS);
            }
            q.addLast(now);
        }
    }

    /** 잠겨 있으면(잠금이 걸린 시각부터 loginLockDuration이 지나기 전) 429. */
    public void checkLoginAllowed(String email) {
        Instant until = lockedUntilByEmail.get(email);
        if (until == null) return;
        if (clock.instant().isBefore(until)) {
            throw new ApiException(ErrorCode.TOO_MANY_ATTEMPTS);
        }
        lockedUntilByEmail.remove(email, until);
    }

    /**
     * 실패를 남긴다. 최근 loginLockDuration 안의 실패가 loginFailuresBeforeLock번이 되면
     * 지금부터 loginLockDuration 동안 잠그고 실패 기록은 비운다(잠금이 풀린 뒤에는 처음부터 다시 센다).
     */
    public void recordLoginFailure(String email) {
        Instant now = clock.instant();
        Deque<Instant> q = failuresByEmail.computeIfAbsent(email, k -> new ArrayDeque<>());
        synchronized (q) {
            prune(q, now.minus(props.loginLockDuration()));
            q.addLast(now);
            if (q.size() >= props.loginFailuresBeforeLock()) {
                lockedUntilByEmail.put(email, now.plus(props.loginLockDuration()));
                q.clear();
            }
        }
    }

    public void recordLoginSuccess(String email) {
        failuresByEmail.remove(email);
        lockedUntilByEmail.remove(email);
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
        lockedUntilByEmail.entrySet().removeIf(e -> !now.isBefore(e.getValue()));
        aiSessionsByUser.entrySet().removeIf(e -> {
            synchronized (e.getValue()) {
                prune(e.getValue(), now.minus(AI_SESSION_WINDOW));
                return e.getValue().isEmpty();
            }
        });
        exportsByUser.entrySet().removeIf(e -> {
            synchronized (e.getValue()) {
                prune(e.getValue(), now.minus(EXPORT_WINDOW));
                return e.getValue().isEmpty();
            }
        });
    }

    private static void prune(Deque<Instant> q, Instant cutoff) {
        while (!q.isEmpty() && q.peekFirst().isBefore(cutoff)) q.pollFirst();
    }
}
