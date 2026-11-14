package kr.hamkkejaram;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.hamkkejaram.aiobs.TestReceipts;
import kr.hamkkejaram.consent.ConsentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 계정 기능: 표시명·비밀번호 변경(ACC-08·09), 재동의(ACC-10), 내 데이터 내려받기(ACC-11), 동의 기록 파기(ACC-12), 검증 문구. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AccountFeaturesIntegrationTest {
    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final String V = "2026-11-v1";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;
    @Autowired JdbcTemplate jdbc;
    @Autowired ConsentService consentService;

    // ---------- helpers ----------

    private JsonNode json(MvcResult r) throws Exception {
        String body = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        return body.isEmpty() ? om.nullNode() : om.readTree(body);
    }

    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder b, String token) {
        return b.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    private MockHttpServletRequestBuilder body(MockHttpServletRequestBuilder b, Object o) throws Exception {
        return b.contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(o));
    }

    private JsonNode signup(String role) throws Exception {
        String email = "acct-" + role.toLowerCase() + SEQ.incrementAndGet() + "@example.com";
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

    private void link(String parent, String teacher, long childId) throws Exception {
        String code = json(mvc.perform(auth(post("/api/v1/children/" + childId + "/invite-codes"), parent))
                .andExpect(status().isCreated()).andReturn()).get("code").asText();
        mvc.perform(body(auth(post("/api/v1/teacher/links"), teacher), Map.of("code", code)))
                .andExpect(status().isCreated());
    }

    private Map<String, Object> metrics() {
        var m = new java.util.HashMap<String, Object>();
        m.put("clientRecordId", UUID.randomUUID().toString());
        m.put("recordedAt", Instant.now().toString());
        m.put("analyzedSeconds", 120.0);
        m.put("jointPlaySeconds", 60.0);
        m.put("responseLatencySec", 1.2);
        m.put("childBidCount", 10);
        m.put("respondedBidCount", 8);
        m.put("distressEpisodeCount", 1);
        m.put("modelVersion", "pose_landmarker_lite+audio-heuristic-1");
        m.put("specVersion", "metrics-v1");
        return m;
    }

    /** AI 동의 → 분석 토큰 → 영수증 저장. 분석 토큰의 jti를 돌려준다(내려받기에 담기지 않아야 한다). */
    private String saveAiObservation(String token, long userId, long childId) throws Exception {
        mvc.perform(body(auth(put("/api/v1/children/" + childId + "/ai-consent"), token),
                Map.of("agreed", true, "version", V))).andExpect(status().isOk());
        String session = json(mvc.perform(auth(post("/api/v1/children/" + childId + "/ai-sessions"), token))
                .andExpect(status().isCreated()).andReturn()).get("token").asText();
        Map<String, Object> claims = TestReceipts.claims(userId, childId, "HOME");
        String jti = TestReceipts.payload(session).get("jti").asText();
        claims.put("jti", jti);
        mvc.perform(body(auth(post("/api/v1/children/" + childId + "/ai-observations"), token),
                Map.of("receipt", TestReceipts.sign(claims)))).andExpect(status().isCreated());
        return jti;
    }

    private long insertConsent(Long userId, Long childId, String type, Instant agreedAt, Instant revokedAt) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO consents (user_id, child_id, type, version, agreed_at, revoked_at) VALUES (?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setObject(1, userId);
            ps.setObject(2, childId);
            ps.setString(3, type);
            ps.setString(4, V);
            ps.setTimestamp(5, Timestamp.from(agreedAt));
            ps.setTimestamp(6, revokedAt == null ? null : Timestamp.from(revokedAt));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    private boolean consentExists(long id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM consents WHERE id = ?", Integer.class, id) == 1;
    }

    // ---------- 표시명 (ACC-08) ----------

    @Test
    void displayNameCanBeChangedAndIsTrimmed() throws Exception {
        JsonNode teacherTokens = signup("TEACHER");
        String teacher = token(teacherTokens);
        String parent = token(signup("PARENT"));
        long childId = createChild(parent);
        link(parent, teacher, childId);
        mvc.perform(body(auth(post("/api/v1/children/" + childId + "/goals"), teacher), Map.of("title", "인사하기")))
                .andExpect(status().isCreated());

        mvc.perform(body(auth(patch("/api/v1/me"), teacher), Map.of("displayName", "  이선생  ")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("이선생"))
                .andExpect(jsonPath("$.email").value(teacherTokens.at("/user/email").asText()))
                .andExpect(jsonPath("$.role").value("TEACHER"))
                .andExpect(jsonPath("$.pendingConsents.length()").value(0));
        mvc.perform(auth(get("/api/v1/me"), teacher)).andExpect(jsonPath("$.displayName").value("이선생"));
        // 부모 화면의 목표 작성자 이름도 바뀐다
        mvc.perform(auth(get("/api/v1/children/" + childId + "/goals"), parent))
                .andExpect(jsonPath("$[0].teacherName").value("이선생"));

        for (Object bad : new Object[]{"   ", "", "가".repeat(31), " " + "가".repeat(31) + " "}) {
            mvc.perform(body(auth(patch("/api/v1/me"), teacher), Map.of("displayName", bad)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        // 공백을 지우면 30자인 값은 받는다
        mvc.perform(body(auth(patch("/api/v1/me"), teacher), Map.of("displayName", "  " + "가".repeat(30) + "  ")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("가".repeat(30)));
        mvc.perform(patch("/api/v1/me").contentType(MediaType.APPLICATION_JSON).content("{\"displayName\":\"x\"}"))
                .andExpect(status().isUnauthorized());
    }

    // ---------- 비밀번호 (ACC-09) ----------

    @Test
    void passwordChangeRevokesOtherSessionsAndIssuesNewTokens() throws Exception {
        JsonNode first = signup("PARENT");
        String email = first.at("/user/email").asText();
        String access = token(first);
        // 다른 기기에서 한 로그인
        JsonNode otherDevice = json(mvc.perform(body(post("/api/v1/auth/login"),
                Map.of("email", email, "password", "abcd1234"))).andExpect(status().isOk()).andReturn());

        mvc.perform(body(auth(put("/api/v1/me/password"), access),
                        Map.of("currentPassword", "wrong123", "newPassword", "newpass99")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        mvc.perform(body(auth(put("/api/v1/me/password"), access),
                        Map.of("currentPassword", "abcd1234", "newPassword", "abcd1234")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(body(auth(put("/api/v1/me/password"), access),
                        Map.of("currentPassword", "abcd1234", "newPassword", "onlyletters")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("newPassword"))
                .andExpect(jsonPath("$.fieldErrors[0].message").value("영문과 숫자를 함께 넣어 주세요."));
        mvc.perform(body(auth(put("/api/v1/me/password"), access),
                        Map.of("currentPassword", "abcd1234", "newPassword", "a1")))
                .andExpect(status().isBadRequest());

        JsonNode changed = json(mvc.perform(body(auth(put("/api/v1/me/password"), access),
                        Map.of("currentPassword", "abcd1234", "newPassword", "newpass99")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.accessTokenExpiresIn").value(1800))
                .andExpect(jsonPath("$.user.email").value(email))
                .andExpect(jsonPath("$.user.pendingConsents.length()").value(0))
                .andReturn());

        // 예전 리프레시 토큰(가입·다른 기기)은 모두 쓸 수 없다
        for (JsonNode old : new JsonNode[]{first, otherDevice}) {
            mvc.perform(body(post("/api/v1/auth/refresh"), Map.of("refreshToken", old.get("refreshToken").asText())))
                    .andExpect(status().isUnauthorized());
        }
        // 새로 받은 토큰은 쓸 수 있다
        mvc.perform(auth(get("/api/v1/me"), token(changed))).andExpect(status().isOk());
        mvc.perform(body(post("/api/v1/auth/refresh"), Map.of("refreshToken", changed.get("refreshToken").asText())))
                .andExpect(status().isOk());
        // 예전 비밀번호로는 로그인할 수 없고 새 비밀번호로는 된다
        mvc.perform(body(post("/api/v1/auth/login"), Map.of("email", email, "password", "abcd1234")))
                .andExpect(status().isUnauthorized());
        mvc.perform(body(post("/api/v1/auth/login"), Map.of("email", email, "password", "newpass99")))
                .andExpect(status().isOk());
        mvc.perform(put("/api/v1/me/password").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"newpass99\",\"newPassword\":\"other123\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void repeatedWrongCurrentPasswordLocksLikeLogin() throws Exception {
        JsonNode tokens = signup("PARENT");
        String access = token(tokens);
        for (int i = 0; i < 5; i++) {
            mvc.perform(body(auth(put("/api/v1/me/password"), access),
                            Map.of("currentPassword", "wrong123", "newPassword", "newpass99")))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(body(auth(put("/api/v1/me/password"), access),
                        Map.of("currentPassword", "abcd1234", "newPassword", "newpass99")))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("TOO_MANY_ATTEMPTS"));
    }

    /** BCrypt 72바이트 상한: 64자 안이어도 한글이 많으면 500이 아니라 400으로 돌려준다(가입·비밀번호 변경 모두). */
    @Test
    void passwordOverBcryptByteLimitIsRejectedNot500() throws Exception {
        String longKorean = "가".repeat(30) + "a1"; // 32자, 92바이트
        String access = token(signup("PARENT"));
        mvc.perform(body(auth(put("/api/v1/me/password"), access),
                        Map.of("currentPassword", "abcd1234", "newPassword", longKorean)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        // 바뀌지 않았으므로 지금 비밀번호로 계속 바꿀 수 있다
        mvc.perform(body(auth(put("/api/v1/me/password"), access),
                        Map.of("currentPassword", "abcd1234", "newPassword", "newpass99")))
                .andExpect(status().isOk());

        var req = Map.of("email", "acct-long" + SEQ.incrementAndGet() + "@example.com", "password", longKorean,
                "displayName", "하늘맘", "role", "PARENT", "consents", new Object[]{
                        Map.of("type", "TERMS_OF_SERVICE", "version", V),
                        Map.of("type", "PRIVACY_POLICY", "version", V)});
        mvc.perform(body(post("/api/v1/auth/signup"), req))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    // ---------- 재동의 (ACC-10) ----------

    @Test
    void changedPolicyVersionAsksForReconsent() throws Exception {
        JsonNode tokens = signup("PARENT");
        String t = token(tokens);
        long userId = tokens.at("/user/id").asLong();
        assertThat(tokens.at("/user/pendingConsents").size()).isZero();
        mvc.perform(auth(get("/api/v1/me"), t)).andExpect(jsonPath("$.pendingConsents.length()").value(0));

        // 정책 버전이 바뀐 상황: 이 사람이 동의한 버전을 예전 버전으로 돌려 둔다
        jdbc.update("UPDATE consents SET version = '2026-10-v0' WHERE user_id = ? AND type IN ('TERMS_OF_SERVICE', 'PRIVACY_POLICY')",
                userId);
        mvc.perform(auth(get("/api/v1/me"), t))
                .andExpect(jsonPath("$.pendingConsents.length()").value(2))
                .andExpect(jsonPath("$.pendingConsents[0]").value("TERMS_OF_SERVICE"))
                .andExpect(jsonPath("$.pendingConsents[1]").value("PRIVACY_POLICY"));
        // 로그인 응답에도 담긴다
        mvc.perform(body(post("/api/v1/auth/login"),
                        Map.of("email", tokens.at("/user/email").asText(), "password", "abcd1234")))
                .andExpect(jsonPath("$.user.pendingConsents.length()").value(2));

        // 현재 버전이 아니면 400
        mvc.perform(body(auth(post("/api/v1/me/consents"), t), Map.of("type", "TERMS_OF_SERVICE", "version", "2026-10-v0")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        // 아이별 동의는 여기서 받지 않는다
        mvc.perform(body(auth(post("/api/v1/me/consents"), t), Map.of("type", "CHILD_DATA_GUARDIAN", "version", V)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(body(auth(post("/api/v1/me/consents"), t), Map.of("type", "NOPE", "version", V)))
                .andExpect(status().isBadRequest());

        mvc.perform(body(auth(post("/api/v1/me/consents"), t), Map.of("type", "TERMS_OF_SERVICE", "version", V)))
                .andExpect(status().isNoContent());
        mvc.perform(auth(get("/api/v1/me"), t))
                .andExpect(jsonPath("$.pendingConsents.length()").value(1))
                .andExpect(jsonPath("$.pendingConsents[0]").value("PRIVACY_POLICY"));
        mvc.perform(body(auth(post("/api/v1/me/consents"), t), Map.of("type", "PRIVACY_POLICY", "version", V)))
                .andExpect(status().isNoContent());
        // 같은 요청을 다시 보내도 기록이 늘지 않는다
        mvc.perform(body(auth(post("/api/v1/me/consents"), t), Map.of("type", "PRIVACY_POLICY", "version", V)))
                .andExpect(status().isNoContent());
        mvc.perform(auth(get("/api/v1/me"), t)).andExpect(jsonPath("$.pendingConsents.length()").value(0));
        // 이력: 예전 버전 2건 + 새 버전 2건
        mvc.perform(auth(get("/api/v1/me/consents"), t))
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[?(@.version == '2026-11-v1')]").isNotEmpty());
    }

    @Test
    void revokedConsentCountsAsPending() throws Exception {
        JsonNode tokens = signup("TEACHER");
        long userId = tokens.at("/user/id").asLong();
        jdbc.update("UPDATE consents SET revoked_at = agreed_at WHERE user_id = ? AND type = 'PRIVACY_POLICY'", userId);
        mvc.perform(auth(get("/api/v1/me"), token(tokens)))
                .andExpect(jsonPath("$.pendingConsents.length()").value(1))
                .andExpect(jsonPath("$.pendingConsents[0]").value("PRIVACY_POLICY"));
    }

    // ---------- 내 데이터 내려받기 (ACC-11) ----------

    @Test
    void parentExportContainsOwnDataWithoutSecrets() throws Exception {
        JsonNode parentTokens = signup("PARENT");
        String parent = token(parentTokens);
        long parentId = parentTokens.at("/user/id").asLong();
        JsonNode teacherTokens = signup("TEACHER");
        String teacher = token(teacherTokens);
        long childId = createChild(parent);
        mvc.perform(body(auth(post("/api/v1/children/" + childId + "/interaction-records"), parent), metrics()))
                .andExpect(status().isCreated());
        String jti = saveAiObservation(parent, parentId, childId);
        link(parent, teacher, childId);
        mvc.perform(body(auth(post("/api/v1/children/" + childId + "/goals"), teacher),
                Map.of("title", "친구 이름 부르기", "description", "놀이 시간에"))).andExpect(status().isCreated());
        mvc.perform(body(auth(post("/api/v1/children/" + childId + "/observations"), teacher),
                Map.of("content", "블록 놀이를 오래 했어요.", "observedOn", LocalDate.now().minusDays(1).toString())))
                .andExpect(status().isCreated());

        MvcResult r = mvc.perform(auth(get("/api/v1/me/export"), parent))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        org.hamcrest.Matchers.allOf(org.hamcrest.Matchers.containsString("attachment"),
                                org.hamcrest.Matchers.containsString("filename=\"hamkke-jaram-export.json\""))))
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE,
                        org.hamcrest.Matchers.startsWith(MediaType.APPLICATION_JSON_VALUE)))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, org.hamcrest.Matchers.containsString("no-store")))
                .andReturn();
        String raw = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode ex = om.readTree(raw);

        assertThat(ex.get("format").asText()).isEqualTo("hamkke-jaram-export-v1");
        assertThat(ex.at("/account/email").asText()).isEqualTo(parentTokens.at("/user/email").asText());
        assertThat(ex.at("/account/role").asText()).isEqualTo("PARENT");
        assertThat(ex.has("teacher")).isFalse();
        assertThat(ex.get("consents").size()).isGreaterThanOrEqualTo(4); // 약관·개인정보·법정대리인·AI
        JsonNode child = ex.at("/children/0");
        assertThat(child.get("id").asLong()).isEqualTo(childId);
        assertThat(child.get("nickname").asText()).isEqualTo("하늘");
        assertThat(child.at("/shareSettings/jointPlay").asBoolean()).isFalse();
        assertThat(child.get("interactionRecords").size()).isEqualTo(1);
        assertThat(child.get("cardDeliveries").size()).isEqualTo(2); // 지표 기록 1 + AI 관찰 기록 1
        assertThat(child.at("/cardDeliveries/0/cardTitle").asText()).isNotBlank();
        assertThat(child.get("aiObservations").size()).isEqualTo(1);
        assertThat(child.at("/aiObservations/0/source").asText()).isEqualTo("HOME");
        assertThat(child.get("goals").size()).isEqualTo(1);
        assertThat(child.at("/goals/0/teacherName").asText()).isEqualTo("김선생");
        assertThat(child.get("observationNotes").size()).isEqualTo(1);
        assertThat(child.at("/linkedTeachers/0/displayName").asText()).isEqualTo("김선생");
        assertThat(child.at("/linkedTeachers/0").has("email")).isFalse();

        // 비밀값·다른 사람의 연락처는 담기지 않는다
        assertThat(raw).doesNotContain("passwordHash", "password_hash", "$2a$", "refreshToken", "accessToken",
                "sessionJti", jti, teacherTokens.at("/user/email").asText(), "receipt");
    }

    @Test
    void teacherExportContainsLinksGoalsAndNotesTheyWrote() throws Exception {
        String parent = token(signup("PARENT"));
        JsonNode teacherTokens = signup("TEACHER");
        String teacher = token(teacherTokens);
        long childId = createChild(parent);
        link(parent, teacher, childId);
        mvc.perform(body(auth(post("/api/v1/children/" + childId + "/goals"), teacher), Map.of("title", "인사하기")))
                .andExpect(status().isCreated());
        mvc.perform(body(auth(post("/api/v1/children/" + childId + "/observations"), teacher),
                Map.of("content", "웃으며 인사했어요.", "observedOn", LocalDate.now().toString())))
                .andExpect(status().isCreated());

        JsonNode ex = json(mvc.perform(auth(get("/api/v1/me/export"), teacher)).andExpect(status().isOk()).andReturn());
        assertThat(ex.at("/account/role").asText()).isEqualTo("TEACHER");
        assertThat(ex.has("children")).isFalse();
        assertThat(ex.at("/teacher/links/0/childId").asLong()).isEqualTo(childId);
        assertThat(ex.at("/teacher/links/0/childNickname").asText()).isEqualTo("하늘");
        assertThat(ex.at("/teacher/goals/0/title").asText()).isEqualTo("인사하기");
        assertThat(ex.at("/teacher/goals/0/childId").asLong()).isEqualTo(childId);
        assertThat(ex.at("/teacher/observationNotes/0/content").asText()).isEqualTo("웃으며 인사했어요.");
    }

    @Test
    void exportIsRateLimitedPerUser() throws Exception {
        String t = token(signup("TEACHER"));
        for (int i = 0; i < 3; i++) {
            mvc.perform(auth(get("/api/v1/me/export"), t)).andExpect(status().isOk());
        }
        mvc.perform(auth(get("/api/v1/me/export"), t))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("TOO_MANY_ATTEMPTS"));
        // 다른 사람은 따로 센다
        mvc.perform(auth(get("/api/v1/me/export"), token(signup("TEACHER")))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/me/export")).andExpect(status().isUnauthorized());
    }

    // ---------- 동의 기록 보관·파기 (ACC-12) ----------

    @Test
    void orphanedConsentsArePurgedAfterRetention() throws Exception {
        long userId = signup("PARENT").at("/user/id").asLong();
        Instant now = Instant.now();
        Instant old = now.minus(consentService.retention()).minus(Duration.ofDays(1));
        Instant recent = now.minus(Duration.ofDays(30));

        long deletedAccountOld = insertConsent(null, null, "TERMS_OF_SERVICE", old.minusSeconds(60), old);
        long deletedChildOld = insertConsent(userId, null, "CHILD_DATA_GUARDIAN", old.minusSeconds(60), old);
        long deletedAccountRecent = insertConsent(null, null, "PRIVACY_POLICY", recent.minusSeconds(60), recent);
        long liveAccountOldRevoked = insertConsent(userId, null, "PRIVACY_POLICY", old.minusSeconds(60), old);
        long orphanNotRevoked = insertConsent(null, null, "TERMS_OF_SERVICE", old, null);

        int purged = consentService.purgeOrphanedConsents();

        assertThat(purged).isGreaterThanOrEqualTo(2);
        assertThat(consentExists(deletedAccountOld)).isFalse();
        assertThat(consentExists(deletedChildOld)).isFalse();
        assertThat(consentExists(deletedAccountRecent)).isTrue();   // 보관 기간 안
        assertThat(consentExists(liveAccountOldRevoked)).isTrue();  // 계정이 남아 있는 사람의 계정 단위 동의
        assertThat(consentExists(orphanNotRevoked)).isTrue();       // 철회 시각이 없으면 지우지 않는다
        assertThat(consentService.retention()).isEqualTo(Duration.ofDays(1095));
        jdbc.update("DELETE FROM consents WHERE id IN (?, ?, ?)", deletedAccountRecent, liveAccountOldRevoked,
                orphanNotRevoked);
    }

    // ---------- 검증 문구 ----------

    @Test
    void fieldErrorsAreKoreanSentencesEvenForEnglishBrowsers() throws Exception {
        String parent = token(signup("PARENT"));
        String teacher = token(signup("TEACHER"));
        long childId = createChild(parent);
        link(parent, teacher, childId);
        long goalId = json(mvc.perform(body(auth(post("/api/v1/children/" + childId + "/goals"), teacher),
                Map.of("title", "인사하기"))).andExpect(status().isCreated()).andReturn()).get("id").asLong();

        mvc.perform(body(auth(patch("/api/v1/children/" + childId + "/goals/" + goalId), teacher),
                        // 빈 문자열은 @Size와 @Pattern 두 위반이 함께 나와 순서가 정해져 있지 않다 → 길이만 넘는 값으로 본다
                        Map.of("title", "가".repeat(61))).header(HttpHeaders.ACCEPT_LANGUAGE, "en-US,en;q=0.9"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("title"))
                .andExpect(jsonPath("$.fieldErrors[0].message").value("목표 제목은 1~60자로 입력해 주세요."));
        mvc.perform(body(auth(post("/api/v1/children/" + childId + "/observations"), teacher),
                        Map.of("content", "가".repeat(1001), "observedOn", LocalDate.now().toString()))
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "en"))
                .andExpect(jsonPath("$.fieldErrors[0].message").value("관찰 메모는 1000자까지 입력할 수 있어요."));

        var signup = Map.of("email", "not-an-email", "password", "abcd1234", "displayName", "  ",
                "role", "PARENT", "consents", new Object[0]);
        JsonNode err = json(mvc.perform(body(post("/api/v1/auth/signup"), signup)
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "en"))
                .andExpect(status().isBadRequest()).andReturn());
        Map<String, String> byField = new java.util.HashMap<>();
        err.get("fieldErrors").forEach(f -> byField.put(f.get("field").asText(), f.get("message").asText()));
        assertThat(byField).containsEntry("email", "이메일 형식을 확인해 주세요.")
                .containsEntry("displayName", "이름을 입력해 주세요.");

        // DTO에 직접 적은 문구는 그대로
        mvc.perform(body(auth(patch("/api/v1/children/" + childId), parent), Map.of("birthYearMonth", "2023/05"))
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "en"))
                .andExpect(jsonPath("$.fieldErrors[0].message").value("생년월은 YYYY-MM 형식이에요."));
        // 숫자 범위
        var m = metrics();
        m.put("analyzedSeconds", 5.0);
        mvc.perform(body(auth(post("/api/v1/children/" + childId + "/interaction-records"), parent), m)
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "en"))
                .andExpect(jsonPath("$.fieldErrors[0].message").value("분석한 시간은 10.0 이상이어야 해요."));
    }
}
