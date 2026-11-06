package kr.hamkkejaram.common;

import jakarta.validation.Valid;
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
                            @Valid @NotNull RateLimit rateLimit) {

    /** secret은 HS256용 32바이트(영문 32자) 이상. 기본값이 없어서 설정하지 않으면 기동하지 않는다. */
    public record Jwt(@NotBlank @Size(min = 32) String secret, @NotBlank String issuer,
                      @NotNull Duration accessTtl, @NotNull Duration refreshTtl) {}

    public record Policy(@NotBlank String termsVersion, @NotBlank String privacyVersion,
                         @NotBlank String guardianVersion) {}

    public record RateLimit(int authRequestsPerMinute, int loginFailuresBeforeLock,
                            @NotNull Duration loginLockDuration) {}

    public List<String> allowedOrigins() {
        return corsAllowedOrigins == null ? List.of()
                : corsAllowedOrigins.stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
