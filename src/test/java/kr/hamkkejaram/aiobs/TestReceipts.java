package kr.hamkkejaram.aiobs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 테스트용 영수증 만들기. hamkke-ai 서버가 하는 일을 흉내 낸다: 공유 비밀로 HS256 JWS를 직접 조립한다.
 * 검증 쪽(jjwt)과 다른 구현으로 서명하므로, 다른 언어로 만든 영수증도 읽히는지 함께 확인된다.
 */
public final class TestReceipts {
    private TestReceipts() {}

    /** src/test/resources/application-test.yml 의 app.ai.shared-secret 과 같은 값. */
    public static final String SECRET = "test-ai-shared-secret-test-ai-shared-secret-0123";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

    /** 약속대로 만든 영수증 클레임. 테스트마다 필요한 것만 바꿔 쓴다. */
    public static Map<String, Object> claims(long userId, long childId, String src) {
        long now = Instant.now().getEpochSecond();
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("iss", "hamkke-ai");
        c.put("aud", "hamkke-api");
        c.put("sub", Long.toString(userId));
        c.put("cid", childId);
        c.put("src", src);
        c.put("jti", UUID.randomUUID().toString());
        c.put("obs", obs("어른과 아이가 블록을 사이에 두고 마주 앉아 있어요. 아이가 블록을 밀어내자 어른이 잠시 기다린 뒤 말을 걸어요.",
                List.of("PLAYING_TOGETHER", "REFUSING"), List.of("WAITING", "TALKING"),
                List.of(moment(12.5, "아이가 블록을 밀어내요.")), 2));
        c.put("mv", "qwen3.5:4b");
        c.put("pv", "obs-v1");
        c.put("fc", 6);
        c.put("iat", now);
        c.put("exp", now + 900);
        return c;
    }

    public static Map<String, Object> obs(String summary, List<String> childStates, List<String> adultResponses,
                                          List<Map<String, Object>> moments, Object peopleCount) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("summary", summary);
        o.put("childStates", childStates);
        o.put("adultResponses", adultResponses);
        o.put("moments", new ArrayList<>(moments));
        o.put("peopleCount", peopleCount);
        return o;
    }

    public static Map<String, Object> moment(Object t, Object note) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("t", t);
        m.put("note", note);
        return m;
    }

    public static String sign(Map<String, Object> claims) {
        return sign(claims, SECRET);
    }

    public static String sign(Map<String, Object> claims, String secret) {
        return sign(claims, secret, "HS256", "HmacSHA256");
    }

    public static String sign(Map<String, Object> claims, String secret, String alg, String jcaName) {
        try {
            String header = B64.encodeToString(
                    JSON.writeValueAsBytes(Map.of("alg", alg, "typ", "JWT")));
            String payload = B64.encodeToString(JSON.writeValueAsBytes(claims));
            String signingInput = header + "." + payload;
            return signingInput + "." + B64.encodeToString(hmac(signingInput, secret, jcaName));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 서명 없는 토큰(alg=none). 받아들여지면 안 된다. */
    public static String unsigned(Map<String, Object> claims) {
        try {
            return B64.encodeToString(JSON.writeValueAsBytes(Map.of("alg", "none", "typ", "JWT")))
                    + "." + B64.encodeToString(JSON.writeValueAsBytes(claims)) + ".";
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 토큰의 본문(클레임)을 읽는다. 서명은 보지 않는다. */
    public static JsonNode payload(String jws) {
        try {
            return JSON.readTree(Base64.getUrlDecoder().decode(jws.split("\\.")[1]));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static JsonNode header(String jws) {
        try {
            return JSON.readTree(Base64.getUrlDecoder().decode(jws.split("\\.")[0]));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 공유 비밀로 HS256 서명이 맞는지 직접 계산해 본다(AI 서버가 분석 토큰을 검증하는 방식). */
    public static boolean signatureMatches(String jws, String secret) {
        try {
            int lastDot = jws.lastIndexOf('.');
            String expected = B64.encodeToString(hmac(jws.substring(0, lastDot), secret, "HmacSHA256"));
            return expected.equals(jws.substring(lastDot + 1));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] hmac(String input, String secret, String jcaName) throws Exception {
        Mac mac = Mac.getInstance(jcaName);
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), jcaName));
        return mac.doFinal(input.getBytes(StandardCharsets.US_ASCII));
    }
}
