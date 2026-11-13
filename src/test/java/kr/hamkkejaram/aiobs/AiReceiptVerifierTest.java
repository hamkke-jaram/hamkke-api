package kr.hamkkejaram.aiobs;

import com.fasterxml.jackson.databind.JsonNode;
import kr.hamkkejaram.common.ApiException;
import kr.hamkkejaram.common.AppProperties;
import kr.hamkkejaram.common.ErrorCode;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static kr.hamkkejaram.aiobs.TestReceipts.SECRET;
import static kr.hamkkejaram.aiobs.TestReceipts.moment;
import static kr.hamkkejaram.aiobs.TestReceipts.obs;
import static kr.hamkkejaram.aiobs.TestReceipts.sign;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 분석 토큰 발급과 영수증 검증 (docs/ai-observation.md 4장). */
class AiReceiptVerifierTest {
    private static final Instant NOW = Instant.parse("2026-11-13T03:00:00Z");
    private static final String JWT_SECRET = "test-secret-test-secret-test-secret-0123";
    private static final long USER = 11L;
    private static final long CHILD = 7L;

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final AiSettings settings = new AiSettings(props(true, SECRET), false);
    private final AiReceiptVerifier verifier = new AiReceiptVerifier(settings, clock);
    private final AiSessionTokens sessions = new AiSessionTokens(settings, clock);

    static AppProperties props(boolean enabled, String aiSecret) {
        return new AppProperties(
                new AppProperties.Jwt(JWT_SECRET, "hamkke-jaram", Duration.ofMinutes(30), Duration.ofDays(14)),
                new AppProperties.Policy("2026-11-v1", "2026-11-v1", "2026-11-v1", "2026-11-v1"),
                List.of(),
                new AppProperties.RateLimit(20, 5, Duration.ofMinutes(15), 20),
                new AppProperties.Ai(enabled, aiSecret, 6, Duration.ofMinutes(10)));
    }

    /** 고정 시계 기준으로 약속대로 만든 영수증 클레임. */
    private Map<String, Object> claims() {
        Map<String, Object> c = TestReceipts.claims(USER, CHILD, "HOME");
        c.put("iat", NOW.getEpochSecond());
        c.put("exp", NOW.getEpochSecond() + 900);
        return c;
    }

    private void assertRejected(String receipt, String reasonPrefix) {
        assertThatThrownBy(() -> verifier.verify(receipt))
                .isInstanceOf(InvalidReceiptException.class)
                .extracting(e -> ((InvalidReceiptException) e).reason()).asString().startsWith(reasonPrefix);
    }

    private void assertRejected(Map<String, Object> claims, String reasonPrefix) {
        assertRejected(sign(claims), reasonPrefix);
    }

    // ---------- 받아들이는 경우 ----------

    @Test
    void acceptsAReceiptSignedWithTheSharedSecret() {
        Map<String, Object> c = claims();
        AiReceiptVerifier.Verified v = verifier.verify(sign(c));
        assertThat(v.userId()).isEqualTo(USER);
        assertThat(v.childId()).isEqualTo(CHILD);
        assertThat(v.source()).isEqualTo(ObservationSource.HOME);
        assertThat(v.jti()).isEqualTo(c.get("jti"));
        assertThat(v.modelVersion()).isEqualTo("qwen3.5:4b");
        assertThat(v.promptVersion()).isEqualTo("obs-v1");
        assertThat(v.frameCount()).isEqualTo(6);
        assertThat(v.content().childStates()).containsExactly(ChildState.PLAYING_TOGETHER, ChildState.REFUSING);
        assertThat(v.content().adultResponses()).containsExactly(AdultResponse.WAITING, AdultResponse.TALKING);
        assertThat(v.content().moments()).hasSize(1);
        assertThat(v.content().peopleCount()).isEqualTo(2);
        assertThat(v.content().summary()).startsWith("어른과 아이가");
    }

