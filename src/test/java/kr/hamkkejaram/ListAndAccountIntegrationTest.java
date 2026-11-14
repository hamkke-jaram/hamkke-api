package kr.hamkkejaram;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 목록 커서(limit·beforeId)와 그동안 테스트가 없던 요구사항:
 * 아이 5명 한도(CHD-05), 이메일 중복·소문자 처리와 비밀번호 규칙(ACC-01), 로그아웃 뒤 갱신 거부(ACC-03), 기록 삭제(REC-06).
 */
class ListAndAccountIntegrationTest extends IntegrationTestSupport {

    /** limit=size, beforeId=마지막 id로 끝까지 넘기며 모은 id. 마지막 쪽은 size보다 적다. */
    private List<Long> pageThrough(String token, String path, int size) throws Exception {
        List<Long> all = new ArrayList<>();
        Long before = null;
        for (int guard = 0; guard < 50; guard++) {
            List<Long> page = ids(token, path + "?limit=" + size + (before == null ? "" : "&beforeId=" + before));
            assertThat(page.size()).isLessThanOrEqualTo(size);
            all.addAll(page);
            if (page.size() < size) return all;
            before = page.get(page.size() - 1);
        }
        throw new AssertionError("paging did not finish");
    }

    // ---------- 목록 커서 ----------

