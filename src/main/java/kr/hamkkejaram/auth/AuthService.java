package kr.hamkkejaram.auth;

import kr.hamkkejaram.auth.AuthDtos.ConsentInput;
import kr.hamkkejaram.auth.AuthDtos.LoginRequest;
import kr.hamkkejaram.auth.AuthDtos.SignupRequest;
import kr.hamkkejaram.auth.AuthDtos.TokenResponse;
import kr.hamkkejaram.auth.AuthDtos.UserResponse;
import kr.hamkkejaram.common.ApiException;
import kr.hamkkejaram.common.AppProperties;
import kr.hamkkejaram.common.ErrorCode;
import kr.hamkkejaram.consent.Consent;
import kr.hamkkejaram.consent.ConsentRepository;
import kr.hamkkejaram.consent.ConsentType;
import kr.hamkkejaram.security.JwtService;
import kr.hamkkejaram.security.RateLimiter;
import kr.hamkkejaram.user.User;
import kr.hamkkejaram.user.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class AuthService {
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository users;
    private final ConsentRepository consents;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RateLimiter rateLimiter;
    private final AppProperties props;
    private final Clock clock;
    private final String dummyHash;

    public AuthService(UserRepository users, ConsentRepository consents, RefreshTokenRepository refreshTokens,
                       PasswordEncoder passwordEncoder, JwtService jwtService, RateLimiter rateLimiter,
                       AppProperties props, Clock clock) {
        this.users = users;
        this.consents = consents;
        this.refreshTokens = refreshTokens;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.rateLimiter = rateLimiter;
        this.props = props;
        this.clock = clock;
        // 없는 이메일로 로그인할 때도 같은 시간이 걸리게 해서 가입 여부가 드러나지 않게 한다.
        this.dummyHash = passwordEncoder.encode("timing-equalizer-0");
    }

    public static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    @Transactional
    public TokenResponse signup(SignupRequest req) {
        Map<ConsentType, String> given = req.consents().stream()
                .collect(Collectors.toMap(ConsentInput::type, ConsentInput::version, (a, b) -> b));
        AppProperties.Policy policy = props.policy();
        if (!policy.termsVersion().equals(given.get(ConsentType.TERMS_OF_SERVICE))
                || !policy.privacyVersion().equals(given.get(ConsentType.PRIVACY_POLICY))) {
            throw new ApiException(ErrorCode.CONSENT_REQUIRED);
        }
        String email = normalizeEmail(req.email());
        if (users.existsByEmail(email)) {
            throw new ApiException(ErrorCode.EMAIL_TAKEN);
        }
        Instant now = clock.instant();
        User user;
        try {
            user = users.saveAndFlush(new User(email, passwordEncoder.encode(req.password()),
                    req.displayName().trim(), req.role(), now));
        } catch (DataIntegrityViolationException e) {
            throw new ApiException(ErrorCode.EMAIL_TAKEN);
        }
        consents.save(new Consent(user.getId(), null, ConsentType.TERMS_OF_SERVICE, policy.termsVersion(), now));
        consents.save(new Consent(user.getId(), null, ConsentType.PRIVACY_POLICY, policy.privacyVersion(), now));
        return issueTokens(user);
    }

    @Transactional(noRollbackFor = ApiException.class)
    public TokenResponse login(LoginRequest req) {
        String email = normalizeEmail(req.email());
        rateLimiter.checkLoginAllowed(email);
        User user = users.findByEmail(email).orElse(null);
        boolean ok = passwordEncoder.matches(req.password(), user != null ? user.getPasswordHash() : dummyHash);
        if (user == null || !ok) {
            rateLimiter.recordLoginFailure(email);
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS);
        }
        rateLimiter.recordLoginSuccess(email);
        return issueTokens(user);
    }

    /** 회전 방식. 이미 폐기된 토큰이 다시 오면 탈취로 보고 그 사용자의 토큰을 모두 폐기한다. */
    @Transactional(noRollbackFor = ApiException.class)
    public TokenResponse refresh(String rawToken) {
        Instant now = clock.instant();
        RefreshToken token = refreshTokens.findByTokenHash(hash(rawToken))
                .orElseThrow(() -> new ApiException(ErrorCode.INVALID_REFRESH_TOKEN));
        if (token.getRevokedAt() != null) {
            refreshTokens.revokeAllForUser(token.getUserId(), now);
            throw new ApiException(ErrorCode.INVALID_REFRESH_TOKEN);
        }
        if (!token.isUsable(now)) {
            throw new ApiException(ErrorCode.INVALID_REFRESH_TOKEN);
        }
        token.revoke(now);
        User user = users.findById(token.getUserId())
                .orElseThrow(() -> new ApiException(ErrorCode.INVALID_REFRESH_TOKEN));
        return issueTokens(user);
    }

    @Transactional
    public void logout(String rawToken) {
        refreshTokens.findByTokenHash(hash(rawToken)).ifPresent(t -> t.revoke(clock.instant()));
    }

    /** 호출하는 쪽 트랜잭션 안에서만 쓴다. */
    private TokenResponse issueTokens(User user) {
        Instant now = clock.instant();
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        refreshTokens.save(new RefreshToken(user.getId(), hash(raw), now, now.plus(props.jwt().refreshTtl())));
        String access = jwtService.issueAccessToken(user.getId(), user.getRole());
        return new TokenResponse(access, jwtService.accessTtlSeconds(), raw, UserResponse.from(user));
    }

    @Scheduled(cron = "0 17 4 * * *")
    @Transactional
    public void purgeExpiredRefreshTokens() {
        refreshTokens.deleteExpiredBefore(clock.instant());
    }

    static String hash(String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