    @Test
    void acceptsAudienceAsArrayAndInstitutionSource() {
        Map<String, Object> c = claims();
        c.put("aud", List.of("hamkke-api"));
        c.put("src", "INSTITUTION");
        assertThat(verifier.verify(sign(c)).source()).isEqualTo(ObservationSource.INSTITUTION);
    }

    @Test
    void jtiIsNormalisedToLowerCase() {
        Map<String, Object> c = claims();
        c.put("jti", "0F8FAD5B-D9CB-469F-A165-70867728950E");
        assertThat(verifier.verify(sign(c)).jti()).isEqualTo("0f8fad5b-d9cb-469f-a165-70867728950e");
    }

    @Test
    void toleratesSmallClockDifferenceBetweenServers() {
        Map<String, Object> c = claims();
        c.put("iat", NOW.getEpochSecond() + 20);       // AI 서버 시계가 20초 빠름
        c.put("exp", NOW.getEpochSecond() + 20 + 900);
        assertThat(verifier.verify(sign(c)).childId()).isEqualTo(CHILD);

        c = claims();
        c.put("iat", NOW.getEpochSecond() - 920);       // 만료 직후 20초
        c.put("exp", NOW.getEpochSecond() - 20);
        assertThat(verifier.verify(sign(c)).childId()).isEqualTo(CHILD);
    }

    @Test
    void sentenceFilterRunsAgainOnTheReceipt() {
        Map<String, Object> c = claims();
        String word = ObservationSentenceFilter.forbiddenExamples().get(0);
        c.put("obs", obs("아이가 놀아요. " + word + " 같아요.", List.of("CALM"), List.of(),
                List.of(moment(1, "아이가 웃어요."), moment(2, "이렇게 " + ObservationSentenceFilter.judgingWords().get(12))),
                1));
        ObservationContent content = verifier.verify(sign(c)).content();
        assertThat(content.summary()).isNull();
        assertThat(content.moments()).extracting(ObservationContent.Moment::note).containsExactly("아이가 웃어요.");
        assertThat(content.childStates()).containsExactly(ChildState.CALM);
    }

    // ---------- 서명·발급자·대상·만료 ----------

    @Test
    void rejectsForgedSignature() {
        assertRejected(sign(claims(), "another-secret-another-secret-another-0123"), "jwt_");
        // 로그인 토큰의 비밀로 서명한 것도 받지 않는다
        assertRejected(sign(claims(), JWT_SECRET), "jwt_");
    }

    @Test
    void rejectsTamperedPayload() {
        String receipt = sign(claims());
        Map<String, Object> other = claims();
        other.put("cid", CHILD + 1);
        String[] good = receipt.split("\\.");
        String[] bad = sign(other, "another-secret-another-secret-another-0123").split("\\.");
        // 본문만 바꾸고 원래 서명을 붙인다
        assertRejected(good[0] + "." + bad[1] + "." + good[2], "jwt_");
    }

    @Test
    void rejectsUnsignedAndMalformedTokens() {
        assertRejected(TestReceipts.unsigned(claims()), "jwt_");
        assertRejected("not-a-token", "jwt_");
        assertRejected("", "jwt_");
        assertRejected("a.b.c", "jwt_");
    }

    @Test
    void rejectsOtherHmacAlgorithms() {
        // 비밀이 48바이트 이상이면 HS384로 서명한 토큰도 jjwt는 검증할 수 있다. 계약은 HS256뿐이다.
        assertThat(SECRET.length()).isGreaterThanOrEqualTo(48);
        assertRejected(sign(claims(), SECRET, "HS384", "HmacSHA384"), "alg_not_hs256");
    }

    @Test
    void rejectsWrongIssuerOrAudience() {
        Map<String, Object> c = claims();
        c.put("iss", "hamkke-api");
        assertRejected(c, "jwt_");

        c = claims();
        c.remove("iss");
        assertRejected(c, "jwt_");

        c = claims();
        c.put("aud", "hamkke-ai");
        assertRejected(c, "jwt_");

        c = claims();
        c.put("aud", List.of("hamkke-web", "hamkke-ai"));
        assertRejected(c, "jwt_");

        c = claims();
        c.remove("aud");
        assertRejected(c, "jwt_");
    }