    @Test
    void recordsAndCardDeliveriesPageWithBeforeId() throws Exception {
        String parent = token(signup("PARENT"));
        long childId = createChild(parent);
        Instant base = Instant.now().minus(3, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        // 같은 촬영 시각 두 개(같은 시각은 id 순으로 이어진다)
        postRecord(parent, childId, base);
        postRecord(parent, childId, base.plus(1, ChronoUnit.HOURS));
        postRecord(parent, childId, base.plus(1, ChronoUnit.HOURS));
        postRecord(parent, childId, base.minus(1, ChronoUnit.HOURS)); // 늦게 보낸 예전 기록
        postRecord(parent, childId, base.plus(2, ChronoUnit.HOURS));

        String records = "/api/v1/children/" + childId + "/interaction-records";
        List<Long> full = ids(parent, records); // 매개변수 없이: 예전과 같다(최대 20개, 최신순)
        assertThat(full).hasSize(5);
        assertThat(pageThrough(parent, records, 2)).isEqualTo(full);
        assertThat(pageThrough(parent, records, 1)).isEqualTo(full);
        assertThat(ids(parent, records + "?limit=2")).isEqualTo(full.subList(0, 2));
        assertThat(ids(parent, records + "?beforeId=" + full.get(4))).isEmpty();

        String cards = "/api/v1/children/" + childId + "/card-deliveries";
        List<Long> allCards = ids(parent, cards);
        assertThat(allCards).hasSizeGreaterThanOrEqualTo(3);
        assertThat(pageThrough(parent, cards, 2)).isEqualTo(allCards);
    }

    @Test
    void unknownOrForeignCursorIsRejected() throws Exception {
        String parent = token(signup("PARENT"));
        long childId = createChild(parent);
        long otherChild = createChild(parent);
        long otherRecord = postRecord(parent, otherChild, Instant.now().minus(1, ChronoUnit.HOURS));
        postRecord(parent, childId, Instant.now().minus(2, ChronoUnit.HOURS));

        String records = "/api/v1/children/" + childId + "/interaction-records";
        mvc.perform(auth(get(records + "?beforeId=" + otherRecord), parent)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(auth(get(records + "?beforeId=999999999"), parent)).andExpect(status().isBadRequest());
        mvc.perform(auth(get("/api/v1/children/" + childId + "/card-deliveries?beforeId=999999999"), parent))
                .andExpect(status().isBadRequest());
        mvc.perform(auth(get("/api/v1/children/" + childId + "/observations?beforeId=999999999"), parent))
                .andExpect(status().isBadRequest());
        mvc.perform(auth(get("/api/v1/children/" + childId + "/ai-observations?beforeId=999999999"), parent))
                .andExpect(status().isBadRequest());
        // 권한 검사가 먼저다: 남의 아이 목록은 커서와 상관없이 404
        String stranger = token(signup("PARENT"));
        mvc.perform(auth(get(records + "?beforeId=999999999"), stranger)).andExpect(status().isNotFound());
    }

    @Test
    void teacherNotesAndAiObservationsPageWithBeforeId() throws Exception {
        JsonNode parentTokens = signup("PARENT");
        String parent = token(parentTokens);
        String teacher = token(signup("TEACHER"));
        long childId = createChild(parent);
        link(parent, teacher, childId);

        String notes = "/api/v1/children/" + childId + "/observations";
        LocalDate today = LocalDate.now();
        for (LocalDate on : new LocalDate[]{today.minusDays(2), today, today.minusDays(1), today, today.minusDays(3)}) {
            mvc.perform(body(auth(post(notes), teacher), Map.of("content", "함께 놀이했어요.", "observedOn", on.toString())))
                    .andExpect(status().isCreated());
        }
        List<Long> allNotes = ids(parent, notes);
        assertThat(allNotes).hasSize(5);
        assertThat(pageThrough(parent, notes, 2)).isEqualTo(allNotes);
        assertThat(pageThrough(teacher, notes, 3)).isEqualTo(allNotes);

        String ai = "/api/v1/children/" + childId + "/ai-observations";
        long parentId = userId(parentTokens);
        for (int i = 0; i < 3; i++) saveAiObservation(parent, parentId, childId);
        List<Long> allAi = ids(parent, ai);
        assertThat(allAi).hasSize(3);
        assertThat(pageThrough(parent, ai, 2)).isEqualTo(allAi);
        // 가정 기록 공유가 꺼져 있으면 선생님은 그 기록을 기준으로도 넘길 수 없다
        mvc.perform(auth(get(ai + "?beforeId=" + allAi.get(0)), teacher)).andExpect(status().isBadRequest());
        mvc.perform(body(auth(put("/api/v1/children/" + childId + "/share-settings"), parent),
                        Map.of("jointPlay", false, "responseLatency", false, "distress", false, "coachingCards", false,
                                "aiObservations", true)))
                .andExpect(status().isOk());
        assertThat(pageThrough(teacher, ai, 2)).isEqualTo(allAi);
    }

    // ---------- 기록 삭제 (REC-06) ----------

    @Test
    void parentDeletesOneRecord() throws Exception {
        String parent = token(signup("PARENT"));
        String other = token(signup("PARENT"));
        long childId = createChild(parent);
        long keep = postRecord(parent, childId, Instant.now().minus(2, ChronoUnit.HOURS));
        long drop = postRecord(parent, childId, Instant.now().minus(1, ChronoUnit.HOURS));
        String path = "/api/v1/children/" + childId + "/interaction-records";

        mvc.perform(auth(delete(path + "/" + drop), other)).andExpect(status().isNotFound());
        mvc.perform(auth(delete(path + "/" + drop), parent)).andExpect(status().isNoContent());
        assertThat(ids(parent, path)).containsExactly(keep);
        mvc.perform(auth(delete(path + "/" + drop), parent)).andExpect(status().isNotFound());
        // 그 기록으로 받은 카드 이력은 남고(record_id만 비워진다) 기록과의 연결만 끊긴다
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM card_deliveries WHERE record_id = ?", Integer.class, drop))
                .isZero();
    }

    // ---------- 아이 5명 한도 (CHD-05) ----------

    @Test
    void sixthChildIsRejected() throws Exception {
        String parent = token(signup("PARENT"));
        for (int i = 0; i < 5; i++) createChild(parent);
        var req = Map.of("nickname", "여섯째", "birthYearMonth", LocalDate.now().minusYears(1).toString().substring(0, 7),
                "guardianConsent", Map.of("agreed", true, "version", V));
        mvc.perform(body(auth(post("/api/v1/children"), parent), req)).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CHILD_LIMIT_REACHED"));
        mvc.perform(auth(get("/api/v1/children"), parent)).andExpect(jsonPath("$.length()").value(5));
    }

    // ---------- 가입 규칙 (ACC-01) ----------

    @Test
    void emailIsStoredLowerCaseAndDuplicatesIgnoreCase() throws Exception {
        String email = uniqueEmail("Mixed.Case");
        JsonNode first = json(mvc.perform(body(post("/api/v1/auth/signup"),
                        signupRequest(email.toUpperCase(), PASSWORD, "PARENT")))
                .andExpect(status().isCreated()).andReturn());
        assertThat(first.at("/user/email").asText()).isEqualTo(email.toLowerCase());

        mvc.perform(body(post("/api/v1/auth/signup"), signupRequest(email.toLowerCase(), PASSWORD, "TEACHER")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_TAKEN"));
        // 대소문자를 바꿔 로그인해도 같은 계정이다
        mvc.perform(body(post("/api/v1/auth/login"), Map.of("email", email.toUpperCase(), "password", PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.id").value(userId(first)));
    }

    @Test
    void passwordNeedsEightToSixtyFourCharsWithLettersAndDigits() throws Exception {
        for (String bad : new String[]{"abc123", "abcdefgh", "12345678", "a1" + "x".repeat(63)}) {
            mvc.perform(body(post("/api/v1/auth/signup"), signupRequest(uniqueEmail("pw"), bad, "PARENT")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.fieldErrors[?(@.field == 'password')]").isNotEmpty());
        }
        mvc.perform(body(post("/api/v1/auth/signup"), signupRequest(uniqueEmail("pw"), "abcd1234", "PARENT")))
                .andExpect(status().isCreated());
    }

    // ---------- 로그아웃 (ACC-03) ----------

    @Test
    void refreshAfterLogoutIsRejected() throws Exception {
        JsonNode tokens = signup("PARENT");
        String refresh = tokens.get("refreshToken").asText();
        mvc.perform(body(post("/api/v1/auth/logout"), Map.of("refreshToken", refresh)))
                .andExpect(status().isNoContent());
        mvc.perform(body(post("/api/v1/auth/refresh"), Map.of("refreshToken", refresh)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
    }
}
