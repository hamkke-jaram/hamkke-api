package kr.hamkkejaram.child;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;

public final class ChildDtos {
    private ChildDtos() {}

    static final String YEAR_MONTH = "^\\d{4}-(0[1-9]|1[0-2])$";

    public record GuardianConsentInput(
            @NotNull @AssertTrue(message = "법정대리인 동의가 필요해요.") Boolean agreed,
            @NotBlank @Size(max = 30) String version) {}

    public record CreateChildRequest(
            @NotBlank @Size(max = 20) String nickname,
            @NotBlank @Pattern(regexp = YEAR_MONTH, message = "생년월은 YYYY-MM 형식이에요.") String birthYearMonth,
            @Valid GuardianConsentInput guardianConsent) {}

    public record UpdateChildRequest(
            @Size(min = 1, max = 20) String nickname,
            @Pattern(regexp = YEAR_MONTH, message = "생년월은 YYYY-MM 형식이에요.") String birthYearMonth) {}

    public record ChildResponse(long id, String nickname, String birthYearMonth, int ageMonths, Instant createdAt) {
        public static ChildResponse from(Child c, LocalDate today) {
            return new ChildResponse(c.getId(), c.getNickname(), c.getBirthYearMonth(), c.ageMonths(today),
                    c.getCreatedAt());
        }
    }
}
