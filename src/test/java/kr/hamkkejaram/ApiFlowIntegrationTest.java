package kr.hamkkejaram;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApiFlowIntegrationTest {
    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final String V = "2026-11-v1";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;

    // ---------- helpers ----------

    private JsonNode json(MvcResult r) throws Exception {
        String body = r.getResponse().getContentAsString();
        return body.isEmpty() ? om.nullNode() : om.readTree(body);
    }

    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder b, String token) {
        return b.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    private MockHttpServletRequestBuilder body(MockHttpServletRequestBuilder b, Object o) throws Exception {
        return b.contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(o));
    }

    private JsonNode signup(String role) throws Exception {
        String email = role.toLowerCase() + SEQ.incrementAndGet() + "@example.com";
        var req = Map.of("email", email, "password", "abcd1234", "displayName", role.equals("PARENT") ? "하늘맘" : "김선생",
                "role", role, "consents", new Object[]{
                        Map.of("type", "TERMS_OF_SERVICE", "version", V),
                        Map.of("type", "PRIVACY_POLICY", "version", V)});
        return json(mvc.perform(body(post("/api/v1/auth/signup"), req)).andExpect(status().isCreated()).andReturn());
    }

    private String token(JsonNode tokens) {
        return tokens.get("accessToken").asText();
    }

    private long createChild(String token) throws Exception {
        var req = Map.of("nickname", "하늘", "birthYearMonth", LocalDate.now().minusYears(3).toString().substring(0, 7),
                "guardianConsent", Map.of("agreed", true, "version", V));
        return json(mvc.perform(body(auth(post("/api/v1/children"), token), req))
                .andExpect(status().isCreated()).andReturn()).get("id").asLong();
    }

    private Map<String, Object> metrics(String clientId, Instant at, double analyzed, double joint, Double latency,
                                        int distress) {
        var m = new java.util.HashMap<String, Object>();
        m.put("clientRecordId", clientId);
        m.put("recordedAt", at.toString());
        m.put("analyzedSeconds", analyzed);
        m.put("jointPlaySeconds", joint);
        m.put("responseLatencySec", latency);
        m.put("childBidCount", 10);
        m.put("respondedBidCount", 8);
        m.put("distressEpisodeCount", distress);
        m.put("modelVersion", "pose_landmarker_lite+audio-heuristic-1");
        m.put("specVersion", "metrics-v1");
        return m;
    }

    private JsonNode postRecord(String token, long childId, Map<String, Object> m, int expectedStatus) throws Exception {
        return json(mvc.perform(body(auth(post("/api/v1/children/" + childId + "/interaction-records"), token), m))
                .andExpect(status().is(expectedStatus)).andReturn());
    }

    // ---------- tests ----------

    @Test
    void publicEndpointsNeedNoLogin() throws Exception {
        mvc.perform(get("/api/v1/support-resources")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").exists());
        mvc.perform(get("/api/v1/policies")).andExpect(status().isOk())
                .andExpect(jsonPath("$.disclaimer").value(org.hamcrest.Matchers.containsString("의료기기가 아니며")));
        mvc.perform(get("/api/v1/children")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void signupRequiresConsents() throws Exception {
        var req = Map.of("email", "noconsent@example.com", "password", "abcd1234", "displayName", "a",
                "role", "PARENT", "consents", new Object[]{Map.of("type", "TERMS_OF_SERVICE", "version", V)});
        mvc.perform(body(post("/api/v1/auth/signup"), req)).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CONSENT_REQUIRED"));
    }

    @Test
    void childRequiresGuardianConsent() throws Exception {
        String t = token(signup("PARENT"));
        var noConsent = Map.of("nickname", "하늘", "birthYearMonth", "2023-05");
        mvc.perform(body(auth(post("/api/v1/children"), t), noConsent)).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("GUARDIAN_CONSENT_REQUIRED"));
        var wrongVersion = Map.of("nickname", "하늘", "birthYearMonth", "2023-05",
                "guardianConsent", Map.of("agreed", true, "version", "old"));
        mvc.perform(body(auth(post("/api/v1/children"), t), wrongVersion)).andExpect(status().isUnprocessableEntity());
        mvc.perform(auth(get("/api/v1/children"), t)).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void teacherCannotRegisterChild() throws Exception {
        String t = token(signup("TEACHER"));
        var req = Map.of("nickname", "하늘", "birthYearMonth", "2023-05",
                "guardianConsent", Map.of("agreed", true, "version", V));
        mvc.perform(body(auth(post("/api/v1/children"), t), req)).andExpect(status().isForbidden());
    }

    @Test
    void serverRejectsFileUploadsAndLargeBodies() throws Exception {
        String t = token(signup("PARENT"));
        long childId = createChild(t);
        var file = new MockMultipartFile("video", "play.mp4", "video/mp4", new byte[1024]);
        mvc.perform(auth(multipart("/api/v1/children/" + childId + "/interaction-records").file(file), t))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
        mvc.perform(auth(post("/api/v1/children/" + childId + "/interaction-records"), t)
                        .contentType("video/mp4").content(new byte[100]))
                .andExpect(status().isUnsupportedMediaType());
        String big = "{\"x\":\"" + "a".repeat(70_000) + "\"}";
        mvc.perform(auth(post("/api/v1/children/" + childId + "/interaction-records"), t)
                        .contentType(MediaType.APPLICATION_JSON).content(big))
                .andExpect(status().isPayloadTooLarge());
    }

    @Test
    void recordFlowWithTrendCardsAndIdempotency() throws Exception {
        String t = token(signup("PARENT"));
        long childId = createChild(t);
        Instant now = Instant.now();

        String first = UUID.randomUUID().toString();
        JsonNode r1 = postRecord(t, childId, metrics(first, now.minus(2, ChronoUnit.DAYS), 120, 48, 1.5, 1), 201);
        assertThat(r1.at("/record/changeTrend").asText()).isEqualTo("FIRST_RECORD");
        assertThat(r1.at("/record/jointPlayRatio").asDouble()).isEqualTo(0.4);
        assertThat(r1.at("/coaching/delivery/card/sourceTitle").asText()).isNotBlank();
        assertThat(r1.at("/coaching/delivery/card/situation").asText()).isIn("FIRST_RECORD", "ANY");
        long firstCardId = r1.at("/coaching/delivery/card/id").asLong();

        // 같은 clientRecordId 재전송 → 200, 같은 결과
        JsonNode again = postRecord(t, childId, metrics(first, now.minus(2, ChronoUnit.DAYS), 120, 48, 1.5, 1), 200);
        assertThat(again.at("/record/id").asLong()).isEqualTo(r1.at("/record/id").asLong());
        assertThat(again.at("/coaching/delivery/id").asLong()).isEqualTo(r1.at("/coaching/delivery/id").asLong());

        // 함께 노는 비율 0.4 → 0.7: GROWING + JOINT_PLAY 카드
        JsonNode r2 = postRecord(t, childId,
                metrics(UUID.randomUUID().toString(), now.minus(1, ChronoUnit.DAYS), 120, 84, 1.5, 1), 201);
        assertThat(r2.at("/record/changeTrend").asText()).isEqualTo("GROWING");
        assertThat(r2.at("/metricChanges/jointPlay").asText()).isEqualTo("UP");
        assertThat(r2.at("/coaching/delivery/card/metricFocus").asText()).isIn("JOINT_PLAY", "GENERAL");
        assertThat(r2.at("/coaching/delivery/card/id").asLong()).isNotEqualTo(firstCardId);

        // 두 지표가 함께 크게 줄면(이전 기록 2개 이상) 카드 대신 상담·기관 안내
        JsonNode r3 = postRecord(t, childId,
                metrics(UUID.randomUUID().toString(), now, 120, 10, 4.0, 1), 201);
        assertThat(r3.at("/record/changeTrend").asText()).isEqualTo("NEEDS_ATTENTION");
        assertThat(r3.at("/coaching/delivery").isNull()).isTrue();
        assertThat(r3.at("/coaching/guidance/resources").size()).isGreaterThan(0);

        mvc.perform(auth(get("/api/v1/children/" + childId + "/weekly-summary"), t)).andExpect(status().isOk())
                .andExpect(jsonPath("$.weeks.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)));
        mvc.perform(auth(get("/api/v1/children/" + childId + "/card-deliveries"), t))
                .andExpect(jsonPath("$.length()").value(2));

        long deliveryId = r2.at("/coaching/delivery/id").asLong();
        mvc.perform(auth(post("/api/v1/children/" + childId + "/card-deliveries/" + deliveryId + "/tried"), t))
                .andExpect(status().isOk()).andExpect(jsonPath("$.triedAt").exists());
    }

    @Test
    void recordValidation() throws Exception {
        String t = token(signup("PARENT"));
        long childId = createChild(t);
        // 함께 노는 시간 > 분석 길이
        postRecord(t, childId, metrics(UUID.randomUUID().toString(), Instant.now(), 60, 90, 1.0, 0), 400);
        // 지원하지 않는 지표 버전
        var m = metrics(UUID.randomUUID().toString(), Instant.now(), 60, 30, 1.0, 0);
        m.put("specVersion", "metrics-v9");
        postRecord(t, childId, m, 400);
        // 미래 시각
        postRecord(t, childId, metrics(UUID.randomUUID().toString(), Instant.now().plus(1, ChronoUnit.DAYS), 60, 30,
                1.0, 0), 400);
        // 분석 길이 너무 짧음
        postRecord(t, childId, metrics(UUID.randomUUID().toString(), Instant.now(), 5, 1, 1.0, 0), 400);
    }

    @Test
    void otherParentsCannotSeeChild() throws Exception {
        String owner = token(signup("PARENT"));
        String other = token(signup("PARENT"));
        long childId = createChild(owner);
        mvc.perform(auth(get("/api/v1/children/" + childId), other)).andExpect(status().isNotFound());
        postRecord(other, childId, metrics(UUID.randomUUID().toString(), Instant.now(), 60, 30, 1.0, 0), 404);
        mvc.perform(auth(get("/api/v1/children/" + childId + "/goals"), other)).andExpect(status().isNotFound());
    }

    @Test
    void teacherLinkingSharingGoalsAndNotes() throws Exception {
        String parent = token(signup("PARENT"));
        JsonNode teacherTokens = signup("TEACHER");
        String teacher = token(teacherTokens);
        long teacherId = teacherTokens.at("/user/id").asLong();
        long childId = createChild(parent);
        postRecord(parent, childId, metrics(UUID.randomUUID().toString(), Instant.now(), 120, 60, 1.2, 2), 201);

        // 연결 전에는 접근 불가
        mvc.perform(auth(get("/api/v1/teacher/children/" + childId + "/shared-summary"), teacher))
                .andExpect(status().isNotFound());
        mvc.perform(body(auth(post("/api/v1/teacher/links"), teacher), Map.of("code", "ZZZZZZZZ")))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("INVITE_CODE_INVALID"));

        String code = json(mvc.perform(auth(post("/api/v1/children/" + childId + "/invite-codes"), parent))
                .andExpect(status().isCreated()).andReturn()).get("code").asText();
        assertThat(code).hasSize(8).doesNotContain("0", "O", "1", "I");

        mvc.perform(body(auth(post("/api/v1/teacher/links"), teacher),
                        Map.of("code", code.substring(0, 4) + "-" + code.substring(4).toLowerCase())))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(childId));
        // 1회용
        String teacher2 = token(signup("TEACHER"));
        mvc.perform(body(auth(post("/api/v1/teacher/links"), teacher2), Map.of("code", code)))
                .andExpect(status().isUnprocessableEntity());

        mvc.perform(auth(get("/api/v1/teacher/children"), teacher)).andExpect(jsonPath("$[0].nickname").value("하늘"));

        // 기본값: 전부 비공개 → 지표 없음
        mvc.perform(auth(get("/api/v1/teacher/children/" + childId + "/shared-summary"), teacher))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sharedItems.length()").value(0))
                .andExpect(jsonPath("$.weeks.length()").value(0));
        mvc.perform(auth(get("/api/v1/children/" + childId + "/card-deliveries"), teacher))
                .andExpect(status().isNotFound());
        // 교사는 부모 전용 원 기록을 볼 수 없다
        mvc.perform(auth(get("/api/v1/children/" + childId + "/interaction-records"), teacher))
                .andExpect(status().isNotFound());

        // 함께 노는 시간만 공유
        mvc.perform(body(auth(put("/api/v1/children/" + childId + "/share-settings"), parent),
                        Map.of("jointPlay", true, "responseLatency", false, "distress", false, "coachingCards", true)))
                .andExpect(status().isOk());
        mvc.perform(auth(get("/api/v1/teacher/children/" + childId + "/shared-summary"), teacher))
                .andExpect(jsonPath("$.sharedItems[0]").value("JOINT_PLAY"))
                .andExpect(jsonPath("$.weeks[0].jointPlayRatio").value(0.5))
                .andExpect(jsonPath("$.weeks[0].responseLatencySec").doesNotExist())
                .andExpect(jsonPath("$.weeks[0].distressPerTenMinutes").doesNotExist());
        mvc.perform(auth(get("/api/v1/children/" + childId + "/card-deliveries"), teacher))
                .andExpect(status().isOk());

        // 목표·메모
        long goalId = json(mvc.perform(body(auth(post("/api/v1/children/" + childId + "/goals"), teacher),
                        Map.of("title", "친구 이름 부르기", "description", "놀이 시간에 친구 이름을 불러 보기")))
                .andExpect(status().isCreated()).andReturn()).get("id").asLong();
        mvc.perform(body(auth(patch("/api/v1/children/" + childId + "/goals/" + goalId), teacher),
                        Map.of("status", "ACHIEVED")))
                .andExpect(jsonPath("$.status").value("ACHIEVED"));
        mvc.perform(body(auth(post("/api/v1/children/" + childId + "/observations"), teacher),
                        Map.of("content", "블록 놀이에 10분 넘게 집중했어요.", "observedOn", LocalDate.now().minusDays(1).toString())))
                .andExpect(status().isCreated());
        // 부모는 읽기만
        mvc.perform(auth(get("/api/v1/children/" + childId + "/goals"), parent))
                .andExpect(jsonPath("$[0].teacherName").value("김선생"));
        mvc.perform(auth(get("/api/v1/children/" + childId + "/observations"), parent))
                .andExpect(jsonPath("$.length()").value(1));
        mvc.perform(body(auth(post("/api/v1/children/" + childId + "/goals"), parent), Map.of("title", "x")))
                .andExpect(status().isNotFound());

        // 부모가 연결 해제 → 교사 접근 불가
        mvc.perform(auth(get("/api/v1/children/" + childId + "/teachers"), parent))
                .andExpect(jsonPath("$[0].teacherId").value(teacherId));
        mvc.perform(auth(delete("/api/v1/children/" + childId + "/teachers/" + teacherId), parent))
                .andExpect(status().isNoContent());
        mvc.perform(auth(get("/api/v1/children/" + childId + "/goals"), teacher)).andExpect(status().isNotFound());
        mvc.perform(auth(get("/api/v1/teacher/children/" + childId + "/shared-summary"), teacher))
                .andExpect(status().isNotFound());
    }

    @Test
    void refreshTokenRotationAndReuseDetection() throws Exception {
        JsonNode tokens = signup("PARENT");
        String r1 = tokens.get("refreshToken").asText();
        JsonNode next = json(mvc.perform(body(post("/api/v1/auth/refresh"), Map.of("refreshToken", r1)))
                .andExpect(status().isOk()).andReturn());
        String r2 = next.get("refreshToken").asText();
        assertThat(r2).isNotEqualTo(r1);
        // 이미 쓴 토큰 재사용 → 거부 + 모든 토큰 폐기
        mvc.perform(body(post("/api/v1/auth/refresh"), Map.of("refreshToken", r1))).andExpect(status().isUnauthorized());
        mvc.perform(body(post("/api/v1/auth/refresh"), Map.of("refreshToken", r2))).andExpect(status().isUnauthorized());
    }

    @Test
    void loginLocksAfterRepeatedFailures() throws Exception {
        JsonNode tokens = signup("PARENT");
        String email = tokens.at("/user/email").asText();
        for (int i = 0; i < 5; i++) {
            mvc.perform(body(post("/api/v1/auth/login"), Map.of("email", email, "password", "wrong123")))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(body(post("/api/v1/auth/login"), Map.of("email", email, "password", "abcd1234")))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void deletingChildAndAccountRemovesData() throws Exception {
        String parent = token(signup("PARENT"));
        long childId = createChild(parent);
        postRecord(parent, childId, metrics(UUID.randomUUID().toString(), Instant.now(), 120, 60, 1.2, 2), 201);
        mvc.perform(auth(delete("/api/v1/children/" + childId), parent)).andExpect(status().isNoContent());
        mvc.perform(auth(get("/api/v1/children/" + childId), parent)).andExpect(status().isNotFound());
        mvc.perform(auth(get("/api/v1/me/consents"), parent))
                .andExpect(jsonPath("$[?(@.type == 'CHILD_DATA_GUARDIAN')].revokedAt").isNotEmpty());

        long child2 = createChild(parent);
        postRecord(parent, child2, metrics(UUID.randomUUID().toString(), Instant.now(), 120, 60, 1.2, 2), 201);
        mvc.perform(body(auth(delete("/api/v1/me"), parent), Map.of("password", "wrong999")))
                .andExpect(status().isUnauthorized());
        mvc.perform(body(auth(delete("/api/v1/me"), parent), Map.of("password", "abcd1234")))
                .andExpect(status().isNoContent());
        // 탈퇴 직후 기존 액세스 토큰도 쓸 수 없다
        mvc.perform(auth(get("/api/v1/me"), parent)).andExpect(status().isUnauthorized());
    }
}