    @Test
    void rejectsExpiredReceipt() {
        Map<String, Object> c = claims();
        c.put("iat", NOW.getEpochSecond() - 1900);
        c.put("exp", NOW.getEpochSecond() - 1000);
        assertRejected(c, "jwt_ExpiredJwtException");
    }

    @Test
    void rejectsReceiptWithoutExpiryOrWithLongLifetime() {
        Map<String, Object> c = claims();
        c.remove("exp");
        assertRejected(c, "exp_or_iat_missing");

        c = claims();
        c.remove("iat");
        assertRejected(c, "exp_or_iat_missing");

        c = claims();
        c.put("exp", NOW.getEpochSecond() + 3600); // 계약은 15분
        assertRejected(c, "lifetime_too_long");

        c = claims();
        c.put("iat", NOW.getEpochSecond() + 600);
        c.put("exp", NOW.getEpochSecond() + 900);
        assertRejected(c, "iat_in_future");
    }

    /** 분석 토큰은 같은 비밀로 서명되지만 영수증으로 쓸 수 없다(사용자가 가진 토큰으로 기록을 만들 수 없다). */
    @Test
    void sessionTokenIsNotAReceipt() {
        String token = sessions.issue(USER, CHILD, ObservationSource.HOME).token();
        assertRejected(token, "jwt_");
    }

    // ---------- 클레임 모양 ----------

    @Test
    void rejectsMalformedIdentityClaims() {
        Map<String, Object> c = claims();
        c.put("sub", "abc");
        assertRejected(c, "sub_");

        c = claims();
        c.remove("sub");
        assertRejected(c, "sub_");

        c = claims();
        c.put("cid", "seven");
        assertRejected(c, "cid_");

        c = claims();
        c.put("cid", 7.5);
        assertRejected(c, "cid_");

        c = claims();
        c.put("cid", 0);
        assertRejected(c, "cid_");

        c = claims();
        c.put("src", "SCHOOL");
        assertRejected(c, "src_");

        c = claims();
        c.remove("src");
        assertRejected(c, "src_");

        c = claims();
        c.put("jti", "not-a-uuid");
        assertRejected(c, "jti_not_uuid");

        c = claims();
        c.remove("jti");
        assertRejected(c, "jti_not_uuid");
    }

    @Test
    void acceptsSubjectAndChildAsEitherStringOrNumber() {
        Map<String, Object> c = claims();
        c.put("cid", Long.toString(CHILD));
        assertThat(verifier.verify(sign(c)).childId()).isEqualTo(CHILD);
    }

    @Test
    void rejectsMalformedModelClaims() {
        Map<String, Object> c = claims();
        c.put("fc", 0);
        assertRejected(c, "fc_");

        c = claims();
        c.put("fc", 7); // 한 번에 최대 6장
        assertRejected(c, "fc_");

        c = claims();
        c.remove("fc");
        assertRejected(c, "fc_");

        c = claims();
        c.put("mv", "");
        assertRejected(c, "mv_");

        c = claims();
        c.put("mv", "m".repeat(101));
        assertRejected(c, "mv_");

        c = claims();
        c.remove("pv");
        assertRejected(c, "pv_");
    }

    @Test
    void rejectsMalformedObservation() {
        Map<String, Object> c = claims();
        c.remove("obs");
        assertRejected(c, "obs_not_object");

        c = claims();
        c.put("obs", obs(null, List.of("SLEEPING"), List.of(), List.of(), 1));
        assertRejected(c, "childStates_unknown_value");

        c = claims();
        c.put("obs", obs(null, List.of("CALM"), List.of(), List.of(), 9));
        assertRejected(c, "peopleCount_out_of_range");
    }

    // ---------- 분석 토큰 ----------

    @Test
    void sessionTokenCarriesTheAgreedClaims() {
        AiSessionTokens.Issued issued = sessions.issue(USER, CHILD, ObservationSource.INSTITUTION);
        assertThat(issued.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(10)));
        assertThat(TestReceipts.signatureMatches(issued.token(), SECRET)).isTrue();
        assertThat(TestReceipts.header(issued.token()).get("alg").asText()).isEqualTo("HS256");

