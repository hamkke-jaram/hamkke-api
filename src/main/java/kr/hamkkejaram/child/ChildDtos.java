package kr.hamkkejaram.child;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;

public final class ChildDtos {
    private ChildDtos() {}

    static final String YEAR_MONTH = "^\\d{4}-(0[1-9]|1[0-2])$";
    /** 공백(전각 공백 포함)이 아닌 글자가 하나 이상 있어야 한다. null은 @Pattern 검사 대상이 아니다. */
    static final String NOT_BLANK = "(?sU).*\\S.*";

    /**
     * 동의 여부와 버전은 여기서 막지 않는다. 없거나 거부했거나 버전이 다르면 서비스가 422 GUARDIAN_CONSENT_REQUIRED로
     * 답한다(CHD-02). 여기서 막으면 같은 상황이 400 VALIDATION_FAILED로 바뀐다.
     */
    public record GuardianConsentInput(Boolean agreed, String version) {}

    public record CreateChildRequest(
            @NotBlank @Size(max = 20)
            @Pattern(regexp = NOT_BLANK, message = "별칭을 한 글자 이상 입력해 주세요.") String nickname,
            @NotBlank @Pattern(regexp = YEAR_MONTH, message = "생년월은 YYYY-MM 형식이에요.") String birthYearMonth,
            @Valid GuardianConsentInput guardianConsent) {}

    /** 수정은 보낸 항목만 바꾼다. 별칭은 생략할 수 있지만, 보냈다면 공백만으로 둘 수 없다. */
    public record UpdateChildRequest(
            @Size(min = 1, max = 20)
            @Pattern(regexp = NOT_BLANK, message = "별칭을 한 글자 이상 입력해 주세요.") String nickname,
            @Pattern(regexp = YEAR_MONTH, message = "생년월은 YYYY-MM 형식이에요.") String birthYearMonth) {}

    public record ChildResponse(long id, String nickname, String birthYearMonth, int ageMonths, Instant createdAt) {
        public static ChildResponse from(Child c, LocalDate today) {
            return new ChildResponse(c.getId(), c.getNickname(), c.getBirthYearMonth(), c.ageMonths(today),
                    c.getCreatedAt());
        }
    }
}
