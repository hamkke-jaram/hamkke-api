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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static kr.hamkkejaram.aiobs.TestReceipts.moment;
import static kr.hamkkejaram.aiobs.TestReceipts.obs;
import static kr.hamkkejaram.aiobs.TestReceipts.sign;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AI 관찰 기록 전체 흐름 (docs/ai-observation.md 4장·7장).
 * 영수증은 테스트가 공유 비밀로 직접 서명한다(AI 서버 역할). 사진은 어디에도 오가지 않는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AiObservationFlowIntegrationTest {
    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final String V = "2026-11-v1";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;
    @Autowired JdbcTemplate jdbc;

    // ---------- helpers ----------

    private record Account(String token, long id) {}

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
        return signup(role, role.equals("PARENT") ? "하늘맘" : "김선생");
    }

    private Account signup(String role, String name) throws Exception {
        // 같은 테스트 DB를 쓰는 다른 통합 테스트와 이메일이 겹치지 않게 접두어를 붙인다.
        String email = "aio-" + role.toLowerCase() + SEQ.incrementAndGet() + "@example.com";
        var req = Map.of("email", email, "password", "abcd1234", "displayName", name, "role", role,
                "consents", new Object[]{
                        Map.of("type", "TERMS_OF_SERVICE", "version", V),
                        Map.of("type", "PRIVACY_POLICY", "version", V)});
        JsonNode tokens = json(mvc.perform(body(post("/api/v1/auth/signup"), req))
                .andExpect(status().isCreated()).andReturn());
        return new Account(tokens.get("accessToken").asText(), tokens.at("/user/id").asLong());
    }

    private long createChild(Account parent) throws Exception {
        var req = Map.of("nickname", "하늘", "birthYearMonth", LocalDate.now().minusYears(3).toString().substring(0, 7),
                "guardianConsent", Map.of("agreed", true, "version", V));
        return json(mvc.perform(body(auth(post("/api/v1/children"), parent), req))
                .andExpect(status().isCreated()).andReturn()).get("id").asLong();
    }

    private String path(long childId, String tail) {
        return "/api/v1/children/" + childId + tail;
    }

    private void agree(Account parent, long childId) throws Exception {
        mvc.perform(body(auth(put(path(childId, "/ai-consent")), parent), Map.of("agreed", true, "version", V)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.agreed").value(true));
    }

    private void link(Account parent, Account teacher, long childId) throws Exception {
        String code = json(mvc.perform(auth(post(path(childId, "/invite-codes")), parent))
                .andExpect(status().isCreated()).andReturn()).get("code").asText();
        mvc.perform(body(auth(post("/api/v1/teacher/links"), teacher), Map.of("code", code)))
                .andExpect(status().isCreated());
    }

    private void share(Account parent, long childId, Boolean aiObservations, Boolean institutionCapture)
            throws Exception {
        Map<String, Object> req = new HashMap<>(Map.of("jointPlay", false, "responseLatency", false,
                "distress", false, "coachingCards", false));
        if (aiObservations != null) req.put("aiObservations", aiObservations);
        if (institutionCapture != null) req.put("institutionCapture", institutionCapture);
        mvc.perform(body(auth(put(path(childId, "/share-settings")), parent), req)).andExpect(status().isOk());
    }

    /** 분석 토큰을 받는다(201). */
    private JsonNode session(Account who, long childId) throws Exception {
        return json(mvc.perform(auth(post(path(childId, "/ai-sessions")), who))
                .andExpect(status().isCreated()).andReturn());
    }

    /** AI 서버가 하듯, 분석 토큰의 값을 그대로 옮겨 영수증 클레임을 만든다. */
    private Map<String, Object> receiptFor(JsonNode session) {
        JsonNode t = TestReceipts.payload(session.get("token").asText());
        Map<String, Object> c = TestReceipts.claims(Long.parseLong(t.get("sub").asText()), t.get("cid").asLong(),
                t.get("src").asText());
        c.put("jti", t.get("jti").asText());
        return c;
    }

    private MvcResult postReceipt(Account who, long childId, String receipt) throws Exception {
        return mvc.perform(body(auth(post(path(childId, "/ai-observations")), who), Map.of("receipt", receipt)))
                .andReturn();
    }

    private JsonNode save(Account who, long childId, Map<String, Object> claims) throws Exception {
        MvcResult r = postReceipt(who, childId, sign(claims));
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(201);
        return json(r);
    }

    private void assertRejected(Account who, long childId, String receipt) throws Exception {
        MvcResult r = postReceipt(who, childId, receipt);
        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        assertThat(json(r).get("code").asText()).isEqualTo("AI_RECEIPT_INVALID");
    }

    private static List<String> texts(Iterable<JsonNode> nodes) {
        List<String> out = new java.util.ArrayList<>();
        nodes.forEach(n -> out.add(n.asText()));
        return out;
    }

    private int observationCount(long childId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM ai_observations WHERE child_id = ?", Integer.class, childId);
    }

    private int aiDeliveryCount(long childId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM card_deliveries WHERE child_id = ? AND ai_observation_id IS NOT NULL",
                Integer.class, childId);
    }

    // ---------- 공개 정보 ----------

    @Test
    void policiesExposeAiVersionAndAvailability() throws Exception {
        mvc.perform(get("/api/v1/policies")).andExpect(status().isOk())
                .andExpect(jsonPath("$.aiAnalysisVersion").value(V))
                .andExpect(jsonPath("$.aiObservationEnabled").value(true))
                .andExpect(jsonPath("$.termsVersion").value(V))
                .andExpect(jsonPath("$.disclaimer").exists());
    }

    @Test
    void seedHasAtLeastSixHardMomentCardsWithSources() {
        List<Map<String, Object>> cards = jdbc.queryForList(
                "SELECT * FROM coaching_cards WHERE metric_focus = 'HARD_MOMENT' AND active = TRUE");
        assertThat(cards).hasSizeGreaterThanOrEqualTo(6);
        for (Map<String, Object> c : cards) {
            assertThat((String) c.get("situation")).isIn("ANY", "STEADY");
            assertThat((String) c.get("evidence_tag")).startsWith("근거기반 실제: ");
            assertThat((String) c.get("source_title")).isNotBlank();
            assertThat((String) c.get("source_url")).startsWith("https://");
            assertThat((String) c.get("author")).isEqualTo("함께자람 콘텐츠팀(초안)");
            assertThat(c.get("reviewer")).isNull();
            assertThat((String) c.get("body")).endsWith("요."); // 해요체
        }
    }

    // ---------- 동의 ----------

    @Test
    void consentIsPerChildVersionedAndIdempotent() throws Exception {
        Account parent = signup("PARENT");
        long childId = createChild(parent);
        long sibling = createChild(parent);

        mvc.perform(auth(get(path(childId, "/ai-consent")), parent)).andExpect(status().isOk())
                .andExpect(jsonPath("$.agreed").value(false))
                .andExpect(jsonPath("$.version").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.agreedAt").value(org.hamcrest.Matchers.nullValue()));

        // 버전이 다르면 422, 동의에 체크하지 않으면 400
        mvc.perform(body(auth(put(path(childId, "/ai-consent")), parent), Map.of("agreed", true, "version", "old")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("AI_CONSENT_VERSION_MISMATCH"));
        mvc.perform(body(auth(put(path(childId, "/ai-consent")), parent), Map.of("agreed", false, "version", V)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(body(auth(put(path(childId, "/ai-consent")), parent), Map.of("version", V)))
                .andExpect(status().isBadRequest());
        mvc.perform(auth(get(path(childId, "/ai-consent")), parent)).andExpect(jsonPath("$.agreed").value(false));

        JsonNode first = json(mvc.perform(body(auth(put(path(childId, "/ai-consent")), parent),
                Map.of("agreed", true, "version", V))).andExpect(status().isOk()).andReturn());
        assertThat(first.get("agreed").asBoolean()).isTrue();
        assertThat(first.get("version").asText()).isEqualTo(V);
        assertThat(first.get("agreedAt").asText()).isNotBlank();

        // 다시 보내도 처음 동의한 시각 그대로, 기록은 한 줄
        JsonNode again = json(mvc.perform(body(auth(put(path(childId, "/ai-consent")), parent),
                Map.of("agreed", true, "version", V))).andExpect(status().isOk()).andReturn());
        assertThat(again.get("agreedAt").asText()).isEqualTo(first.get("agreedAt").asText());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM consents WHERE child_id = ? AND type = ?",
                Integer.class, childId, "CHILD_IMAGE_AI_ANALYSIS")).isEqualTo(1);

        // 아이마다 따로다
        mvc.perform(auth(get(path(sibling, "/ai-consent")), parent)).andExpect(jsonPath("$.agreed").value(false));
        mvc.perform(auth(post(path(sibling, "/ai-sessions")), parent)).andExpect(status().isUnprocessableEntity());

        // 내 동의 이력에 보인다
        mvc.perform(auth(get("/api/v1/me/consents"), parent))
                .andExpect(jsonPath("$[?(@.type == 'CHILD_IMAGE_AI_ANALYSIS')].childId").value((int) childId));
    }

    @Test
    void oldConsentVersionDoesNotAllowSendingPictures() throws Exception {
        Account parent = signup("PARENT");
        long childId = createChild(parent);
        agree(parent, childId);
        // 동의 문서가 바뀐 상황: 남아 있는 동의는 예전 버전이다
        jdbc.update("UPDATE consents SET version = '2025-01-v0' WHERE child_id = ? AND type = ?", childId,
                "CHILD_IMAGE_AI_ANALYSIS");
        mvc.perform(auth(get(path(childId, "/ai-consent")), parent))
                .andExpect(jsonPath("$.agreed").value(false))
                .andExpect(jsonPath("$.version").value("2025-01-v0"));
        mvc.perform(auth(post(path(childId, "/ai-sessions")), parent)).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("AI_CONSENT_REQUIRED"));
        // 새 버전에 다시 동의하면 된다(예전 동의에는 철회 시각이 남는다)
        agree(parent, childId);
        session(parent, childId);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM consents WHERE child_id = ? AND type = ? "
                + "AND revoked_at IS NULL", Integer.class, childId, "CHILD_IMAGE_AI_ANALYSIS")).isEqualTo(1);
    }

    // ---------- 가정 기록 ----------

    @Test
    void homeFlowConsentSessionObservationAndCard() throws Exception {
        Account parent = signup("PARENT");
        long childId = createChild(parent);

        // 동의 없이는 분석 토큰이 나오지 않는다 = 사진이 한 장도 나가지 않는다
        mvc.perform(auth(post(path(childId, "/ai-sessions")), parent)).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("AI_CONSENT_REQUIRED"));
        agree(parent, childId);

        // 분석 토큰: 계약의 클레임 그대로
        JsonNode session = session(parent, childId);
        assertThat(session.get("maxFrames").asInt()).isEqualTo(6);
        String token = session.get("token").asText();
        assertThat(TestReceipts.signatureMatches(token, TestReceipts.SECRET)).isTrue();
        JsonNode t = TestReceipts.payload(token);
        assertThat(t.get("iss").asText()).isEqualTo("hamkke-api");
        assertThat(t.get("aud").asText()).isEqualTo("hamkke-ai");
        assertThat(t.get("sub").asText()).isEqualTo(Long.toString(parent.id()));
        assertThat(t.get("cid").asLong()).isEqualTo(childId);
        assertThat(t.get("src").asText()).isEqualTo("HOME");
        assertThat(t.get("exp").asLong() - t.get("iat").asLong()).isEqualTo(600);
        assertThat(Instant.parse(session.get("expiresAt").asText()).getEpochSecond()).isEqualTo(t.get("exp").asLong());

        // 영수증으로 저장 → 관찰 기록 + 카드 1장
        Map<String, Object> claims = receiptFor(session);
        JsonNode saved = save(parent, childId, claims);
        JsonNode o = saved.get("observation");
        assertThat(o.get("id").asLong()).isPositive();
        assertThat(o.get("source").asText()).isEqualTo("HOME");
        assertThat(o.get("summary").asText()).startsWith("어른과 아이가 블록을");
        assertThat(texts(o.get("childStates"))).containsExactly("PLAYING_TOGETHER", "REFUSING");
        assertThat(texts(o.get("adultResponses"))).containsExactly("WAITING", "TALKING");
        assertThat(o.at("/moments/0/t").asDouble()).isEqualTo(12.5);
        assertThat(o.at("/moments/0/note").asText()).isEqualTo("아이가 블록을 밀어내요.");
        assertThat(o.get("peopleCount").asInt()).isEqualTo(2);
        assertThat(o.get("modelVersion").asText()).isEqualTo("qwen3.5:4b");
        assertThat(o.get("promptVersion").asText()).isEqualTo("obs-v1");
        assertThat(o.get("frameCount").asInt()).isEqualTo(6);
        assertThat(o.get("mine").asBoolean()).isTrue();
        assertThat(o.get("authorName").isNull()).isTrue();
        assertThat(o.get("recordedAt").asText()).isNotBlank();
        // '싫다고 표현해요' 태그가 있으므로 '힘든 순간 함께하기' 카드
        JsonNode card = saved.at("/coaching/delivery/card");
        assertThat(card.get("metricFocus").asText()).isEqualTo("HARD_MOMENT");
        assertThat(card.get("situation").asText()).isIn("ANY", "STEADY");
        assertThat(card.get("sourceTitle").asText()).isNotBlank();
        assertThat(card.get("evidenceTag").asText()).isNotBlank();
        assertThat(saved.at("/coaching/guidance").isNull()).isTrue();

        // 같은 영수증을 다시 쓰면 409
        MvcResult dup = postReceipt(parent, childId, sign(claims));
        assertThat(dup.getResponse().getStatus()).isEqualTo(409);
        assertThat(json(dup).get("code").asText()).isEqualTo("DUPLICATE_REQUEST");
        // 같은 jti로 내용만 바꾼 영수증도 마찬가지
        Map<String, Object> sameJti = receiptFor(session);
        sameJti.put("obs", obs("아이가 웃어요.", List.of("CALM"), List.of(), List.of(), 1));
        assertThat(postReceipt(parent, childId, sign(sameJti)).getResponse().getStatus()).isEqualTo(409);
        assertThat(observationCount(childId)).isEqualTo(1);

        // 목록과 카드 이력
        mvc.perform(auth(get(path(childId, "/ai-observations")), parent)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(o.get("id").asLong()))
                .andExpect(jsonPath("$[0].source").value("HOME"));
        mvc.perform(auth(get(path(childId, "/card-deliveries")), parent))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].card.metricFocus").value("HARD_MOMENT"));
        long deliveryId = saved.at("/coaching/delivery/id").asLong();
        mvc.perform(auth(post(path(childId, "/card-deliveries/" + deliveryId + "/tried")), parent))
                .andExpect(status().isOk()).andExpect(jsonPath("$.triedAt").exists());

        // 기록을 지우면 그 기록으로 전달된 카드 이력도 함께 지워진다
        mvc.perform(auth(delete(path(childId, "/ai-observations/" + o.get("id").asLong())), parent))
                .andExpect(status().isNoContent());
        mvc.perform(auth(get(path(childId, "/ai-observations")), parent)).andExpect(jsonPath("$.length()").value(0));
        assertThat(aiDeliveryCount(childId)).isZero();
        mvc.perform(auth(delete(path(childId, "/ai-observations/" + o.get("id").asLong())), parent))
                .andExpect(status().isNotFound());
    }

    @Test
    void cardFocusFollowsChildStateTagsAndSkipsRecentCards() throws Exception {
        Account parent = signup("PARENT");
        long childId = createChild(parent);
        agree(parent, childId);

        // 힘든 순간 태그 → HARD_MOMENT. 네 번 받아도 최근 3장과 겹치지 않는다.
        java.util.Set<Long> hardCards = new java.util.LinkedHashSet<>();
        for (int i = 0; i < 4; i++) {
            Map<String, Object> c = receiptFor(session(parent, childId));
            c.put("obs", obs("아이가 울고 있고 어른이 곁에 앉아 있어요.", List.of("UPSET_CRYING"), List.of("COMFORTING"),
                    List.of(), 2));
            JsonNode card = save(parent, childId, c).at("/coaching/delivery/card");
            assertThat(card.get("metricFocus").asText()).isEqualTo("HARD_MOMENT");
            hardCards.add(card.get("id").asLong());
        }
        assertThat(hardCards).hasSize(4);

        // 혼자 놀아요 → 함께 놀기
        Map<String, Object> alone = receiptFor(session(parent, childId));
        alone.put("obs", obs("아이가 혼자 자동차를 굴리고 있어요.", List.of("PLAYING_ALONE", "FOCUSED"), List.of(),
                List.of(), 1));
        JsonNode aloneCard = save(parent, childId, alone).at("/coaching/delivery/card");
        assertThat(aloneCard.get("metricFocus").asText()).isEqualTo("JOINT_PLAY");
        assertThat(aloneCard.get("situation").asText()).isIn("ANY", "STEADY");

        // 어른에게 다가가요 → 반응
        Map<String, Object> seeking = receiptFor(session(parent, childId));
        seeking.put("obs", obs("아이가 어른에게 다가가 손을 내밀어요.", List.of("SEEKING_ADULT"), List.of("TALKING"),
                List.of(), 2));
        assertThat(save(parent, childId, seeking).at("/coaching/delivery/card/metricFocus").asText())
                .isEqualTo("RESPONSE");

        // 그 밖에는 GENERAL
        Map<String, Object> calm = receiptFor(session(parent, childId));
        calm.put("obs", obs("어른과 아이가 함께 블록을 쌓고 있어요.", List.of("PLAYING_TOGETHER", "CALM"),
                List.of("JOINING_PLAY"), List.of(), 2));
        JsonNode calmCard = save(parent, childId, calm).at("/coaching/delivery/card");
        assertThat(calmCard.get("metricFocus").asText()).isEqualTo("GENERAL");
        assertThat(calmCard.get("situation").asText()).isIn("ANY", "STEADY");

        // 목록은 최신순
        JsonNode list = json(mvc.perform(auth(get(path(childId, "/ai-observations?limit=3")), parent))
                .andExpect(status().isOk()).andReturn());
        assertThat(list).hasSize(3);
        assertThat(list.get(0).at("/childStates/0").asText()).isEqualTo("PLAYING_TOGETHER");
        assertThat(aiDeliveryCount(childId)).isEqualTo(7);
    }

    @Test
    void recordedAtIsOptionalAndCannotBeInTheFuture() throws Exception {
        Account parent = signup("PARENT");
        long childId = createChild(parent);
        agree(parent, childId);

        Instant yesterday = Instant.now().minus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        String receipt = sign(receiptFor(session(parent, childId)));
        mvc.perform(body(auth(post(path(childId, "/ai-observations")), parent),
                        Map.of("receipt", receipt, "recordedAt", yesterday.toString())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.observation.recordedAt").value(yesterday.toString()));

        String next = sign(receiptFor(session(parent, childId)));
        mvc.perform(body(auth(post(path(childId, "/ai-observations")), parent),
                        Map.of("receipt", next, "recordedAt", Instant.now().plus(1, ChronoUnit.DAYS).toString())))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        // 거절된 영수증은 쓰이지 않았으므로 시각을 고쳐 다시 보낼 수 있다
        mvc.perform(body(auth(post(path(childId, "/ai-observations")), parent), Map.of("receipt", next)))
                .andExpect(status().isCreated());

        mvc.perform(body(auth(post(path(childId, "/ai-observations")), parent), Map.of("recordedAt", yesterday.toString())))
                .andExpect(status().isBadRequest());
        mvc.perform(body(auth(post(path(childId, "/ai-observations")), parent), Map.of("receipt", "")))
                .andExpect(status().isBadRequest());
    }

    // ---------- 영수증 검증 ----------

    @Test
    void receiptsThatDoNotMatchAreRejected() throws Exception {
        Account parent = signup("PARENT");
        Account otherParent = signup("PARENT");
        long childId = createChild(parent);
        long sibling = createChild(parent);
        long otherChild = createChild(otherParent);
        agree(parent, childId);
        agree(parent, sibling);
        agree(otherParent, otherChild);
        JsonNode session = session(parent, childId);

        // 다른 아이의 영수증(cid 불일치): 내 다른 아이여도 안 된다
        assertRejected(parent, sibling, sign(receiptFor(session)));
        // 다른 사람의 영수증(sub 불일치)
        Map<String, Object> c = receiptFor(session);
        c.put("sub", Long.toString(otherParent.id()));
        assertRejected(parent, childId, sign(c));
        // 대상(aud)·발급자(iss) 불일치
        c = receiptFor(session);
        c.put("aud", "hamkke-ai");
        assertRejected(parent, childId, sign(c));
        c = receiptFor(session);
        c.put("iss", "someone-else");
        assertRejected(parent, childId, sign(c));
        // 만료
        c = receiptFor(session);
        c.put("iat", Instant.now().getEpochSecond() - 3600);
        c.put("exp", Instant.now().getEpochSecond() - 1800);
        assertRejected(parent, childId, sign(c));
        // 위조 서명·서명 없음·형식 오류
        assertRejected(parent, childId, sign(receiptFor(session), "forged-secret-forged-secret-forged-secret-01"));
        assertRejected(parent, childId, TestReceipts.unsigned(receiptFor(session)));
        assertRejected(parent, childId, "not.a.receipt");
        // 분석 토큰 자체를 영수증처럼 보내기
        assertRejected(parent, childId, session.get("token").asText());
        // 보호자가 기관 기록 영수증을 보내기(src 불일치)
        c = receiptFor(session);
        c.put("src", "INSTITUTION");
        assertRejected(parent, childId, sign(c));
        // 약속과 다른 관찰 기록 객체
        c = receiptFor(session);
        c.put("obs", obs("아이가 웃어요.", List.of("SLEEPING"), List.of(), List.of(), 1));
        assertRejected(parent, childId, sign(c));
        c = receiptFor(session);
        c.put("obs", obs("아이가 웃어요.", List.of("CALM"), List.of(), List.of(), 7));
        assertRejected(parent, childId, sign(c));
        c = receiptFor(session);
        c.put("fc", 9);
        assertRejected(parent, childId, sign(c));
        c = receiptFor(session);
        c.remove("obs");
        assertRejected(parent, childId, sign(c));

        // 아무것도 저장되지 않았고, 거절된 뒤에도 올바른 영수증은 받는다
        assertThat(observationCount(childId)).isZero();
        assertThat(observationCount(sibling)).isZero();
        save(parent, childId, receiptFor(session));
        assertThat(observationCount(childId)).isEqualTo(1);
    }

    @Test
    void sentenceFilterDropsOffendingTextBeforeSaving() throws Exception {
        Account parent = signup("PARENT");
        long childId = createChild(parent);
        agree(parent, childId);

        String forbidden = ObservationSentenceFilter.forbiddenExamples().get(0);
        Map<String, Object> c = receiptFor(session(parent, childId));
        c.put("obs", obs("아이가 블록을 던져요. " + forbidden + " 같아 보여요.", List.of("THROWING_OR_HITTING"),
                List.of("SETTING_LIMIT"),
                List.of(moment(5, "아이가 블록을 던져요."), moment(20, "아이에게 문제가 있어 보여요."),
                        moment(35, "이럴 때는 안아 주세요. 꼭 그렇게 하세요."), moment(50, "어른이 블록을 주워요.")), 2));
        JsonNode o = save(parent, childId, c).get("observation");
        assertThat(o.get("summary").isNull()).isTrue();
        assertThat(texts(o.get("moments").findValues("note")))
                .containsExactly("아이가 블록을 던져요.", "어른이 블록을 주워요.");
        assertThat(texts(o.get("childStates"))).containsExactly("THROWING_OR_HITTING");

        // DB에도 걸린 문장은 없다
        Map<String, Object> row = jdbc.queryForMap("SELECT summary, moments FROM ai_observations WHERE id = ?",
                o.get("id").asLong());
        assertThat(row.get("summary")).isNull();
        assertThat((String) row.get("moments")).doesNotContain(forbidden).doesNotContain("문제").doesNotContain("하세요");

        // 문장이 모두 버려지고 태그도 없으면 남길 것이 없으므로 저장하지 않는다
        Map<String, Object> empty = receiptFor(session(parent, childId));
        empty.put("obs", obs("아이가 산만해 보여요.", List.of(), List.of(), List.of(moment(1, "주의가 부족해요.")), 1));
        assertRejected(parent, childId, sign(empty));
    }

    // ---------- 기관 기록 ----------

    @Test
    void institutionCaptureNeedsLinkShareSettingAndConsent() throws Exception {
        Account parent = signup("PARENT");
        Account teacher = signup("TEACHER");
        Account stranger = signup("TEACHER", "박선생");
        long childId = createChild(parent);

        // 연결 전: 아무것도 안 된다
        mvc.perform(auth(post(path(childId, "/ai-sessions")), teacher)).andExpect(status().isNotFound());
        mvc.perform(auth(get(path(childId, "/ai-observations")), teacher)).andExpect(status().isNotFound());
        link(parent, teacher, childId);

        // 연결만으로는 안 된다(존재를 드러내지 않도록 404)
        mvc.perform(auth(post(path(childId, "/ai-sessions")), teacher)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        // 동의만 있어도, '기관 촬영'만 켜져 있어도 안 된다
        agree(parent, childId);
        mvc.perform(auth(post(path(childId, "/ai-sessions")), teacher)).andExpect(status().isNotFound());
        mvc.perform(auth(delete(path(childId, "/ai-consent")), parent)).andExpect(status().isNoContent());
        share(parent, childId, null, true);
        mvc.perform(auth(post(path(childId, "/ai-sessions")), teacher)).andExpect(status().isNotFound());
        mvc.perform(auth(get("/api/v1/teacher/children/" + childId + "/shared-summary"), teacher))
                .andExpect(jsonPath("$.sharedItems.length()").value(0));

        // 둘 다 있으면 된다
        agree(parent, childId);
        mvc.perform(auth(get("/api/v1/teacher/children/" + childId + "/shared-summary"), teacher))
                .andExpect(jsonPath("$.sharedItems.length()").value(1))
                .andExpect(jsonPath("$.sharedItems[0]").value("INSTITUTION_CAPTURE"));
        JsonNode session = session(teacher, childId);
        JsonNode t = TestReceipts.payload(session.get("token").asText());
        assertThat(t.get("src").asText()).isEqualTo("INSTITUTION");
        assertThat(t.get("sub").asText()).isEqualTo(Long.toString(teacher.id()));

        // 기관 기록에는 카드가 없다
        JsonNode saved = save(teacher, childId, receiptFor(session));
        assertThat(saved.at("/observation/source").asText()).isEqualTo("INSTITUTION");
        assertThat(saved.at("/observation/authorName").asText()).isEqualTo("김선생");
        assertThat(saved.at("/observation/mine").asBoolean()).isTrue();
        assertThat(saved.get("coaching").isNull()).isTrue();
        assertThat(aiDeliveryCount(childId)).isZero();
        long institutionObs = saved.at("/observation/id").asLong();

        // 선생님이 가정 기록 영수증을 보낼 수는 없다
        Map<String, Object> asHome = receiptFor(session(teacher, childId));
        asHome.put("src", "HOME");
        assertRejected(teacher, childId, sign(asHome));
        // 보호자의 분석 토큰으로 만든 영수증을 선생님이 저장할 수도 없다
        assertRejected(teacher, childId, sign(receiptFor(session(parent, childId))));

        // 연결되지 않은 선생님은 볼 수도 만들 수도 없다
        mvc.perform(auth(post(path(childId, "/ai-sessions")), stranger)).andExpect(status().isNotFound());
        mvc.perform(auth(get(path(childId, "/ai-observations")), stranger)).andExpect(status().isNotFound());
        assertThat(postReceipt(stranger, childId, sign(receiptFor(session))).getResponse().getStatus()).isEqualTo(404);

        // 보호자가 '기관 촬영'을 끄면 바로 막힌다(이미 받은 영수증도 저장되지 않는다)
        Map<String, Object> pending = receiptFor(session(teacher, childId));
        share(parent, childId, null, false);
        mvc.perform(auth(post(path(childId, "/ai-sessions")), teacher)).andExpect(status().isNotFound());
        assertThat(postReceipt(teacher, childId, sign(pending)).getResponse().getStatus()).isEqualTo(404);
        // 만들어 둔 기관 기록은 계속 보인다
        mvc.perform(auth(get(path(childId, "/ai-observations")), teacher)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(institutionObs));
        // 보호자도 본다(누가 남겼는지와 함께)
        mvc.perform(auth(get(path(childId, "/ai-observations")), parent))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].source").value("INSTITUTION"))
                .andExpect(jsonPath("$[0].authorName").value("김선생"))
                .andExpect(jsonPath("$[0].mine").value(false));
    }

    @Test
    void teacherSeesHomeObservationsOnlyWhenSharedAndDeletesOnlyOwn() throws Exception {
        Account parent = signup("PARENT");
        Account teacher = signup("TEACHER");
        Account teacher2 = signup("TEACHER", "박선생");
        long childId = createChild(parent);
        link(parent, teacher, childId);
        link(parent, teacher2, childId);
        agree(parent, childId);
        share(parent, childId, false, true);

        long homeObs = save(parent, childId, receiptFor(session(parent, childId))).at("/observation/id").asLong();
        long instObs = save(teacher, childId, receiptFor(session(teacher, childId))).at("/observation/id").asLong();

        // 가정 기록 공유가 꺼져 있으면 선생님은 기관 기록만 본다
        mvc.perform(auth(get(path(childId, "/ai-observations")), teacher)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(instObs))
                .andExpect(jsonPath("$[0].source").value("INSTITUTION"));
        mvc.perform(auth(get("/api/v1/teacher/children/" + childId + "/shared-summary"), teacher))
                .andExpect(jsonPath("$.sharedItems.length()").value(1))
                .andExpect(jsonPath("$.sharedItems[0]").value("INSTITUTION_CAPTURE"));
        // 보호자는 전부 본다
        mvc.perform(auth(get(path(childId, "/ai-observations")), parent)).andExpect(jsonPath("$.length()").value(2));

        // 보호자가 'AI 관찰 기록' 공유를 켜면 가정 기록도 보인다(보호자 이름은 싣지 않는다)
        share(parent, childId, true, null);
        JsonNode list = json(mvc.perform(auth(get(path(childId, "/ai-observations")), teacher))
                .andExpect(status().isOk()).andReturn());
        assertThat(list).hasSize(2);
        JsonNode home = list.get(0).get("id").asLong() == homeObs ? list.get(0) : list.get(1);
        assertThat(home.get("source").asText()).isEqualTo("HOME");
        assertThat(home.get("authorName").isNull()).isTrue();
        assertThat(home.get("mine").asBoolean()).isFalse();
        mvc.perform(auth(get("/api/v1/teacher/children/" + childId + "/shared-summary"), teacher))
                .andExpect(jsonPath("$.sharedItems[0]").value("AI_OBSERVATIONS"))
                .andExpect(jsonPath("$.sharedItems[1]").value("INSTITUTION_CAPTURE"));

        // 선생님은 자기가 만든 기록만 지운다
        mvc.perform(auth(delete(path(childId, "/ai-observations/" + homeObs)), teacher))
                .andExpect(status().isNotFound());
        mvc.perform(auth(delete(path(childId, "/ai-observations/" + instObs)), teacher2))
                .andExpect(status().isNotFound());
        assertThat(observationCount(childId)).isEqualTo(2);
        mvc.perform(auth(delete(path(childId, "/ai-observations/" + instObs)), teacher))
                .andExpect(status().isNoContent());

        // 보호자는 기관 기록도 지울 수 있다
        long instObs2 = save(teacher2, childId, receiptFor(session(teacher2, childId))).at("/observation/id").asLong();
        mvc.perform(auth(delete(path(childId, "/ai-observations/" + instObs2)), parent))
                .andExpect(status().isNoContent());
        // 다시 끄면 가정 기록은 선생님에게 보이지 않는다
        share(parent, childId, false, null);
        mvc.perform(auth(get(path(childId, "/ai-observations")), teacher)).andExpect(jsonPath("$.length()").value(0));
        assertThat(observationCount(childId)).isEqualTo(1);
    }

    @Test
    void institutionRecordSurvivesTeacherAccountDeletion() throws Exception {
        Account parent = signup("PARENT");
        Account teacher = signup("TEACHER");
        long childId = createChild(parent);
        link(parent, teacher, childId);
        agree(parent, childId);
        share(parent, childId, null, true);
        save(teacher, childId, receiptFor(session(teacher, childId)));

        mvc.perform(body(auth(delete("/api/v1/me"), teacher), Map.of("password", "abcd1234")))
                .andExpect(status().isNoContent());
        // 아이의 기록은 남고, 만든 사람 표시만 비워진다
        mvc.perform(auth(get(path(childId, "/ai-observations")), parent)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].source").value("INSTITUTION"))
                .andExpect(jsonPath("$[0].authorName").value(org.hamcrest.Matchers.nullValue()));
    }

    // ---------- 공유 설정 ----------

    @Test
    void shareSettingsKeepAiFieldsWhenOmitted() throws Exception {
        Account parent = signup("PARENT");
        long childId = createChild(parent);
        // 기본값은 꺼짐
        mvc.perform(auth(get(path(childId, "/share-settings")), parent)).andExpect(status().isOk())
                .andExpect(jsonPath("$.aiObservations").value(false))
                .andExpect(jsonPath("$.institutionCapture").value(false));

        // 두 항목을 모르는 예전 화면의 요청(네 값만)도 그대로 받는다
        var legacy = Map.of("jointPlay", true, "responseLatency", false, "distress", false, "coachingCards", true);
        mvc.perform(body(auth(put(path(childId, "/share-settings")), parent), legacy)).andExpect(status().isOk())
                .andExpect(jsonPath("$.jointPlay").value(true))
                .andExpect(jsonPath("$.aiObservations").value(false))
                .andExpect(jsonPath("$.institutionCapture").value(false));

        share(parent, childId, true, true);
        // 생략하면 지금 값 그대로
        mvc.perform(body(auth(put(path(childId, "/share-settings")), parent), legacy)).andExpect(status().isOk())
                .andExpect(jsonPath("$.aiObservations").value(true))
                .andExpect(jsonPath("$.institutionCapture").value(true))
                .andExpect(jsonPath("$.coachingCards").value(true));
        // 하나만 보내면 그것만 바뀐다
        share(parent, childId, false, null);
        mvc.perform(auth(get(path(childId, "/share-settings")), parent))
                .andExpect(jsonPath("$.aiObservations").value(false))
                .andExpect(jsonPath("$.institutionCapture").value(true));
    }

    // ---------- 철회·삭제 ----------

    @Test
    void revokingConsentDeletesEveryAiObservationOfThatChild() throws Exception {
        Account parent = signup("PARENT");
        Account teacher = signup("TEACHER");
        long childId = createChild(parent);
        long sibling = createChild(parent);
        link(parent, teacher, childId);
        agree(parent, childId);
        agree(parent, sibling);
        share(parent, childId, true, true);

        save(parent, childId, receiptFor(session(parent, childId)));
        save(parent, childId, receiptFor(session(parent, childId)));
        save(teacher, childId, receiptFor(session(teacher, childId)));
        save(parent, sibling, receiptFor(session(parent, sibling)));
        Map<String, Object> pending = receiptFor(session(parent, childId)); // 분석은 끝났지만 아직 저장하지 않은 영수증
        assertThat(observationCount(childId)).isEqualTo(3);
        assertThat(aiDeliveryCount(childId)).isEqualTo(2);

        // 선생님은 철회할 수 없다
        mvc.perform(auth(delete(path(childId, "/ai-consent")), teacher)).andExpect(status().isNotFound());
        assertThat(observationCount(childId)).isEqualTo(3);

        mvc.perform(auth(delete(path(childId, "/ai-consent")), parent)).andExpect(status().isNoContent());

        // 그 아이의 관찰 기록(가정·기관)과 그 기록으로 전달된 카드 이력이 모두 지워진다
        assertThat(observationCount(childId)).isZero();
        assertThat(aiDeliveryCount(childId)).isZero();
        mvc.perform(auth(get(path(childId, "/ai-observations")), parent)).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(auth(get(path(childId, "/ai-observations")), teacher)).andExpect(jsonPath("$.length()").value(0));
        // 다른 아이의 기록은 그대로
        assertThat(observationCount(sibling)).isEqualTo(1);
        assertThat(aiDeliveryCount(sibling)).isEqualTo(1);

        // 동의에는 철회 시각이 남고, AI 관련 공유 설정은 꺼진다
        mvc.perform(auth(get(path(childId, "/ai-consent")), parent)).andExpect(jsonPath("$.agreed").value(false));
        mvc.perform(auth(get("/api/v1/me/consents"), parent))
                .andExpect(jsonPath("$[?(@.type == 'CHILD_IMAGE_AI_ANALYSIS' && @.childId == " + childId
                        + ")].revokedAt").isNotEmpty());
        mvc.perform(auth(get(path(childId, "/share-settings")), parent))
                .andExpect(jsonPath("$.aiObservations").value(false))
                .andExpect(jsonPath("$.institutionCapture").value(false));

        // 철회 뒤에는 토큰도, 미리 받아 둔 영수증도 쓸 수 없다
        mvc.perform(auth(post(path(childId, "/ai-sessions")), parent)).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("AI_CONSENT_REQUIRED"));
        MvcResult late = postReceipt(parent, childId, sign(pending));
        assertThat(late.getResponse().getStatus()).isEqualTo(422);
        assertThat(json(late).get("code").asText()).isEqualTo("AI_CONSENT_REQUIRED");
        mvc.perform(auth(post(path(childId, "/ai-sessions")), teacher)).andExpect(status().isNotFound());
        assertThat(observationCount(childId)).isZero();

        // 여러 번 철회해도 된다
        mvc.perform(auth(delete(path(childId, "/ai-consent")), parent)).andExpect(status().isNoContent());
    }

    @Test
    void deletingChildOrAccountCascadesToAiObservations() throws Exception {
        Account parent = signup("PARENT");
        long childId = createChild(parent);
        long child2 = createChild(parent);
        agree(parent, childId);
        agree(parent, child2);
        save(parent, childId, receiptFor(session(parent, childId)));
        save(parent, child2, receiptFor(session(parent, child2)));

        mvc.perform(auth(delete("/api/v1/children/" + childId), parent)).andExpect(status().isNoContent());
        assertThat(observationCount(childId)).isZero();
        assertThat(aiDeliveryCount(childId)).isZero();
        assertThat(observationCount(child2)).isEqualTo(1);
        // 아이에게 걸린 AI 동의에도 철회 시각이 남는다(이력은 child_id가 비워진 채 보존)
        mvc.perform(auth(get("/api/v1/me/consents"), parent))
                .andExpect(jsonPath("$[?(@.type == 'CHILD_IMAGE_AI_ANALYSIS' && @.childId == null)].revokedAt")
                        .isNotEmpty());

        mvc.perform(body(auth(delete("/api/v1/me"), parent), Map.of("password", "abcd1234")))
                .andExpect(status().isNoContent());
        assertThat(observationCount(child2)).isZero();
        assertThat(aiDeliveryCount(child2)).isZero();
    }

    // ---------- 권한·제한 ----------

    @Test
    void otherParentsAndWrongRolesGetNotFound() throws Exception {
        Account owner = signup("PARENT");
        Account other = signup("PARENT");
        Account teacher = signup("TEACHER");
        long childId = createChild(owner);
        agree(owner, childId);
        JsonNode session = session(owner, childId);
        long obsId = save(owner, childId, receiptFor(session)).at("/observation/id").asLong();

        mvc.perform(auth(get(path(childId, "/ai-consent")), other)).andExpect(status().isNotFound());
        mvc.perform(body(auth(put(path(childId, "/ai-consent")), other), Map.of("agreed", true, "version", V)))
                .andExpect(status().isNotFound());
        mvc.perform(auth(delete(path(childId, "/ai-consent")), other)).andExpect(status().isNotFound());
        mvc.perform(auth(post(path(childId, "/ai-sessions")), other)).andExpect(status().isNotFound());
        mvc.perform(auth(get(path(childId, "/ai-observations")), other)).andExpect(status().isNotFound());
        mvc.perform(auth(delete(path(childId, "/ai-observations/" + obsId)), other)).andExpect(status().isNotFound());
        assertThat(postReceipt(other, childId, sign(receiptFor(session(owner, childId)))).getResponse().getStatus())
                .isEqualTo(404);

        // 동의는 보호자만: 연결된 선생님이어도 볼 수도 바꿀 수도 없다
        link(owner, teacher, childId);
        mvc.perform(auth(get(path(childId, "/ai-consent")), teacher)).andExpect(status().isNotFound());
        mvc.perform(body(auth(put(path(childId, "/ai-consent")), teacher), Map.of("agreed", true, "version", V)))
                .andExpect(status().isNotFound());

        // 로그인 없이는 401
        mvc.perform(post(path(childId, "/ai-sessions"))).andExpect(status().isUnauthorized());
        mvc.perform(get(path(childId, "/ai-observations"))).andExpect(status().isUnauthorized());
        assertThat(observationCount(childId)).isEqualTo(1);
    }

    @Test
    void sessionsAreLimitedToTwentyPerHourPerUser() throws Exception {
        Account parent = signup("PARENT");
        Account other = signup("PARENT");
        long childId = createChild(parent);
        long otherChild = createChild(other);
        agree(parent, childId);
        agree(other, otherChild);
        for (int i = 0; i < 20; i++) session(parent, childId);
        mvc.perform(auth(post(path(childId, "/ai-sessions")), parent)).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("TOO_MANY_ATTEMPTS"));
        // 사용자별 제한이라 다른 사람은 영향이 없다
        session(other, otherChild);
    }

    @Test
    void apiStillRefusesImagesAndLargeBodies() throws Exception {
        Account parent = signup("PARENT");
        long childId = createChild(parent);
        agree(parent, childId);
        var file = new MockMultipartFile("frame", "frame.jpg", "image/jpeg", new byte[1024]);
        mvc.perform(auth(multipart(path(childId, "/ai-observations")).file(file), parent))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
        mvc.perform(auth(post(path(childId, "/ai-observations")), parent).contentType("image/jpeg")
                        .content(new byte[100]))
                .andExpect(status().isUnsupportedMediaType());
        String big = "{\"receipt\":\"" + "a".repeat(70_000) + "\"}";
        mvc.perform(auth(post(path(childId, "/ai-observations")), parent).contentType(MediaType.APPLICATION_JSON)
                        .content(big))
                .andExpect(status().isPayloadTooLarge());
        // 본문 한도 안이어도 영수증으로 보기에는 너무 긴 값은 받지 않는다
        String longReceipt = "{\"receipt\":\"" + "a".repeat(20_000) + "\"}";
        mvc.perform(auth(post(path(childId, "/ai-observations")), parent).contentType(MediaType.APPLICATION_JSON)
                        .content(longReceipt))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
}