        JsonNode p = TestReceipts.payload(issued.token());
        assertThat(p.get("iss").asText()).isEqualTo("hamkke-api");
        assertThat(p.get("aud").isTextual()).isTrue(); // 배열이 아니라 문자열 하나
        assertThat(p.get("aud").asText()).isEqualTo("hamkke-ai");
        assertThat(p.get("sub").isTextual()).isTrue();
        assertThat(p.get("sub").asText()).isEqualTo("11");
        assertThat(p.get("cid").isIntegralNumber()).isTrue();
        assertThat(p.get("cid").asLong()).isEqualTo(CHILD);
        assertThat(p.get("src").asText()).isEqualTo("INSTITUTION");
        assertThat(p.get("jti").asText()).isEqualTo(issued.jti()).hasSize(36);
        assertThat(p.get("iat").asLong()).isEqualTo(NOW.getEpochSecond());
        assertThat(p.get("exp").asLong()).isEqualTo(NOW.getEpochSecond() + 600);
        // 사진·문장·이름 같은 것은 토큰에 없다
        assertThat(p.size()).isEqualTo(8);
    }

    @Test
    void eachSessionGetsItsOwnJti() {
        assertThat(sessions.issue(USER, CHILD, ObservationSource.HOME).jti())
                .isNotEqualTo(sessions.issue(USER, CHILD, ObservationSource.HOME).jti());
    }

    // ---------- 설정 ----------

    @Test
    void enabledFeatureNeedsALongSecretDifferentFromTheLoginSecret() {
        assertThatThrownBy(() -> new AiSettings(props(true, null), false)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new AiSettings(props(true, ""), false)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new AiSettings(props(true, "short-secret"), false)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new AiSettings(props(true, " ".repeat(40)), false)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new AiSettings(props(true, JWT_SECRET), false)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET");
        assertThat(new AiSettings(props(true, SECRET), false).enabled()).isTrue();
        assertThat(new AiSettings(props(true, SECRET), true).enabled()).isTrue();
    }

    @Test
    void productionRefusesTheLocalDevelopmentSecret() {
        assertThat(new AiSettings(props(true, AiSettings.LOCAL_DEV_SECRET), false).enabled()).isTrue();
        assertThatThrownBy(() -> new AiSettings(props(true, AiSettings.LOCAL_DEV_SECRET), true))
                .isInstanceOf(IllegalStateException.class);
    }

    /** hamkke-ai 서버가 환경변수 앞뒤 공백을 지우고 쓰므로 여기서도 같은 값으로 서명·검증해야 한다. */
    @Test
    void surroundingWhitespaceInTheSecretIsIgnored() {
        AiSettings padded = new AiSettings(props(true, "  " + SECRET + "\n"), false);
        String token = new AiSessionTokens(padded, clock).issue(USER, CHILD, ObservationSource.HOME).token();
        assertThat(TestReceipts.signatureMatches(token, SECRET)).isTrue();
        assertThat(new AiReceiptVerifier(padded, clock).verify(sign(claims())).childId()).isEqualTo(CHILD);
    }

    @Test
    void disabledFeatureStartsWithoutSecretAndAnswersUnavailable() {
        AiSettings off = new AiSettings(props(false, ""), true);
        assertThat(off.enabled()).isFalse();
        assertThatThrownBy(off::requireEnabled).isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code()).isEqualTo(ErrorCode.AI_UNAVAILABLE);
        AiReceiptVerifier offVerifier = new AiReceiptVerifier(off, clock);
        assertThatThrownBy(() -> offVerifier.verify(sign(claims()))).isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code()).isEqualTo(ErrorCode.AI_UNAVAILABLE);
        assertThatThrownBy(() -> new AiSessionTokens(off, clock).issue(USER, CHILD, ObservationSource.HOME))
                .isInstanceOf(ApiException.class);
    }
}
