package kr.hamkkejaram.aiobs;

import io.jsonwebtoken.Jwts;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.UUID;

/**
 * 분석 토큰 발급(docs/ai-observation.md 4장). hamkke-ai 서버가 검증한다. HS256, 유효 10분, 한 번의 분석용.
 * 토큰에는 사진도 문장도 없다: 누가(sub) 어느 아이(cid)를 어디서(src) 분석하는지만 담는다.
 */
@Component
public class AiSessionTokens {
    public static final String ISSUER = "hamkke-api";
    public static final String AUDIENCE = "hamkke-ai";

    private final AiSettings settings;
    private final Clock clock;

    public AiSessionTokens(AiSettings settings, Clock clock) {
        this.settings = settings;
        this.clock = clock;
    }

    public record Issued(String token, Instant expiresAt, String jti) {}

    /**
     * aud는 배열이 아니라 문자열 하나로 싣는다(계약의 표 그대로, 다른 언어의 검증기가 읽기 쉽게).
     * jjwt 0.12에서 문자열 aud를 만드는 방법은 {@code audience().single()}뿐이라 폐기 예정 경고를 끈다.
     */
    @SuppressWarnings("deprecation")
    public Issued issue(long userId, long childId, ObservationSource source) {
        // JWT의 iat·exp는 초 단위라 응답의 expiresAt도 초 단위로 맞춘다.
        Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        Instant expiresAt = now.plus(settings.sessionTtl());
        String jti = UUID.randomUUID().toString();
        String token = Jwts.builder()
                .issuer(ISSUER)
                .audience().single(AUDIENCE)
                .subject(Long.toString(userId))
                .claim("cid", childId)
                .claim("src", source.name())
                .id(jti)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .signWith(settings.key(), Jwts.SIG.HS256)
                .compact();
        return new Issued(token, expiresAt, jti);
    }
}
