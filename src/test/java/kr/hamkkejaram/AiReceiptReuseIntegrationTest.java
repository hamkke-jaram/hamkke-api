package kr.hamkkejaram;

import com.fasterxml.jackson.databind.JsonNode;
import kr.hamkkejaram.aiobs.AiUsedReceipt;
import kr.hamkkejaram.aiobs.AiUsedReceiptRepository;
import kr.hamkkejaram.aiobs.TestReceipts;
import kr.hamkkejaram.aiobs.UsedReceipts;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 영수증 하나는 한 번만 저장된다(AIO-07) — 그 영수증으로 만든 기록을 지운 뒤에도.
 * 쓴 영수증의 jti 는 관찰 기록과 따로, 만료 시각까지 남는다(V6 {@code ai_used_receipts}).
 * 함께 확인: 철회와 저장이 동시에 와도 철회 뒤에 기록이 남지 않는다(AIO-03).
 * 영수증은 테스트가 공유 비밀로 직접 서명한다(AI 서버 역할).
 */
class AiReceiptReuseIntegrationTest extends IntegrationTestSupport {
    @Autowired UsedReceipts usedReceipts;
    @Autowired AiUsedReceiptRepository usedReceiptRows;

    // ---------- helpers ----------

    private record Account(String token, long id) {}

    private Account account(String role) throws Exception {
        JsonNode tokens = signup(role);
        return new Account(token(tokens), userId(tokens));
    }

    private String path(long childId, String tail) {
        return "/api/v1/children/" + childId + tail;
    }

