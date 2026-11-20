package kr.hamkkejaram.aiobs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static kr.hamkkejaram.aiobs.TestReceipts.sign;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 선생님용 가정 장면 요약(아이 한 줄·어른 한 줄 + 태그)과 보호자의 "선생님에게 보내지 않기".
 * 영수증은 테스트가 공유 비밀로 직접 서명한다(AI 서버 역할).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TeacherSceneIntegrationTest {
    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final String V = "2026-11-v1";
    private static final String CHILD_LINE = "아이가 블록을 밀어내요.";
    private static final String PARENT_LINE = "어른이 곁에서 기다린 뒤 말을 걸어요.";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;

    private record Account(String token, long id) {}

    private record Family(Account parent, Account teacher, long childId) {}

    private JsonNode json(MvcResult r) throws Exception {
        String body = r.getResponse().getContentAsString();
        return body.isEmpty() ? om.nullNode() : om.readTree(body);
    }

    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder b, Account a) {
        return b.header(HttpHeaders.AUTHORIZATION, "Bearer " + a.token());
    }

    private MockHttpServletRequestBuilder body(MockHttpServletRequestBuilder b, Object o) throws Exception {
        return b.contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(o));
    }

    private Account signup(String role) throws Exception {
        String email = "scene-" + role.toLowerCase() + SEQ.incrementAndGet() + "@example.com";
        var req = Map.of("email", email, "password", "abcd1234", "displayName", role.equals("PARENT") ? "하늘맘" : "김선생",
                "role", role, "consents", new Object[]{
                        Map.of("type", "TERMS_OF_SERVICE", "version", V),
                        Map.of("type", "PRIVACY_POLICY", "version", V)});
        JsonNode tokens = json(mvc.perform(body(post("/api/v1/auth/signup"), req))
                .andExpect(status().isCreated()).andReturn());
        return new Account(tokens.get("accessToken").asText(), tokens.at("/user/id").asLong());
    }

    private String path(long childId, String tail) {
        return "/api/v1/children/" + childId + tail;
    }

    /** 보호자·아이·AI 동의·연결된 선생님. 공유 설정은 기본(모두 꺼짐). */
    private Family family() throws Exception {
        Account parent = signup("PARENT");
        Account teacher = signup("TEACHER");
        var child = Map.of("nickname", "하늘", "birthYearMonth", LocalDate.now().minusYears(3).toString().substring(0, 7),
                "guardianConsent", Map.of("agreed", true, "version", V));
        long childId = json(mvc.perform(body(auth(post("/api/v1/children"), parent), child))
                .andExpect(status().isCreated()).andReturn()).get("id").asLong();
        mvc.perform(body(auth(put(path(childId, "/ai-consent")), parent), Map.of("agreed", true, "version", V)))
                .andExpect(status().isOk());
        String code = json(mvc.perform(auth(post(path(childId, "/invite-codes")), parent))
                .andExpect(status().isCreated()).andReturn()).get("code").asText();
        mvc.perform(body(auth(post("/api/v1/teacher/links"), teacher), Map.of("code", code)))
                .andExpect(status().isCreated());
        return new Family(parent, teacher, childId);
    }

    private void shareAiObservations(Family f, boolean on) throws Exception {
        Map<String, Object> req = new HashMap<>(Map.of("jointPlay", false, "responseLatency", false,
                "distress", false, "coachingCards", false, "aiObservations", on));
        mvc.perform(body(auth(put(path(f.childId(), "/share-settings")), f.parent()), req)).andExpect(status().isOk());
    }

    /** 보호자가 촬영해 저장한 가정 기록 한 건. obs를 바꿔 쓸 수 있게 클레임을 돌려받아 고친다. */
    private MvcResult saveHome(Family f, Map<String, Object> obsOverrides) throws Exception {
        JsonNode session = json(mvc.perform(auth(post(path(f.childId(), "/ai-sessions")), f.parent()))
                .andExpect(status().isCreated()).andReturn());
        JsonNode t = TestReceipts.payload(session.get("token").asText());
        Map<String, Object> c = TestReceipts.claims(Long.parseLong(t.get("sub").asText()), t.get("cid").asLong(),
                t.get("src").asText());
        c.put("jti", t.get("jti").asText());
        @SuppressWarnings("unchecked")
        Map<String, Object> obs = new HashMap<>((Map<String, Object>) c.get("obs"));
        obs.putAll(obsOverrides);
        c.put("obs", obs);
        return mvc.perform(body(auth(post(path(f.childId(), "/ai-observations")), f.parent()),
                Map.of("receipt", sign(c)))).andReturn();
    }

    private long saveHomeOk(Family f, Map<String, Object> obsOverrides) throws Exception {
        MvcResult r = saveHome(f, obsOverrides);
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(201);
        return json(r).at("/observation/id").asLong();
    }

    private JsonNode scenes(Family f, Account who) throws Exception {
        return json(mvc.perform(auth(get(path(f.childId(), "/home-scenes")), who)).andExpect(status().isOk()).andReturn());
    }

    private JsonNode observations(Family f, Account who) throws Exception {
        return json(mvc.perform(auth(get(path(f.childId(), "/ai-observations")), who))
                .andExpect(status().isOk()).andReturn());
    }

    private void setHidden(Family f, long observationId, boolean hidden) throws Exception {
        mvc.perform(body(auth(put(path(f.childId(), "/ai-observations/" + observationId + "/teacher-visibility")),
                f.parent()), Map.of("hidden", hidden))).andExpect(status().isOk());
    }

    @Test
    void linesFromTheReceiptReachTheTeacherAsAReducedScene() throws Exception {
        Family f = family();
        long id = saveHomeOk(f, Map.of("childLine", CHILD_LINE, "parentLine", PARENT_LINE));

        // 보호자는 선생님에게 갈 모습을 미리 본다(검토 전 AI 원문은 보호자에게 보이지 않는 기존 규칙은 그대로).
        JsonNode preview = scenes(f, f.parent()).get(0);
        assertThat(preview.get("childLine").asText()).isEqualTo(CHILD_LINE);
        assertThat(preview.get("parentLine").asText()).isEqualTo(PARENT_LINE);
        assertThat(observations(f, f.parent()).get(0).get("hiddenFromTeacher").asBoolean()).isFalse();

        // 공유가 꺼져 있으면 선생님은 장면 목록을 열 수 없다(존재를 드러내지 않는 404).
        mvc.perform(auth(get(path(f.childId(), "/home-scenes")), f.teacher())).andExpect(status().isNotFound());

        shareAiObservations(f, true);
        JsonNode scene = scenes(f, f.teacher()).get(0);
        assertThat(scene.get("id").asLong()).isEqualTo(id);
        assertThat(scene.get("childLine").asText()).isEqualTo(CHILD_LINE);
        assertThat(scene.get("parentLine").asText()).isEqualTo(PARENT_LINE);
        assertThat(scene.get("childStates")).hasSize(2);
        assertThat(scene.get("observedOn").asText()).matches("\\d{4}-\\d{2}-\\d{2}");
        // 줄인 형태: 원본 요약·시각별 메모·사람 수·정확한 시각·모델 정보는 담지 않는다.
        assertThat(scene.has("summary")).isFalse();
        assertThat(scene.has("moments")).isFalse();
        assertThat(scene.has("peopleCount")).isFalse();
        assertThat(scene.has("recordedAt")).isFalse();
        assertThat(scene.has("modelVersion")).isFalse();

        // 공유를 끄면 바로 다시 404.
        shareAiObservations(f, false);
        mvc.perform(auth(get(path(f.childId(), "/home-scenes")), f.teacher())).andExpect(status().isNotFound());
    }

    @Test
    void hiddenRecordsDisappearFromEveryTeacherViewButStayForTheParent() throws Exception {
        Family f = family();
        shareAiObservations(f, true);
        long hidden = saveHomeOk(f, Map.of("childLine", CHILD_LINE));
        long shown = saveHomeOk(f, Map.of("childLine", CHILD_LINE));

        setHidden(f, hidden, true);

        assertThat(scenes(f, f.teacher()).findValues("id").stream().map(JsonNode::asLong).toList())
                .containsExactly(shown);
        assertThat(observations(f, f.teacher()).findValues("id").stream().map(JsonNode::asLong).toList())
                .doesNotContain(hidden);
        mvc.perform(auth(get(path(f.childId(), "/ai-observations/" + hidden + "/review")), f.teacher()))
                .andExpect(status().isNotFound());
        mvc.perform(auth(get(path(f.childId(), "/ai-observations/" + shown + "/review")), f.teacher()))
                .andExpect(status().isOk());

        // 보호자 미리보기에는 남고, 보내지 않기 표시가 보인다.
        assertThat(scenes(f, f.parent())).hasSize(2);
        for (JsonNode s : scenes(f, f.parent())) {
            assertThat(s.get("hiddenFromTeacher").asBoolean()).isEqualTo(s.get("id").asLong() == hidden);
        }
        JsonNode parentView = observations(f, f.parent());
        for (JsonNode o : parentView) {
            assertThat(o.get("hiddenFromTeacher").asBoolean()).isEqualTo(o.get("id").asLong() == hidden);
        }

        // 다시 보내면 선생님에게 돌아온다.
        setHidden(f, hidden, false);
        assertThat(scenes(f, f.teacher())).hasSize(2);
    }

    @Test
    void onlyTheParentCanHideAndOnlyHomeRecords() throws Exception {
        Family f = family();
        long id = saveHomeOk(f, Map.of());
        mvc.perform(body(auth(put(path(f.childId(), "/ai-observations/" + id + "/teacher-visibility")), f.teacher()),
                Map.of("hidden", true))).andExpect(status().is4xxClientError());
        mvc.perform(body(auth(put(path(f.childId(), "/ai-observations/999999999/teacher-visibility")), f.parent()),
                Map.of("hidden", true))).andExpect(status().isNotFound());
        mvc.perform(body(auth(put(path(f.childId(), "/ai-observations/" + id + "/teacher-visibility")), f.parent()),
                Map.of())).andExpect(status().isBadRequest());
    }

    @Test
    void linesThatFailTheChecksAreDroppedButTheRecordIsKept() throws Exception {
        Family f = family();
        record Case(String childLine, String parentLine) {}
        List<Case> cases = List.of(
                new Case("아이가 블록을 3개 쌓아요.", "어른이 침착하게 잘 대응해요."),  // 숫자, 평가
                new Case("블록이 바닥에 흩어져 있어요.", "아이가 어른 곁으로 가요."),    // 주인공으로 시작하지 않음
                new Case("아이가 블록을 쌓아요. 아이가 웃어요.", ""));                // 두 문장, 빈 줄
        for (Case c : cases) {
            saveHomeOk(f, Map.of("childLine", c.childLine(), "parentLine", c.parentLine()));
        }
        for (JsonNode o : scenes(f, f.parent())) {
            assertThat(o.get("childLine").isNull()).as(o.toString()).isTrue();
            assertThat(o.get("parentLine").isNull()).as(o.toString()).isTrue();
            assertThat(o.get("childStates")).isNotEmpty();
        }
    }

    @Test
    void parentLineIsIgnoredWhenOnlyOnePersonIsVisible() throws Exception {
        Family f = family();
        saveHomeOk(f, Map.of("childLine", CHILD_LINE, "parentLine", PARENT_LINE, "peopleCount", 1,
                "adultResponses", List.of()));
        JsonNode o = scenes(f, f.parent()).get(0);
        assertThat(o.get("childLine").asText()).isEqualTo(CHILD_LINE);
        assertThat(o.get("parentLine").isNull()).isTrue();
    }

    @Test
    void malformedLinesRejectTheWholeReceipt() throws Exception {
        Family f = family();
        String tooLong = "아이가 블록을 하나씩 들어 올려 바구니에 넣고 다시 꺼내 바닥에 줄지어 놓아요.";
        for (Map<String, Object> bad : List.<Map<String, Object>>of(
                Map.of("childLine", tooLong), Map.of("childLine", 42), Map.of("parentLine", List.of("어른이")))) {
            MvcResult r = saveHome(f, bad);
            assertThat(r.getResponse().getStatus()).as(bad.toString()).isEqualTo(400);
            assertThat(json(r).get("code").asText()).isEqualTo("AI_RECEIPT_INVALID");
        }
    }

    @Test
    void receiptsFromTheOlderAiServerStillWorkWithTagsOnly() throws Exception {
        Family f = family();
        shareAiObservations(f, true);
        saveHomeOk(f, Map.of());
        JsonNode scene = scenes(f, f.teacher()).get(0);
        assertThat(scene.get("childLine").isNull()).isTrue();
        assertThat(scene.get("parentLine").isNull()).isTrue();
        assertThat(scene.get("adultResponses")).isNotEmpty();
    }
}
