package kr.hamkkejaram.common;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.List;

@Validated
@ConfigurationProperties(prefix = "app")
public record AppProperties(@Valid @NotNull Jwt jwt,
                            @Valid @NotNull Policy policy,
                            List<String> corsAllowedOrigins,
                            @Valid @NotNull RateLimit rateLimit,
                            @Valid @NotNull Ai ai) {

    /** secret은 HS256용 32바이트(영문 32자) 이상. 기본값이 없어서 설정하지 않으면 기동하지 않는다. */
    public record Jwt(@NotBlank @Size(min = 32) String secret, @NotBlank String issuer,
                      @NotNull Duration accessTtl, @NotNull Duration refreshTtl) {}

    public record Policy(@NotBlank String termsVersion, @NotBlank String privacyVersion,
                         @NotBlank String guardianVersion, @NotBlank String aiAnalysisVersion) {}

    public record RateLimit(int authRequestsPerMinute, int loginFailuresBeforeLock,
                            @NotNull Duration loginLockDuration, @Min(1) int aiSessionsPerHour) {}

    /**
     * AI 관찰 기록(docs/ai-observation.md). sharedSecret은 hamkke-ai 서버와 나눠 쓰는 HS256 키다.
     * 켜져 있을 때의 길이·중복 검사는 {@code aiobs.AiSettings}가 기동 시점에 한다(꺼져 있으면 비워 둘 수 있다).
     * maxFrames는 한 번에 보내는 장면 사진 수의 상한(계약상 최대 6장).
     */
    public record Ai(boolean enabled, String sharedSecret, @Min(1) @Max(6) int maxFrames,
                     @NotNull Duration sessionTtl) {}

    public List<String> allowedOrigins() {
        return corsAllowedOrigins == null ? List.of()
                : corsAllowedOrigins.stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
