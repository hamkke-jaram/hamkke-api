package kr.hamkkejaram.aiobs;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 영수증 검증(docs/ai-observation.md 4장). 영수증은 hamkke-ai 서버가 분석 결과에 서명한 HS256 JWT다.
 * 관찰 기록은 이 영수증으로만 저장되므로, 사용자가 문장을 직접 써서 넣을 수 없다.
 *
 * <p>여기서 확인하는 것: 서명, 알고리즘(HS256), 발급자({@code hamkke-ai}), 대상({@code hamkke-api}), 만료,
 * 클레임 모양, {@code obs}(6장). 경로의 아이·요청한 사용자와 맞는지, {@code jti}가 처음인지는
 * {@link AiObservationService}가 확인한다.
 *
 * <p>분석 토큰(발급자 {@code hamkke-api}, 대상 {@code hamkke-ai})은 같은 비밀로 서명되지만 발급자·대상이 달라
 * 영수증으로 쓸 수 없다.
 */
@Component
public class AiReceiptVerifier {
    public static final String ISSUER = "hamkke-ai";
    public static final String AUDIENCE = "hamkke-api";
    /** 영수증 유효 시간(계약). 이보다 길게 발급된 영수증은 받지 않는다. */
    static final Duration MAX_LIFETIME = Duration.ofMinutes(15);
    /** 두 서버 시계 차이 허용. */
    static final Duration CLOCK_SKEW = Duration.ofSeconds(60);
    static final int MAX_MODEL_VERSION_CHARS = 100;
    static final int MAX_PROMPT_VERSION_CHARS = 40;

    private static final Pattern UUID_PATTERN =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private final AiSettings settings;
    private final Clock clock;

    public AiReceiptVerifier(AiSettings settings, Clock clock) {
        this.settings = settings;
        this.clock = clock;
    }

    /** 검증을 통과한 영수증의 내용. */
    public record Verified(long userId, long childId, ObservationSource source, String jti,
                           ObservationContent content, String modelVersion, String promptVersion, int frameCount) {}

    /**
     * @throws InvalidReceiptException 영수증을 받아들일 수 없을 때(사유 코드만 담는다)
     * @throws kr.hamkkejaram.common.ApiException AI 기능이 꺼져 있을 때(503)
     */
    public Verified verify(String receipt) {
        Instant now = clock.instant();
        Jws<Claims> jws;
        try {
            jws = Jwts.parser()
                    .verifyWith(settings.key())
                    .requireIssuer(ISSUER)
                    .requireAudience(AUDIENCE)
                    .clock(() -> Date.from(now))
                    .clockSkewSeconds(CLOCK_SKEW.toSeconds())
                    .build()
                    .parseSignedClaims(receipt);
        } catch (JwtException | IllegalArgumentException e) {
            // 서명 불일치, 만료, 발급자·대상 불일치, 서명 없는 토큰, 형식 오류
            throw new InvalidReceiptException("jwt_" + e.getClass().getSimpleName());
        }
        if (!"HS256".equals(jws.getHeader().getAlgorithm())) throw new InvalidReceiptException("alg_not_hs256");

        try {
            return read(jws.getPayload(), now);
        } catch (JwtException | ClassCastException e) {
            // 클레임 자료형이 약속과 다를 때(예: exp가 숫자가 아님)
            throw new InvalidReceiptException("claims_malformed");
        }
    }

    private Verified read(Claims c, Instant now) {
        Date exp = c.getExpiration();
        Date iat = c.getIssuedAt();
        if (exp == null || iat == null) throw new InvalidReceiptException("exp_or_iat_missing");
        Instant latest = now.plus(CLOCK_SKEW);
        if (iat.toInstant().isAfter(latest)) throw new InvalidReceiptException("iat_in_future");
        if (exp.toInstant().isAfter(latest.plus(MAX_LIFETIME))) throw new InvalidReceiptException("lifetime_too_long");
        // 발급~만료 간격도 계약(15분 이하) 안이어야 한다. 오래전 iat에 가까운 exp를 붙인 영수증을 막는다.
        if (!exp.after(iat)) throw new InvalidReceiptException("exp_not_after_iat");
        if (Duration.between(iat.toInstant(), exp.toInstant()).compareTo(MAX_LIFETIME) > 0) {
            throw new InvalidReceiptException("lifetime_too_long");
        }

        // sub는 문자열로 온다(계약). 숫자로 와도 같은 값이면 받는다.
        long userId = positiveLong(c.get(Claims.SUBJECT), "sub");
        long childId = positiveLong(c.get("cid"), "cid");
        ObservationSource source = source(c.get("src"));
        if (!(c.get(Claims.ID) instanceof String jti) || !UUID_PATTERN.matcher(jti).matches()) {
            throw new InvalidReceiptException("jti_not_uuid");
        }

        ObservationContent content = ObservationContentParser.parse(c.get("obs"));
        String modelVersion = text(c.get("mv"), MAX_MODEL_VERSION_CHARS, "mv");
        String promptVersion = text(c.get("pv"), MAX_PROMPT_VERSION_CHARS, "pv");
        int frameCount = ObservationContentParser.intInRange(c.get("fc"), 1, settings.maxFrames(), "fc");

        return new Verified(userId, childId, source, jti.toLowerCase(Locale.ROOT), content, modelVersion,
                promptVersion, frameCount);
    }

    private static long positiveLong(Object value, String field) {
        long parsed;
        if (value instanceof String s) {
            try {
                parsed = Long.parseLong(s);
            } catch (NumberFormatException e) {
                throw new InvalidReceiptException(field + "_not_number");
            }
        } else if (value instanceof Number n) {
            double d = n.doubleValue();
            if (!Double.isFinite(d) || d != Math.rint(d) || Math.abs(d) > (double) (1L << 53)) {
                throw new InvalidReceiptException(field + "_not_integer");
            }
            parsed = n.longValue();
        } else {
            throw new InvalidReceiptException(field + "_missing");
        }
        if (parsed <= 0) throw new InvalidReceiptException(field + "_not_positive");
        return parsed;
    }

    private static ObservationSource source(Object value) {
        if (!(value instanceof String s)) throw new InvalidReceiptException("src_missing");
        try {
            return ObservationSource.valueOf(s);
        } catch (IllegalArgumentException e) {
            throw new InvalidReceiptException("src_unknown_value");
        }
    }

    private static String text(Object value, int maxChars, String field) {
        if (!(value instanceof String s)) throw new InvalidReceiptException(field + "_missing");
        String text = s.strip();
        if (text.isEmpty() || text.length() > maxChars || text.chars().anyMatch(Character::isISOControl)
                || text.chars().anyMatch(ch -> Character.isSurrogate((char) ch))) {
            throw new InvalidReceiptException(field + "_malformed");
        }
        return text;
    }
}
