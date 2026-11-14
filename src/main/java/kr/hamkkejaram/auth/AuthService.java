package kr.hamkkejaram.auth;

import kr.hamkkejaram.auth.AuthDtos.ChangePasswordRequest;
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
import kr.hamkkejaram.consent.ConsentService;
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
    private final ConsentService consentService;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RateLimiter rateLimiter;
    private final AppProperties props;
    private final Clock clock;
    private final String dummyHash;

    public AuthService(UserRepository users, ConsentRepository consents, ConsentService consentService,
                       RefreshTokenRepository refreshTokens, PasswordEncoder passwordEncoder, JwtService jwtService,
                       RateLimiter rateLimiter, AppProperties props, Clock clock) {
        this.users = users;
        this.consents = consents;
        this.consentService = consentService;
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
        requireHashablePassword(req.password());
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

    /**
     * 비밀번호 변경(ACC-09). 지금 비밀번호가 맞아야 하고(아니면 401 INVALID_CREDENTIALS), 새 비밀번호는 달라야 한다.
     * 지금 비밀번호를 거듭 틀리면 로그인과 같은 잠금(이메일 기준 5회 → 15분)이 걸린다.
     * 바꾸면 이 사용자의 리프레시 토큰을 모두 지우고(다른 기기는 액세스 토큰이 만료되면 다시 로그인) 새 토큰 묶음을 준다.
     * 폐기 표시 대신 지우는 까닭: 다른 기기가 예전 토큰으로 회전을 시도할 때 재사용 탐지(refresh)가 새 토큰까지 폐기하지 않게.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public TokenResponse changePassword(long userId, ChangePasswordRequest req) {
        User user = users.findById(userId).orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED));
        rateLimiter.checkLoginAllowed(user.getEmail());
        if (!passwordEncoder.matches(req.currentPassword(), user.getPasswordHash())) {
            rateLimiter.recordLoginFailure(user.getEmail());
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS, "지금 쓰는 비밀번호가 맞지 않아요.");
        }
        if (req.currentPassword().equals(req.newPassword())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "지금 쓰는 비밀번호와 다른 비밀번호를 입력해 주세요.");
        }
        requireHashablePassword(req.newPassword());
        user.changePasswordHash(passwordEncoder.encode(req.newPassword()));
        refreshTokens.deleteAllForUser(user.getId());
        return issueTokens(user);
    }

    /**
     * BCrypt는 72바이트까지만 받는다(넘으면 encode가 IllegalArgumentException → 500).
     * 64자 규칙 안이라도 한글처럼 여러 바이트인 글자가 많으면 넘을 수 있어 미리 400으로 돌려준다.
     */
    static final int BCRYPT_MAX_BYTES = 72;

    private static void requireHashablePassword(String raw) {
        if (raw.getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_BYTES) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "비밀번호가 너무 길어요. 한글을 줄이거나 더 짧게 입력해 주세요.");
        }
    }

    /** 호출하는 쪽 트랜잭션 안에서만 쓴다. */
    private TokenResponse issueTokens(User user) {
        Instant now = clock.instant();
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        refreshTokens.save(new RefreshToken(user.getId(), hash(raw), now, now.plus(props.jwt().refreshTtl())));
        String access = jwtService.issueAccessToken(user.getId(), user.getRole());
        return new TokenResponse(access, jwtService.accessTtlSeconds(), raw,
                UserResponse.from(user, consentService.pendingConsents(user.getId())));
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
