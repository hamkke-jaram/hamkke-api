package kr.hamkkejaram.user;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import kr.hamkkejaram.auth.AuthDtos.UserResponse;
import kr.hamkkejaram.child.ChildRepository;
import kr.hamkkejaram.common.ApiException;
import kr.hamkkejaram.common.ErrorCode;
import kr.hamkkejaram.consent.Consent;
import kr.hamkkejaram.consent.ConsentRepository;
import kr.hamkkejaram.consent.ConsentType;
import kr.hamkkejaram.security.AuthUser;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
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
    private final UserRepository users;
    private final ChildRepository children;
    private final ConsentRepository consents;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    public MeController(UserRepository users, ChildRepository children, ConsentRepository consents,
                        PasswordEncoder passwordEncoder, Clock clock) {
        this.users = users;
        this.children = children;
        this.consents = consents;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    public record DeleteAccountRequest(@NotBlank @Size(max = 64) String password) {}

    public record ConsentResponse(ConsentType type, String version, Long childId, Instant agreedAt,
                                  Instant revokedAt) {
        static ConsentResponse from(Consent c) {
            return new ConsentResponse(c.getType(), c.getVersion(), c.getChildId(), c.getAgreedAt(), c.getRevokedAt());
        }
    }

    @GetMapping
    @Transactional(readOnly = true)
    public UserResponse me(@AuthenticationPrincipal AuthUser user) {
        return UserResponse.from(users.findById(user.id()).orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED)));
    }

    @GetMapping("/consents")
    @Transactional(readOnly = true)
    public List<ConsentResponse> consents(@AuthenticationPrincipal AuthUser user) {
        return consents.findByUserIdOrderByAgreedAtDesc(user.id()).stream().map(ConsentResponse::from).toList();
    }

    /**
     * 회원 탈퇴. 계정과 관련 데이터를 즉시 지운다(아동·지표·카드 이력·메모 등은 DB CASCADE).
     * 동의·철회 이력은 user_id/child_id가 비워진 채 남는다.
     */
    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void delete(@AuthenticationPrincipal AuthUser user, @Valid @RequestBody DeleteAccountRequest req) {
        User u = users.findById(user.id()).orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED));
        if (!passwordEncoder.matches(req.password(), u.getPasswordHash())) {
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
}
