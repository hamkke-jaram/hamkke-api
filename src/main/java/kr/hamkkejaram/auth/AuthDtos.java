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

    public record ConsentInput(@NotNull ConsentType type, @NotBlank @Size(max = 30) String version) {}

    public record SignupRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(min = 8, max = 64, message = "비밀번호는 8~64자로 입력해 주세요.")
            @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).+$", message = "영문과 숫자를 함께 넣어 주세요.")
            String password,
            @NotBlank @Size(max = 30) String displayName,
            @NotNull Role role,
            @NotNull @Size(max = 10) List<@Valid ConsentInput> consents) {}

    public record LoginRequest(@NotBlank @Size(max = 254) String email, @NotBlank @Size(max = 64) String password) {}

    public record RefreshRequest(@NotBlank @Size(max = 200) String refreshToken) {}

    public record UserResponse(long id, String email, String displayName, Role role) {
        public static UserResponse from(User u) {
            return new UserResponse(u.getId(), u.getEmail(), u.getDisplayName(), u.getRole());
        }
    }

    public record TokenResponse(String accessToken, long accessTokenExpiresIn, String refreshToken,
                                UserResponse user) {}
}
