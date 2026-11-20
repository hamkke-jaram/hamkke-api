package kr.hamkkejaram;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 요구사항 점검(2026-11-16)에서 찾은 빈틈을 막는 테스트.
 * 동시 요청 한도(SHR-02, CHD-05, ACC-04), 동의 오류 코드(CHD-02), 탈퇴 비밀번호 제한(ACC-06),
 * 앞뒤 공백 규칙(ACC-01, CHD-01), 기록 값 범위(REC-02).
 */
class RequirementGapsIntegrationTest extends IntegrationTestSupport {

    /** 같은 요청을 한꺼번에 보내고 상태 코드를 모은다. 모든 스레드가 준비된 뒤 동시에 출발한다. */
    private List<Integer> concurrently(int n, Callable<MockHttpServletRequestBuilder> request) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            CountDownLatch ready = new CountDownLatch(n);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(() -> {
                    MockHttpServletRequestBuilder b = request.call();
                    ready.countDown();
                    go.await();
                    MvcResult r = mvc.perform(b).andReturn();
                    return r.getResponse().getStatus();
                }));
            }
            ready.await(10, TimeUnit.SECONDS);
            go.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> f : futures) statuses.add(f.get(30, TimeUnit.SECONDS));
            return statuses;
        } finally {
            pool.shutdownNow();
        }
    }

    private static long count(List<Integer> statuses, int status) {
        return statuses.stream().filter(s -> s == status).count();
    }

    // ---------- 동시 요청 ----------

    @Test
    void concurrentInviteCodeRequestsNeverExceedThreeUsableCodes() throws Exception {
        String parent = token(signup("PARENT"));
        long childId = createChild(parent);
        List<Integer> statuses = concurrently(8,
                () -> auth(post("/api/v1/children/" + childId + "/invite-codes"), parent));
        assertThat(count(statuses, 201)).as(statuses.toString()).isEqualTo(3);
        assertThat(count(statuses, 422)).as(statuses.toString()).isEqualTo(5);
        mvc.perform(auth(get("/api/v1/children/" + childId + "/invite-codes"), parent))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(3));
    }

    @Test
    void concurrentChildRegistrationsNeverExceedFiveChildren() throws Exception {
        String parent = token(signup("PARENT"));
        for (int i = 0; i < 3; i++) createChild(parent);
        var req = Map.of("nickname", "바다", "birthYearMonth", "2024-05",
                "guardianConsent", Map.of("agreed", true, "version", V));
        List<Integer> statuses = concurrently(6, () -> body(auth(post("/api/v1/children"), parent), req));
        assertThat(count(statuses, 201)).as(statuses.toString()).isEqualTo(2);
        assertThat(count(statuses, 422)).as(statuses.toString()).isEqualTo(4);
        mvc.perform(auth(get("/api/v1/children"), parent)).andExpect(jsonPath("$.length()").value(5));
    }

    @Test
    void sameRefreshTokenUsedTwiceAtOnceIssuesAtMostOneNewToken() throws Exception {
        JsonNode tokens = signup("PARENT");
        String refresh = tokens.get("refreshToken").asText();
        List<Integer> statuses = concurrently(4,
                () -> body(post("/api/v1/auth/refresh"), Map.of("refreshToken", refresh)));
        assertThat(count(statuses, 200)).as(statuses.toString()).isLessThanOrEqualTo(1);
        assertThat(count(statuses, 401)).as(statuses.toString()).isGreaterThanOrEqualTo(3);
    }

    // ---------- CHD-02 ----------

    @Test
    void refusedOrMalformedGuardianConsentIsAlways422() throws Exception {
        String t = token(signup("PARENT"));
        List<Map<String, Object>> consents = new ArrayList<>();
        consents.add(Map.of("agreed", false, "version", V));
        consents.add(Map.of("agreed", true, "version", ""));
        consents.add(Map.of("agreed", true));
        consents.add(Map.of("version", V));
        consents.add(Map.of());
        for (Map<String, Object> consent : consents) {
            var req = new HashMap<String, Object>(Map.of("nickname", "하늘", "birthYearMonth", "2023-05"));
            req.put("guardianConsent", consent);
            mvc.perform(body(auth(post("/api/v1/children"), t), req))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("GUARDIAN_CONSENT_REQUIRED"));
        }
        mvc.perform(auth(get("/api/v1/children"), t)).andExpect(jsonPath("$.length()").value(0));
    }

    // ---------- ACC-06 ----------

    @Test
    void wrongPasswordsOnAccountDeletionLockLikeLogin() throws Exception {
        JsonNode tokens = signup("PARENT");
        String t = token(tokens);
        String email = tokens.at("/user/email").asText();
        for (int i = 0; i < 5; i++) {
            mvc.perform(body(auth(delete("/api/v1/me"), t), Map.of("password", "wrong999")))
                    .andExpect(status().isUnauthorized());
        }
        // 다섯 번 틀리면 맞는 비밀번호도 잠시 받지 않는다(탈퇴도, 로그인도)
        mvc.perform(body(auth(delete("/api/v1/me"), t), Map.of("password", PASSWORD)))
                .andExpect(status().isTooManyRequests());
        mvc.perform(body(post("/api/v1/auth/login"), Map.of("email", email, "password", PASSWORD)))
                .andExpect(status().isTooManyRequests());
        mvc.perform(auth(get("/api/v1/me"), t)).andExpect(status().isOk());
    }

    // ---------- ACC-01 / CHD-01 ----------

    @Test
    void displayNameIsMeasuredAfterTrimmingAtSignupLikeProfileEdit() throws Exception {
        Map<String, Object> req = new HashMap<>(signupRequest(uniqueEmail("trim"), PASSWORD, "PARENT"));
        String padded = "   " + "가".repeat(30) + "   ";
        req.put("displayName", padded);
        JsonNode ok = json(mvc.perform(body(post("/api/v1/auth/signup"), req))
                .andExpect(status().isCreated()).andReturn());
        assertThat(ok.at("/user/displayName").asText()).isEqualTo("가".repeat(30));

        Map<String, Object> blank = new HashMap<>(signupRequest(uniqueEmail("blank"), PASSWORD, "PARENT"));
        blank.put("displayName", "　　");
        mvc.perform(body(post("/api/v1/auth/signup"), blank)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        Map<String, Object> tooLong = new HashMap<>(signupRequest(uniqueEmail("long"), PASSWORD, "PARENT"));
        tooLong.put("displayName", "가".repeat(31));
        mvc.perform(body(post("/api/v1/auth/signup"), tooLong)).andExpect(status().isBadRequest());
    }

    @Test
    void childNicknameOfOnlyFullWidthSpacesIsRejectedAndPaddingIsRemoved() throws Exception {
        String t = token(signup("PARENT"));
        var blank = Map.of("nickname", "　　", "birthYearMonth", "2023-05",
                "guardianConsent", Map.of("agreed", true, "version", V));
        mvc.perform(body(auth(post("/api/v1/children"), t), blank)).andExpect(status().isBadRequest());

        var padded = Map.of("nickname", "　하늘　", "birthYearMonth", "2023-05",
                "guardianConsent", Map.of("agreed", true, "version", V));
        mvc.perform(body(auth(post("/api/v1/children"), t), padded)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.nickname").value("하늘"));
    }

    // ---------- REC-02 ----------

    @Test
    void recordValuesOutsideTheContractAreRejected() throws Exception {
        String t = token(signup("PARENT"));
        long childId = createChild(t);
        String path = "/api/v1/children/" + childId + "/interaction-records";

        Map<String, Object> latency = metrics(Instant.now());
        latency.put("responseLatencySec", 10.5);
        Map<String, Object> distress = metrics(Instant.now());
        distress.put("distressEpisodeCount", 201);
        Map<String, Object> responded = metrics(Instant.now());
        responded.put("childBidCount", 3);
        responded.put("respondedBidCount", 4);
        for (Map<String, Object> m : List.of(latency, distress, responded)) {
            mvc.perform(body(auth(post(path), t), m)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        // 경계값은 받는다
        Map<String, Object> edge = metrics(Instant.now());
        edge.put("responseLatencySec", 10.0);
        edge.put("distressEpisodeCount", 200);
        edge.put("childBidCount", 4);
        edge.put("respondedBidCount", 4);
        mvc.perform(body(auth(post(path), t), edge)).andExpect(status().isCreated());
    }

    /** 기기 날짜가 틀린 영상처럼 태어나기 전 시각의 기록은 받지 않는다(요약·추이에서 조용히 빠지지 않게). */
    @Test
    void recordShotBeforeTheBirthMonthIsRejected() throws Exception {
        String t = token(signup("PARENT"));
        var req = Map.of("nickname", "바다", "birthYearMonth", "2024-05",
                "guardianConsent", Map.of("agreed", true, "version", V));
        long childId = json(mvc.perform(body(auth(post("/api/v1/children"), t), req))
                .andExpect(status().isCreated()).andReturn()).get("id").asLong();
        String path = "/api/v1/children/" + childId + "/interaction-records";

        for (String at : List.of("1990-01-01T00:00:00Z", "2024-04-30T14:59:59Z")) { // 2024-05-01 00:00 KST 직전
            mvc.perform(body(auth(post(path), t), metrics(Instant.parse(at))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        // 태어난 달의 첫 순간(서비스 시간대)부터는 받는다
        mvc.perform(body(auth(post(path), t), metrics(Instant.parse("2024-04-30T15:00:00Z"))))
                .andExpect(status().isCreated());
    }
}
