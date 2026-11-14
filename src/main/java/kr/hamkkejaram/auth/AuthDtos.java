package kr.hamkkejaram.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import kr.hamkkejaram.consent.ConsentType;
import kr.hamkkejaram.user.Role;
import kr.hamkkejaram.user.User;

import java.util.List;

public final class AuthDtos {
    private AuthDtos() {}

    /** 비밀번호 규칙(ACC-01). 가입과 비밀번호 변경이 같은 규칙을 쓴다. */
    static final String PASSWORD_PATTERN = "^(?=.*[A-Za-z])(?=.*\\d).+$";
    static final String PASSWORD_SIZE_MESSAGE = "비밀번호는 8~64자로 입력해 주세요.";
    static final String PASSWORD_PATTERN_MESSAGE = "영문과 숫자를 함께 넣어 주세요.";

    public record ConsentInput(@NotNull ConsentType type, @NotBlank @Size(max = 30) String version) {}

    public record SignupRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(min = 8, max = 64, message = PASSWORD_SIZE_MESSAGE)
            @Pattern(regexp = PASSWORD_PATTERN, message = PASSWORD_PATTERN_MESSAGE)
            String password,
            @NotBlank @Size(max = 30) String displayName,
            @NotNull Role role,
            @NotNull @Size(max = 10) List<@Valid ConsentInput> consents) {}

    public record LoginRequest(@NotBlank @Size(max = 254) String email, @NotBlank @Size(max = 64) String password) {}

    public record RefreshRequest(@NotBlank @Size(max = 200) String refreshToken) {}

    /** 비밀번호 변경. 새 비밀번호는 가입과 같은 규칙이며 지금 비밀번호와 달라야 한다. */
    public record ChangePasswordRequest(
            @NotBlank @Size(max = 64) String currentPassword,
            @NotBlank @Size(min = 8, max = 64, message = PASSWORD_SIZE_MESSAGE)
            @Pattern(regexp = PASSWORD_PATTERN, message = PASSWORD_PATTERN_MESSAGE)
            String newPassword) {}

    /**
     * 내 정보. pendingConsents: 동의 문서 버전이 바뀌어 다시 동의를 받아야 하는 항목
     * ({@code TERMS_OF_SERVICE}, {@code PRIVACY_POLICY} 중). 평소에는 빈 배열이다.
     */
    public record UserResponse(long id, String email, String displayName, Role role,
                               List<ConsentType> pendingConsents) {
        public static UserResponse from(User u, List<ConsentType> pendingConsents) {
            return new UserResponse(u.getId(), u.getEmail(), u.getDisplayName(), u.getRole(),
                    List.copyOf(pendingConsents));
        }
    }

    public record TokenResponse(String accessToken, long accessTokenExpiresIn, String refreshToken,
                                UserResponse user) {}
}
