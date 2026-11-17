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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 단일 인스턴스용 메모리 기반 제한기.
 * - IP당 분당 요청 수: 용도별로 따로 센다(가입·로그인 / 토큰 갱신 / 비밀번호 확인 / 초대 코드 입력).
 *   한 통으로 세면 기관 Wi-Fi처럼 여러 사람이 한 IP로 나가는 곳에서 서로의 한도를 깎아 먹는다.
 * - 로그인: 이메일당 15분 안에 실패가 5번 쌓이면, 5번째 실패 시각부터 15분 동안 잠금(ACC-05)
 * - AI 분석 토큰: 사용자당 시간당 발급 수 제한
 * - 내 데이터 내려받기: 사용자당 시간당 3회
 * 서버를 여러 대로 늘리면 Redis 기반으로 바꾼다.
 */
@Component
public class RateLimiter {
    private static final Duration IP_WINDOW = Duration.ofMinutes(1);
    private static final Duration AI_SESSION_WINDOW = Duration.ofHours(1);
    private static final Duration EXPORT_WINDOW = Duration.ofHours(1);
    /** 내 데이터 내려받기(GET /me/export) 사용자당 시간당 횟수. */
    public static final int EXPORTS_PER_HOUR = 3;

    private final AppProperties.RateLimit props;
    private final Clock clock;
    private final Window<String> loginOrSignupByIp = new Window<>(IP_WINDOW);
    private final Window<String> tokenRefreshByIp = new Window<>(IP_WINDOW);
    private final Window<String> passwordCheckByIp = new Window<>(IP_WINDOW);
    private final Window<String> inviteCodeByIp = new Window<>(IP_WINDOW);
    private final Window<Long> aiSessionsByUser = new Window<>(AI_SESSION_WINDOW);
    private final Window<Long> exportsByUser = new Window<>(EXPORT_WINDOW);
    private final Map<String, Deque<Instant>> failuresByEmail = new ConcurrentHashMap<>();
    private final Map<String, Instant> lockedUntilByEmail = new ConcurrentHashMap<>();

    public RateLimiter(AppProperties appProperties, Clock clock) {
        this.props = appProperties.rateLimit();
        this.clock = clock;
    }

    /** 가입·로그인: IP당 분당 요청 수. 비밀번호 해시 계산과 여러 이메일에 걸친 대입을 늦춘다. */
    public void checkLoginOrSignup(String ip) {
        loginOrSignupByIp.hit(ip, props.authRequestsPerMinute(), clock.instant());
    }

    /** 토큰 갱신: IP당 분당 요청 수. 앱이 알아서 부르는 요청이라 가입·로그인보다 넉넉하다. */
    public void checkTokenRefresh(String ip) {
        tokenRefreshByIp.hit(ip, props.refreshRequestsPerMinute(), clock.instant());
    }

    /** 비밀번호 확인(비밀번호 변경·탈퇴): IP당 분당 요청 수. 틀린 횟수는 따로 로그인 실패로 센다. */
    public void checkPasswordCheck(String ip) {
        passwordCheckByIp.hit(ip, props.passwordRequestsPerMinute(), clock.instant());
    }

    /** 선생님의 초대 코드 입력: IP당 분당 요청 수(코드 대입 시도 제한). */
    public void checkInviteCodeAttempt(String ip) {
        inviteCodeByIp.hit(ip, props.inviteRequestsPerMinute(), clock.instant());
    }

    /** AI 분석 토큰 발급: 사용자당 1시간에 정해진 횟수까지(docs/ai-observation.md 7장). */
    public void checkAiSession(long userId) {
        aiSessionsByUser.hit(userId, props.aiSessionsPerHour(), clock.instant());
    }

    /** 내 데이터 내려받기: 사용자당 1시간에 3회까지. */
    public void checkExport(long userId) {
        exportsByUser.hit(userId, EXPORTS_PER_HOUR, clock.instant());
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
        for (Window<?> w : List.of(loginOrSignupByIp, tokenRefreshByIp, passwordCheckByIp, inviteCodeByIp,
                aiSessionsByUser, exportsByUser)) {
            w.cleanup(now);
        }
        failuresByEmail.entrySet().removeIf(e -> {
            synchronized (e.getValue()) {
                prune(e.getValue(), now.minus(props.loginLockDuration()));
                return e.getValue().isEmpty();
            }
        });
        lockedUntilByEmail.entrySet().removeIf(e -> !now.isBefore(e.getValue()));
    }

    private static void prune(Deque<Instant> q, Instant cutoff) {
        while (!q.isEmpty() && q.peekFirst().isBefore(cutoff)) q.pollFirst();
    }

    /**
     * 키(IP·사용자)마다 최근 window 동안의 요청 시각을 들고, 한도에 닿으면 429를 던진다.
     * 거절된 요청은 세지 않는다. 세기와 정리를 모두 키 단위로 묶어(compute) 정리 중에 들어온 요청이 빠지지 않는다.
     */
    private static final class Window<K> {
        private final Duration window;
        private final Map<K, Deque<Instant>> hits = new ConcurrentHashMap<>();

        Window(Duration window) {
            this.window = window;
        }

        void hit(K key, int limit, Instant now) {
            hits.compute(key, (k, q) -> {
                if (q == null) q = new ArrayDeque<>();
                prune(q, now.minus(window));
                if (q.size() >= limit) {
                    throw new ApiException(ErrorCode.TOO_MANY_ATTEMPTS);
                }
                q.addLast(now);
                return q;
            });
        }

        void cleanup(Instant now) {
            for (K key : hits.keySet()) {
                hits.computeIfPresent(key, (k, q) -> {
                    prune(q, now.minus(window));
                    return q.isEmpty() ? null : q;
                });
            }
        }
    }
}
