package kr.hamkkejaram;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.hamkkejaram.aiobs.TestReceipts;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 통합 테스트 공통 도우미(가입·아이 등록·연결·기록 저장). 같은 설정이라 스프링 컨텍스트를 함께 쓴다. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
abstract class IntegrationTestSupport {
    private static final AtomicInteger SEQ = new AtomicInteger();
    static final String V = "2026-11-v1";
    static final String PASSWORD = "abcd1234";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;
    @Autowired JdbcTemplate jdbc;

    JsonNode json(MvcResult r) throws Exception {
        String body = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        return body.isEmpty() ? om.nullNode() : om.readTree(body);
    }

    MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder b, String token) {
        return b.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    MockHttpServletRequestBuilder body(MockHttpServletRequestBuilder b, Object o) throws Exception {
        return b.contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(o));
    }

    static String uniqueEmail(String prefix) {
        return prefix + SEQ.incrementAndGet() + "-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
    }

    Map<String, Object> signupRequest(String email, String password, String role) {
        return Map.of("email", email, "password", password,
                "displayName", role.equals("PARENT") ? "하늘맘" : "김선생", "role", role,
                "consents", new Object[]{
                        Map.of("type", "TERMS_OF_SERVICE", "version", V),
                        Map.of("type", "PRIVACY_POLICY", "version", V)});
    }

    JsonNode signup(String role) throws Exception {
        return json(mvc.perform(body(post("/api/v1/auth/signup"),
                        signupRequest(uniqueEmail(role.toLowerCase()), PASSWORD, role)))
                .andExpect(status().isCreated()).andReturn());
    }

    String token(JsonNode tokens) {
        return tokens.get("accessToken").asText();
    }

    long userId(JsonNode tokens) {
        return tokens.at("/user/id").asLong();
    }

    long createChild(String token) throws Exception {
        var req = Map.of("nickname", "하늘", "birthYearMonth", LocalDate.now().minusYears(3).toString().substring(0, 7),
                "guardianConsent", Map.of("agreed", true, "version", V));
        return json(mvc.perform(body(auth(post("/api/v1/children"), token), req))
                .andExpect(status().isCreated()).andReturn()).get("id").asLong();
    }

    String issueCode(String parent, long childId) throws Exception {
        return json(mvc.perform(auth(post("/api/v1/children/" + childId + "/invite-codes"), parent))
                .andExpect(status().isCreated()).andReturn()).get("code").asText();
    }

    void link(String parent, String teacher, long childId) throws Exception {
        mvc.perform(body(auth(post("/api/v1/teacher/links"), teacher), Map.of("code", issueCode(parent, childId))))
                .andExpect(status().isCreated());
    }

    Map<String, Object> metrics(Instant at) {
        var m = new HashMap<String, Object>();
        m.put("clientRecordId", UUID.randomUUID().toString());
        m.put("recordedAt", at.toString());
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

    long postRecord(String token, long childId, Instant at) throws Exception {
        return json(mvc.perform(body(auth(post("/api/v1/children/" + childId + "/interaction-records"), token),
                        metrics(at))).andExpect(status().isCreated()).andReturn()).at("/record/id").asLong();
    }

    /** AI 동의 → 분석 토큰 → (AI 서버 역할로 서명한) 영수증 저장. 저장된 관찰 기록 id를 돌려준다. */
    long saveAiObservation(String token, long userId, long childId) throws Exception {
        mvc.perform(body(auth(put("/api/v1/children/" + childId + "/ai-consent"), token),
                Map.of("agreed", true, "version", V))).andExpect(status().isOk());
        String session = json(mvc.perform(auth(post("/api/v1/children/" + childId + "/ai-sessions"), token))
                .andExpect(status().isCreated()).andReturn()).get("token").asText();
        Map<String, Object> claims = TestReceipts.claims(userId, childId, "HOME");
        claims.put("jti", TestReceipts.payload(session).get("jti").asText());
        return json(mvc.perform(body(auth(post("/api/v1/children/" + childId + "/ai-observations"), token),
                Map.of("receipt", TestReceipts.sign(claims)))).andExpect(status().isCreated()).andReturn())
                .at("/observation/id").asLong();
    }

    /** 목록의 id만 순서대로. */
    List<Long> ids(String token, String path) throws Exception {
        JsonNode list = json(mvc.perform(auth(get(path), token)).andExpect(status().isOk()).andReturn());
        List<Long> out = new ArrayList<>();
        list.forEach(n -> out.add(n.get("id").asLong()));
        return out;
    }

    int rows(String table, long childId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE child_id = ?", Integer.class, childId);
    }
}