    private void agree(Account parent, long childId) throws Exception {
        mvc.perform(body(auth(put(path(childId, "/ai-consent")), parent.token()), TestReceipts.aiConsent()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.agreed").value(true));
    }

    private void allowInstitutionCapture(Account parent, long childId) throws Exception {
        Map<String, Object> req = new HashMap<>(Map.of("jointPlay", false, "responseLatency", false,
                "distress", false, "coachingCards", false));
        req.put("institutionCapture", true);
        mvc.perform(body(auth(put(path(childId, "/share-settings")), parent.token()), req)).andExpect(status().isOk());
    }

    /** 분석 토큰을 받아, AI 서버가 하듯 그 값을 그대로 옮긴 영수증 클레임을 만든다. */
    private Map<String, Object> receiptClaims(Account who, long childId) throws Exception {
        String session = json(mvc.perform(auth(post(path(childId, "/ai-sessions")), who.token()))
                .andExpect(status().isCreated()).andReturn()).get("token").asText();
        JsonNode t = TestReceipts.payload(session);
        Map<String, Object> c = TestReceipts.claims(Long.parseLong(t.get("sub").asText()), t.get("cid").asLong(),
                t.get("src").asText());
        c.put("jti", t.get("jti").asText());
        return c;
    }

    private MockHttpServletRequestBuilder saveRequest(Account who, long childId, String receipt) throws Exception {
        return body(auth(post(path(childId, "/ai-observations")), who.token()), Map.of("receipt", receipt));
    }

    private MvcResult postReceipt(Account who, long childId, String receipt) throws Exception {
        return mvc.perform(saveRequest(who, childId, receipt)).andReturn();
    }

    private long saved(MvcResult r) throws Exception {
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(201);
        return json(r).at("/observation/id").asLong();
    }

    private void assertAlreadyUsed(MvcResult r) throws Exception {
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(409);
        assertThat(json(r).get("code").asText()).isEqualTo("DUPLICATE_REQUEST");
    }

    private int aiDeliveries(long childId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM card_deliveries WHERE child_id = ? AND ai_observation_id IS NOT NULL",
                Integer.class, childId);
    }

    /** 여러 요청을 한꺼번에 보내고 상태 코드를 보낸 순서대로 모은다. 모든 스레드가 준비된 뒤 동시에 출발한다. */
    private List<Integer> together(List<Callable<MockHttpServletRequestBuilder>> requests) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(requests.size());
        try {
            CountDownLatch ready = new CountDownLatch(requests.size());
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Integer>> futures = new ArrayList<>();
            for (Callable<MockHttpServletRequestBuilder> request : requests) {
                futures.add(pool.submit(() -> {
                    MockHttpServletRequestBuilder b = request.call();
                    ready.countDown();
                    go.await();
                    return mvc.perform(b).andReturn().getResponse().getStatus();
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

    // ---------- 지운 뒤 다시 저장 ----------

    @Test
    void deletedHomeObservationCannotBeSavedAgainWithTheSameReceipt() throws Exception {
        Account parent = account("PARENT");
        long childId = createChild(parent.token());
        agree(parent, childId);
        String receipt = TestReceipts.sign(receiptClaims(parent, childId));

        long id = saved(postReceipt(parent, childId, receipt));
        assertThat(aiDeliveries(childId)).isZero();
        assertAlreadyUsed(postReceipt(parent, childId, receipt)); // 지우기 전에도 한 번뿐(예전과 같다)

        mvc.perform(auth(delete(path(childId, "/ai-observations/" + id)), parent.token()))
                .andExpect(status().isNoContent());
        assertThat(rows("ai_observations", childId)).isZero();

        // 영수증은 아직 유효하지만(15분) 이미 쓴 것이라 다시 저장되지 않는다. 카드 이력도 새로 생기지 않는다.
        assertAlreadyUsed(postReceipt(parent, childId, receipt));
        assertThat(rows("ai_observations", childId)).isZero();
        assertThat(aiDeliveries(childId)).isZero();
    }

    @Test
    void teacherCannotBringBackAnInstitutionRecordTheParentDeleted() throws Exception {
        Account parent = account("PARENT");
        Account teacher = account("TEACHER");
        long childId = createChild(parent.token());
        link(parent.token(), teacher.token(), childId);
        agree(parent, childId);
        allowInstitutionCapture(parent, childId);
        String receipt = TestReceipts.sign(receiptClaims(teacher, childId));

        long id = saved(postReceipt(teacher, childId, receipt));
        mvc.perform(auth(delete(path(childId, "/ai-observations/" + id)), parent.token()))
                .andExpect(status().isNoContent());

        // 선생님은 여전히 연결돼 있고 기관 촬영도 허용돼 있지만, 보호자가 지운 기록을 같은 영수증으로 되살릴 수 없다
        assertAlreadyUsed(postReceipt(teacher, childId, receipt));
        assertThat(rows("ai_observations", childId)).isZero();
    }

    @Test
    void receiptUsedBeforeRevokingConsentStaysUsedAfterAgreeingAgain() throws Exception {
        Account parent = account("PARENT");
        long childId = createChild(parent.token());
        agree(parent, childId);
        String receipt = TestReceipts.sign(receiptClaims(parent, childId));
        saved(postReceipt(parent, childId, receipt));

        // 철회 = 그 아이의 AI 관찰 기록 삭제
        mvc.perform(auth(delete(path(childId, "/ai-consent")), parent.token())).andExpect(status().isNoContent());
        assertThat(rows("ai_observations", childId)).isZero();
        agree(parent, childId);

        // 다시 동의해도 철회로 지워진 기록이 예전 영수증으로 돌아오지 않는다
        assertAlreadyUsed(postReceipt(parent, childId, receipt));
        assertThat(rows("ai_observations", childId)).isZero();
        // 새로 분석한 것은 저장된다
        saved(postReceipt(parent, childId, TestReceipts.sign(receiptClaims(parent, childId))));
        assertThat(rows("ai_observations", childId)).isEqualTo(1);
    }

    @Test
    void rejectedSaveDoesNotUseUpTheReceipt() throws Exception {
        Account parent = account("PARENT");
        long childId = createChild(parent.token());
        agree(parent, childId);
        Map<String, Object> claims = receiptClaims(parent, childId);
        String receipt = TestReceipts.sign(claims);

        // 기기 시계가 많이 빨라 촬영 시각이 거절된 경우: 웹은 촬영 시각 없이 같은 영수증을 한 번 더 보낸다
        mvc.perform(body(auth(post(path(childId, "/ai-observations")), parent.token()),
                        Map.of("receipt", receipt, "recordedAt", Instant.now().plus(Duration.ofHours(1)).toString())))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        assertThat(usedReceiptRows.existsById((String) claims.get("jti"))).isFalse();

        saved(postReceipt(parent, childId, receipt));
        assertAlreadyUsed(postReceipt(parent, childId, receipt));
        assertThat(rows("ai_observations", childId)).isEqualTo(1);
    }

    // ---------- 남기는 것과 정리 ----------

    @Test
    void usedReceiptRowHoldsOnlyTheJtiAndItsExpiry() throws Exception {
        Account parent = account("PARENT");
        long childId = createChild(parent.token());
        agree(parent, childId);
        Map<String, Object> claims = receiptClaims(parent, childId);
        String jti = (String) claims.get("jti");
        long exp = ((Number) claims.get("exp")).longValue();
        saved(postReceipt(parent, childId, TestReceipts.sign(claims)));

        // 아이·사용자를 가리키는 값이 없다: 컬럼은 jti 와 만료 시각뿐
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM ai_used_receipts WHERE jti = ?", jti);
        assertThat(row.keySet().stream().map(String::toLowerCase).toList())
                .containsExactlyInAnyOrder("jti", "expires_at");
        // 영수증이 더는 통하지 않는 시각(exp + 두 서버의 시계 차이 허용 60초)까지 남긴다
        assertThat(usedReceiptRows.findById(jti).orElseThrow().getExpiresAt())
                .isEqualTo(Instant.ofEpochSecond(exp).plusSeconds(60));

        // 아이를 지워도(= 동의 철회) 이 표에는 그 아이에 대한 것이 없으므로 지울 것도 없다. 행은 만료 뒤 정리된다.
        mvc.perform(auth(delete("/api/v1/children/" + childId), parent.token())).andExpect(status().isNoContent());
        assertThat(usedReceiptRows.existsById(jti)).isTrue();
    }

    @Test
    void expiredRowsArePurgedAndLiveOnesStay() throws Exception {
        Account parent = account("PARENT");
        long childId = createChild(parent.token());
        agree(parent, childId);
        Map<String, Object> claims = receiptClaims(parent, childId);
        String live = (String) claims.get("jti");
        String receipt = TestReceipts.sign(claims);
        long id = saved(postReceipt(parent, childId, receipt));
        mvc.perform(auth(delete(path(childId, "/ai-observations/" + id)), parent.token()))
                .andExpect(status().isNoContent());

        Instant now = Instant.now();
        String longExpired = UUID.randomUUID().toString();
        String justExpired = UUID.randomUUID().toString();
        usedReceiptRows.save(new AiUsedReceipt(longExpired, now.minus(Duration.ofHours(2))));
        usedReceiptRows.save(new AiUsedReceipt(justExpired, now.minus(Duration.ofMinutes(10))));

        assertThat(usedReceipts.purgeExpired()).isGreaterThanOrEqualTo(1);

        assertThat(usedReceiptRows.existsById(longExpired)).isFalse();
        assertThat(usedReceiptRows.existsById(justExpired)).isTrue(); // 여유(1시간) 안이라 다음 정리 때 지워진다
        assertThat(usedReceiptRows.existsById(live)).isTrue();
        // 정리가 돈 뒤에도 아직 유효한 영수증은 다시 쓸 수 없다
        assertAlreadyUsed(postReceipt(parent, childId, receipt));
    }

    // ---------- 동시 요청 ----------

    @Test
    void sameReceiptSentAtOnceIsStoredOnce() throws Exception {
        Account parent = account("PARENT");
        long childId = createChild(parent.token());
        agree(parent, childId);
        String receipt = TestReceipts.sign(receiptClaims(parent, childId));

        List<Callable<MockHttpServletRequestBuilder>> requests = new ArrayList<>();
        for (int i = 0; i < 6; i++) requests.add(() -> saveRequest(parent, childId, receipt));
        List<Integer> statuses = together(requests);

        assertThat(statuses.stream().filter(s -> s == 201).count()).as(statuses.toString()).isEqualTo(1);
        assertThat(statuses.stream().filter(s -> s == 409).count()).as(statuses.toString()).isEqualTo(5);
        assertThat(rows("ai_observations", childId)).isEqualTo(1);
        assertThat(aiDeliveries(childId)).isZero();
    }

    /**
     * AIO-03: 철회와 저장 중인 기록은 아이 행 잠금으로 줄 선다. 저장이 먼저면 철회가 그 기록까지 지우고,
     * 철회가 먼저면 저장은 거절된다. 어느 쪽이든 철회가 끝난 뒤에는 그 아이의 AI 관찰 기록이 남지 않는다.
     */
    @Test
    void revokingConsentWhileSavingLeavesNothingBehind() throws Exception {
        Account parent = account("PARENT");
        long childId = createChild(parent.token());
        int savedFirst = 0;
        int revokedFirst = 0;

        for (int round = 0; round < 12; round++) {
            agree(parent, childId);
            String receipt = TestReceipts.sign(receiptClaims(parent, childId));

            List<Integer> statuses = together(List.of(
                    () -> saveRequest(parent, childId, receipt),
                    () -> auth(delete(path(childId, "/ai-consent")), parent.token())));

            String seen = "round " + round + " " + statuses;
            assertThat(statuses.get(1)).as(seen).isEqualTo(204);
            assertThat(statuses.get(0)).as(seen).isIn(201, 422);
            assertThat(rows("ai_observations", childId)).as(seen).isZero();
            assertThat(aiDeliveries(childId)).as(seen).isZero();

            if (statuses.get(0) == 201) {
                savedFirst++;
                // 저장됐다가 철회로 지워진 기록은, 다시 동의해도 같은 영수증으로 돌아오지 않는다
                agree(parent, childId);
                assertAlreadyUsed(postReceipt(parent, childId, receipt));
                assertThat(rows("ai_observations", childId)).as(seen).isZero();
                mvc.perform(auth(delete(path(childId, "/ai-consent")), parent.token()))
                        .andExpect(status().isNoContent());
            } else {
                revokedFirst++;
            }
        }
        assertThat(savedFirst + revokedFirst).isEqualTo(12);
    }
}
