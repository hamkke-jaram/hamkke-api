package kr.hamkkejaram.user;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import kr.hamkkejaram.auth.AuthDtos.ChangePasswordRequest;
import kr.hamkkejaram.auth.AuthDtos.TokenResponse;
import kr.hamkkejaram.auth.AuthDtos.UserResponse;
import kr.hamkkejaram.auth.AuthService;
import kr.hamkkejaram.child.ChildRepository;
import kr.hamkkejaram.common.ApiException;
import kr.hamkkejaram.common.ErrorCode;
import kr.hamkkejaram.consent.Consent;
import kr.hamkkejaram.consent.ConsentRepository;
import kr.hamkkejaram.consent.ConsentService;
import kr.hamkkejaram.consent.ConsentType;
import kr.hamkkejaram.security.AuthUser;
import kr.hamkkejaram.security.RateLimiter;
import kr.hamkkejaram.user.DataExportDtos.DataExport;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/v1/me")
public class MeController {
    static final String EXPORT_FILENAME = "hamkke-jaram-export.json";

    private final UserRepository users;
    private final ChildRepository children;
    private final ConsentRepository consents;
    private final ConsentService consentService;
    private final AuthService authService;
    private final DataExportService exportService;
    private final RateLimiter rateLimiter;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    public MeController(UserRepository users, ChildRepository children, ConsentRepository consents,
                        ConsentService consentService, AuthService authService, DataExportService exportService,
                        RateLimiter rateLimiter, PasswordEncoder passwordEncoder, Clock clock) {
        this.users = users;
        this.children = children;
        this.consents = consents;
        this.consentService = consentService;
        this.authService = authService;
        this.exportService = exportService;
        this.rateLimiter = rateLimiter;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    public record DeleteAccountRequest(@NotBlank @Size(max = 64) String password) {}

    /** 표시명은 앞뒤 공백을 지운 뒤 1~30자여야 한다. 길이 상한은 공백을 지운 값으로 다시 확인한다(가입과 같은 규칙). */
    public record UpdateMeRequest(
            @NotBlank(message = DISPLAY_NAME_MESSAGE) @Size(max = 64, message = DISPLAY_NAME_MESSAGE)
            String displayName) {}

    static final String DISPLAY_NAME_MESSAGE = AuthService.DISPLAY_NAME_MESSAGE;

    public record ConsentRequest(@NotNull ConsentType type, @NotBlank @Size(max = 30) String version) {}

    public record ConsentResponse(ConsentType type, String version, Long childId, Instant agreedAt,
                                  Instant revokedAt) {
        static ConsentResponse from(Consent c) {
            return new ConsentResponse(c.getType(), c.getVersion(), c.getChildId(), c.getAgreedAt(), c.getRevokedAt());
        }
    }

    @GetMapping
    @Transactional(readOnly = true)
    public UserResponse me(@AuthenticationPrincipal AuthUser user) {
        return response(currentUser(user));
    }

    /** 표시명 수정(ACC-08). 선생님의 표시명은 목표·메모·AI 관찰 기록에 작성자 이름으로 보인다. */
    @PatchMapping
    @Transactional
    public UserResponse update(@AuthenticationPrincipal AuthUser user, @Valid @RequestBody UpdateMeRequest req) {
        String name = AuthService.normalizeDisplayName(req.displayName());
        User u = currentUser(user);
        u.changeDisplayName(name);
        return response(u);
    }

    /** 비밀번호 변경(ACC-09) → 새 토큰 묶음. 다른 기기의 리프레시 토큰은 모두 폐기된다. */
    @PutMapping("/password")
    public TokenResponse changePassword(@AuthenticationPrincipal AuthUser user,
                                        @Valid @RequestBody ChangePasswordRequest req, HttpServletRequest http) {
        rateLimiter.checkPasswordCheck(http.getRemoteAddr());
        return authService.changePassword(user.id(), req);
    }

    @GetMapping("/consents")
    @Transactional(readOnly = true)
    public List<ConsentResponse> consents(@AuthenticationPrincipal AuthUser user) {
        return consents.findByUserIdOrderByAgreedAtDesc(user.id()).stream().map(ConsentResponse::from).toList();
    }

    /** 바뀐 이용약관·개인정보 처리방침에 다시 동의(ACC-10). version은 GET /policies의 현재 버전이어야 한다. */
    @PostMapping("/consents")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void agree(@AuthenticationPrincipal AuthUser user, @Valid @RequestBody ConsentRequest req) {
        consentService.agree(user.id(), req.type(), req.version());
    }

    /** 내 데이터 내려받기(ACC-11). 사용자당 시간당 3회. 내용은 로그에 남기지 않는다. */
    @GetMapping("/export")
    public ResponseEntity<DataExport> export(@AuthenticationPrincipal AuthUser user) {
        rateLimiter.checkExport(user.id());
        DataExport body = exportService.export(user.id());
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(EXPORT_FILENAME).build().toString())
                .cacheControl(CacheControl.noStore())
                .body(body);
    }

    /**
     * 회원 탈퇴. 계정과 관련 데이터를 즉시 지운다(아동·지표·카드 이력·메모 등은 DB CASCADE).
     * 동의·철회 이력은 user_id/child_id가 비워진 채 남고, 보관 기간이 지나면 지운다(ConsentService, ACC-12).
     */
    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void delete(@AuthenticationPrincipal AuthUser user, @Valid @RequestBody DeleteAccountRequest req,
                       HttpServletRequest http) {
        // 비밀번호를 확인하는 곳이라 비밀번호 변경과 같은 제한을 건다(ACC-06): IP당 요청 수(비밀번호 변경과 한 통),
        // 그리고 틀린 횟수는 로그인 실패로 세어 5번이면 15분 잠근다. 아니면 액세스 토큰만 있으면 비밀번호를 끝없이 맞춰 볼 수 있다.
        rateLimiter.checkPasswordCheck(http.getRemoteAddr());
        User u = currentUser(user);
        rateLimiter.checkLoginAllowed(u.getEmail());
        if (!passwordEncoder.matches(req.password(), u.getPasswordHash())) {
            rateLimiter.recordLoginFailure(u.getEmail());
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS, "비밀번호가 맞지 않아요.");
        }
        Instant now = clock.instant();
        consents.findByUserIdOrderByAgreedAtDesc(u.getId()).forEach(c -> c.revoke(now));
        consents.flush();
        if (u.getRole() == Role.PARENT) {
            children.deleteAll(children.findByParentIdOrderByIdAsc(u.getId()));
            children.flush();
        }
        users.delete(u);
    }

    private User currentUser(AuthUser user) {
        return users.findById(user.id()).orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED));
    }

    private UserResponse response(User u) {
        return UserResponse.from(u, consentService.pendingConsents(u.getId()));
    }
}
