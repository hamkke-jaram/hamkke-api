package kr.hamkkejaram.aiobs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
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

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AI 기능이 꺼져 있을 때(운영 기본값, AI 서버 미설정): 공유 비밀 없이도 기동하고,
 * AI 관련 요청만 503으로 답하며 지표 3개 흐름은 그대로 동작한다(docs/ai-observation.md 2장 7항).
 */
@SpringBootTest(properties = {"app.ai.enabled=false", "app.ai.shared-secret="})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AiDisabledIntegrationTest {
    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final String V = "2026-11-v1";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;
    @Autowired JdbcTemplate jdbc;

    private JsonNode json(MvcResult r) throws Exception {
        return om.readTree(r.getResponse().getContentAsString());
    }

    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder b, String token) {
        return b.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    private MockHttpServletRequestBuilder body(MockHttpServletRequestBuilder b, Object o) throws Exception {
        return b.contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(o));
    }

    private JsonNode signup() throws Exception {
        String email = "aioff-parent" + SEQ.incrementAndGet() + "@example.com";
        var req = Map.of("email", email, "password", "abcd1234", "displayName", "하늘맘", "role", "PARENT",
                "consents", new Object[]{
                        Map.of("type", "TERMS_OF_SERVICE", "version", V),
                        Map.of("type", "PRIVACY_POLICY", "version", V)});
        return json(mvc.perform(body(post("/api/v1/auth/signup"), req)).andExpect(status().isCreated()).andReturn());
    }

    private long createChild(String token) throws Exception {
        var req = Map.of("nickname", "하늘", "birthYearMonth", LocalDate.now().minusYears(3).toString().substring(0, 7),
                "guardianConsent", Map.of("agreed", true, "version", V));
        return json(mvc.perform(body(auth(post("/api/v1/children"), token), req))
                .andExpect(status().isCreated()).andReturn()).get("id").asLong();
    }

    @Test
    void policiesSayTheFeatureIsOff() throws Exception {
        mvc.perform(get("/api/v1/policies")).andExpect(status().isOk())
                .andExpect(jsonPath("$.aiObservationEnabled").value(false))
                .andExpect(jsonPath("$.aiAnalysisVersion").value(V));
    }

    @Test
    void aiRequestsAnswerUnavailableButTheRestKeepsWorking() throws Exception {
        JsonNode tokens = signup();
        String t = tokens.get("accessToken").asText();
        long userId = tokens.at("/user/id").asLong();
        long childId = createChild(t);
        String base = "/api/v1/children/" + childId;

        // 동의를 받지 않는다(쓸 수 없는 기능), 토큰도 영수증도 받지 않는다
        mvc.perform(body(auth(put(base + "/ai-consent"), t), Map.of("agreed", true, "version", V)))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("AI_UNAVAILABLE"));
        mvc.perform(auth(post(base + "/ai-sessions"), t))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("AI_UNAVAILABLE"));
        String receipt = TestReceipts.sign(TestReceipts.claims(userId, childId, "HOME"));
        mvc.perform(body(auth(post(base + "/ai-observations"), t), Map.of("receipt", receipt)))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("AI_UNAVAILABLE"));

        // 예전에 동의해 둔 아이여도 마찬가지다(기능을 껐다 켜는 운영 상황)
        jdbc.update("INSERT INTO consents (user_id, child_id, type, version, agreed_at) VALUES (?, ?, ?, ?, ?)",
                userId, childId, "CHILD_IMAGE_AI_ANALYSIS", V, java.sql.Timestamp.from(Instant.now()));
        mvc.perform(auth(get(base + "/ai-consent"), t)).andExpect(status().isOk())
                .andExpect(jsonPath("$.agreed").value(true));
        mvc.perform(auth(post(base + "/ai-sessions"), t)).andExpect(status().isServiceUnavailable());
        mvc.perform(body(auth(post(base + "/ai-observations"), t), Map.of("receipt", receipt)))
                .andExpect(status().isServiceUnavailable());

        // 조회와 철회(삭제)는 꺼져 있어도 된다
        mvc.perform(auth(get(base + "/ai-observations"), t)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        mvc.perform(auth(delete(base + "/ai-consent"), t)).andExpect(status().isNoContent());
        mvc.perform(auth(get(base + "/ai-consent"), t)).andExpect(jsonPath("$.agreed").value(false));

        // 지표 3개 흐름은 그대로: 기록 저장 + 카드
        Map<String, Object> m = new HashMap<>();
        m.put("clientRecordId", UUID.randomUUID().toString());
        m.put("recordedAt", Instant.now().toString());
        m.put("analyzedSeconds", 120);
        m.put("jointPlaySeconds", 60);
        m.put("responseLatencySec", 1.2);
        m.put("childBidCount", 10);
        m.put("respondedBidCount", 8);
        m.put("distressEpisodeCount", 1);
        m.put("modelVersion", "pose_landmarker_lite+audio-heuristic-1");
        m.put("specVersion", "metrics-v1");
        mvc.perform(body(auth(post(base + "/interaction-records"), t), m)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.coaching.delivery.card.sourceTitle").exists());
        mvc.perform(auth(get(base + "/share-settings"), t)).andExpect(status().isOk())
                .andExpect(jsonPath("$.institutionCapture").value(false));
    }
}
