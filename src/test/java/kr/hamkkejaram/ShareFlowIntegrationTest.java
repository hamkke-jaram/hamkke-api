package kr.hamkkejaram;

import com.fasterxml.jackson.databind.JsonNode;
import kr.hamkkejaram.share.ShareService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 공유·연결 흐름: 초대 코드 목록·취소와 3개 한도(SHR-02), 코드 정리 작업, 선생님 스스로 연결 끊기(TCH-07),
 * 이미 연결된 아이(ALREADY_LINKED), 다른 선생님의 목표·메모 접근(404), 메모 삭제, 탈퇴·아이 삭제 때 함께 지워지는 데이터.
 */
class ShareFlowIntegrationTest extends IntegrationTestSupport {
    @Autowired ShareService shareService;

    private String codesPath(long childId) {
        return "/api/v1/children/" + childId + "/invite-codes";
    }

    private int codeRows(String code) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM invite_codes WHERE code = ?", Integer.class, code);
    }

    // ---------- 초대 코드 목록·취소 (SHR-02) ----------

    @Test
    void parentListsAndRevokesUsableInviteCodes() throws Exception {
        String parent = token(signup("PARENT"));
        String teacher = token(signup("TEACHER"));
        long childId = createChild(parent);

        mvc.perform(auth(get(codesPath(childId)), parent)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        String c1 = issueCode(parent, childId);
        String c2 = issueCode(parent, childId);
        String c3 = issueCode(parent, childId);

        // 쓸 수 있는 코드가 3개면 더 발급할 수 없다
        mvc.perform(auth(post(codesPath(childId)), parent)).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVITE_LIMIT_REACHED"));

        // 최신 발급순, code·expiresAt·createdAt
        mvc.perform(auth(get(codesPath(childId)), parent)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].code").value(c3))
                .andExpect(jsonPath("$[1].code").value(c2))
                .andExpect(jsonPath("$[2].code").value(c1))
                .andExpect(jsonPath("$[0].expiresAt").exists())
                .andExpect(jsonPath("$[0].createdAt").exists());

        // 취소하면 목록에서 빠지고, 그 코드로는 연결할 수 없고, 새 코드를 발급할 수 있다
        mvc.perform(auth(delete(codesPath(childId) + "/" + c2), parent)).andExpect(status().isNoContent());
        mvc.perform(auth(get(codesPath(childId)), parent))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].code").value(c3))
                .andExpect(jsonPath("$[1].code").value(c1));
        mvc.perform(body(auth(post("/api/v1/teacher/links"), teacher), Map.of("code", c2)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVITE_CODE_INVALID"));
        mvc.perform(auth(delete(codesPath(childId) + "/" + c2), parent)).andExpect(status().isNotFound());
        issueCode(parent, childId);

        // 사용한 코드는 목록에서 빠지고 취소할 수도 없다
        mvc.perform(body(auth(post("/api/v1/teacher/links"), teacher), Map.of("code", c1)))
                .andExpect(status().isCreated());
        mvc.perform(auth(get(codesPath(childId)), parent))
                .andExpect(jsonPath("$[?(@.code == '" + c1 + "')]").isEmpty());
        mvc.perform(auth(delete(codesPath(childId) + "/" + c1), parent)).andExpect(status().isNotFound());

        // 만료된 코드도 목록에서 빠지고 취소할 수 없다
        jdbc.update("UPDATE invite_codes SET expires_at = created_at WHERE code = ?", c3);
        mvc.perform(auth(get(codesPath(childId)), parent))
                .andExpect(jsonPath("$[?(@.code == '" + c3 + "')]").isEmpty());
        mvc.perform(auth(delete(codesPath(childId) + "/" + c3), parent)).andExpect(status().isNotFound());
    }

    @Test
    void revokeAcceptsHyphenAndLowerCaseLikeLinking() throws Exception {
        String parent = token(signup("PARENT"));
        long childId = createChild(parent);
        String code = issueCode(parent, childId);
        mvc.perform(auth(delete(codesPath(childId) + "/" + code.substring(0, 4) + "-" + code.substring(4).toLowerCase()),
                parent)).andExpect(status().isNoContent());
        assertThat(codeRows(code)).isZero();
    }

    @Test
    void inviteCodesOfAnotherChildOrParentAreHidden() throws Exception {
        String owner = token(signup("PARENT"));
        String other = token(signup("PARENT"));
        String teacher = token(signup("TEACHER"));
        long childId = createChild(owner);
        long otherChild = createChild(owner);
        String code = issueCode(owner, childId);

        mvc.perform(auth(get(codesPath(childId)), other)).andExpect(status().isNotFound());
        mvc.perform(auth(delete(codesPath(childId) + "/" + code), other)).andExpect(status().isNotFound());
        mvc.perform(auth(get(codesPath(childId)), teacher)).andExpect(status().isNotFound());
        // 같은 부모라도 다른 아이 경로로는 취소할 수 없다
        mvc.perform(auth(delete(codesPath(otherChild) + "/" + code), owner)).andExpect(status().isNotFound());
        assertThat(codeRows(code)).isEqualTo(1);
    }

    // ---------- 초대 코드 정리 ----------

    @Test
    void staleInviteCodesArePurgedAfterSevenDays() throws Exception {
        String parent = token(signup("PARENT"));
        String teacher = token(signup("TEACHER"));
        long childId = createChild(parent);
        Instant now = Instant.now();
        String expiredLongAgo = issueCode(parent, childId);
        String expiredRecently = issueCode(parent, childId);
        String usedLongAgo = issueCode(parent, childId);
        mvc.perform(body(auth(post("/api/v1/teacher/links"), teacher), Map.of("code", usedLongAgo)))
                .andExpect(status().isCreated());
        String usable = issueCode(parent, childId);

        jdbc.update("UPDATE invite_codes SET created_at = ?, expires_at = ? WHERE code = ?",
                Timestamp.from(now.minus(Duration.ofDays(12))), Timestamp.from(now.minus(Duration.ofDays(9))),
                expiredLongAgo);
        jdbc.update("UPDATE invite_codes SET created_at = ?, expires_at = ? WHERE code = ?",
                Timestamp.from(now.minus(Duration.ofDays(6))), Timestamp.from(now.minus(Duration.ofDays(3))),
                expiredRecently);
        jdbc.update("UPDATE invite_codes SET used_at = ? WHERE code = ?",
                Timestamp.from(now.minus(Duration.ofDays(8))), usedLongAgo);

        assertThat(shareService.purgeStaleInviteCodes()).isGreaterThanOrEqualTo(2);
        assertThat(codeRows(expiredLongAgo)).isZero();
        assertThat(codeRows(usedLongAgo)).isZero();
        assertThat(codeRows(expiredRecently)).isEqualTo(1);
        assertThat(codeRows(usable)).isEqualTo(1);
        // 코드가 지워져도 연결은 그대로다
        mvc.perform(auth(get("/api/v1/teacher/children"), teacher)).andExpect(jsonPath("$.length()").value(1));
    }

    // ---------- 선생님 스스로 연결 끊기 (TCH-07) ----------

    @Test
    void teacherUnlinksThemselvesAndTheirGoalsAndNotesStay() throws Exception {
        String parent = token(signup("PARENT"));
        String teacher = token(signup("TEACHER"));
        long childId = createChild(parent);
        link(parent, teacher, childId);
        mvc.perform(body(auth(post("/api/v1/children/" + childId + "/goals"), teacher),
                Map.of("title", "친구 이름 부르기"))).andExpect(status().isCreated());
        mvc.perform(body(auth(post("/api/v1/children/" + childId + "/observations"), teacher),
                Map.of("content", "블록 놀이에 오래 머물렀어요.", "observedOn", LocalDate.now().minusDays(1).toString())))
                .andExpect(status().isCreated());

        // 부모는 이 경로를 쓸 수 없다
        mvc.perform(auth(delete("/api/v1/teacher/links/" + childId), parent)).andExpect(status().isForbidden());

        mvc.perform(auth(delete("/api/v1/teacher/links/" + childId), teacher)).andExpect(status().isNoContent());
        mvc.perform(auth(get("/api/v1/teacher/children"), teacher)).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(auth(get("/api/v1/children/" + childId + "/goals"), teacher)).andExpect(status().isNotFound());
        mvc.perform(auth(get("/api/v1/children/" + childId + "/observations"), teacher))
                .andExpect(status().isNotFound());
        mvc.perform(auth(get("/api/v1/teacher/children/" + childId + "/shared-summary"), teacher))
                .andExpect(status().isNotFound());
        // 다시 끊으면 404
        mvc.perform(auth(delete("/api/v1/teacher/links/" + childId), teacher)).andExpect(status().isNotFound());

        // 쓴 목표·메모는 남아 부모가 계속 본다
        assertThat(rows("child_goals", childId)).isEqualTo(1);
        assertThat(rows("observation_notes", childId)).isEqualTo(1);
        mvc.perform(auth(get("/api/v1/children/" + childId + "/goals"), parent))
                .andExpect(jsonPath("$[0].title").value("친구 이름 부르기"));
        mvc.perform(auth(get("/api/v1/children/" + childId + "/teachers"), parent))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void teacherCannotUnlinkAChildTheyAreNotLinkedTo() throws Exception {
        String parent = token(signup("PARENT"));
        String linked = token(signup("TEACHER"));
        String stranger = token(signup("TEACHER"));
        long childId = createChild(parent);
        link(parent, linked, childId);
        mvc.perform(auth(delete("/api/v1/teacher/links/" + childId), stranger)).andExpect(status().isNotFound());
        mvc.perform(auth(get("/api/v1/teacher/children"), linked)).andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void linkingTwiceToTheSameChildIsRejected() throws Exception {
        String parent = token(signup("PARENT"));
        String teacher = token(signup("TEACHER"));
        long childId = createChild(parent);
        link(parent, teacher, childId);
        String second = issueCode(parent, childId);
        mvc.perform(body(auth(post("/api/v1/teacher/links"), teacher), Map.of("code", second)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_LINKED"));
        // 실패한 코드는 쓰이지 않은 채 남는다
        mvc.perform(auth(get(codesPath(childId)), parent))
                .andExpect(jsonPath("$[0].code").value(second));
    }

    // ---------- 목표·메모 소유권 ----------

    @Test
    void anotherTeacherCannotChangeOrDeleteGoalsAndNotes() throws Exception {
        String parent = token(signup("PARENT"));
        String first = token(signup("TEACHER"));
        String second = token(signup("TEACHER"));
        long childId = createChild(parent);
        link(parent, first, childId);
        link(parent, second, childId);
        String base = "/api/v1/children/" + childId;
        long goalId = json(mvc.perform(body(auth(post(base + "/goals"), first), Map.of("title", "차례 기다리기")))
                .andExpect(status().isCreated()).andReturn()).get("id").asLong();
        long noteId = json(mvc.perform(body(auth(post(base + "/observations"), first),
                        Map.of("content", "차례를 기다려 줬어요.", "observedOn", LocalDate.now().toString())))
                .andExpect(status().isCreated()).andReturn()).get("id").asLong();

        mvc.perform(body(auth(patch(base + "/goals/" + goalId), second), Map.of("status", "ACHIEVED")))
                .andExpect(status().isNotFound());
        mvc.perform(auth(delete(base + "/goals/" + goalId), second)).andExpect(status().isNotFound());
        mvc.perform(auth(delete(base + "/observations/" + noteId), second)).andExpect(status().isNotFound());
        // 부모도 지울 수 없다(읽기만)
        mvc.perform(auth(delete(base + "/observations/" + noteId), parent)).andExpect(status().isNotFound());
        assertThat(rows("child_goals", childId)).isEqualTo(1);
        assertThat(rows("observation_notes", childId)).isEqualTo(1);

        // 쓴 선생님은 메모를 지울 수 있다
        mvc.perform(auth(delete(base + "/observations/" + noteId), first)).andExpect(status().isNoContent());
        assertThat(rows("observation_notes", childId)).isZero();
        mvc.perform(auth(delete(base + "/goals/" + goalId), first)).andExpect(status().isNoContent());
        assertThat(rows("child_goals", childId)).isZero();
    }

    @Test
    void goalTitleCannotBecomeBlankOnPatch() throws Exception {
        String parent = token(signup("PARENT"));
        String teacher = token(signup("TEACHER"));
        long childId = createChild(parent);
        link(parent, teacher, childId);
        String base = "/api/v1/children/" + childId + "/goals";
        long goalId = json(mvc.perform(body(auth(post(base), teacher), Map.of("title", "친구 이름 부르기")))
                .andExpect(status().isCreated()).andReturn()).get("id").asLong();

        for (String blank : new String[]{"   ", "　", ""}) {
            mvc.perform(body(auth(patch(base + "/" + goalId), teacher), Map.of("title", blank)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        mvc.perform(auth(get(base), parent)).andExpect(jsonPath("$[0].title").value("친구 이름 부르기"));
        // 제목을 빼고 보내면 제목은 그대로, 앞뒤 공백은 잘린다
        mvc.perform(body(auth(patch(base + "/" + goalId), teacher), Map.of("status", "ACHIEVED")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.title").value("친구 이름 부르기"));
        mvc.perform(body(auth(patch(base + "/" + goalId), teacher), Map.of("title", " 차례 기다리기 ")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.title").value("차례 기다리기"));
    }

    // ---------- 함께 지워지는 데이터 ----------

    @Test
    void deletingChildRemovesLinksCodesGoalsAndNotes() throws Exception {
        String parent = token(signup("PARENT"));
        String teacher = token(signup("TEACHER"));
        long childId = createChild(parent);
        link(parent, teacher, childId);
        issueCode(parent, childId); // 쓰지 않은 코드
        mvc.perform(body(auth(post("/api/v1/children/" + childId + "/goals"), teacher), Map.of("title", "인사하기")))
                .andExpect(status().isCreated());
        mvc.perform(body(auth(post("/api/v1/children/" + childId + "/observations"), teacher),
                        Map.of("content", "먼저 인사했어요.", "observedOn", LocalDate.now().toString())))
                .andExpect(status().isCreated());
        for (String table : new String[]{"invite_codes", "teacher_links", "child_goals", "observation_notes"}) {
            assertThat(rows(table, childId)).as(table).isPositive();
        }

        mvc.perform(auth(delete("/api/v1/children/" + childId), parent)).andExpect(status().isNoContent());
        for (String table : new String[]{"invite_codes", "teacher_links", "child_goals", "observation_notes",
                "share_settings"}) {
            assertThat(rows(table, childId)).as(table).isZero();
        }
        mvc.perform(auth(get("/api/v1/teacher/children"), teacher)).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void deletingTeacherAccountRemovesTheirLinksGoalsAndNotes() throws Exception {
        String parent = token(signup("PARENT"));
        JsonNode teacherTokens = signup("TEACHER");
        String teacher = token(teacherTokens);
        long teacherId = userId(teacherTokens);
        long childId = createChild(parent);
        link(parent, teacher, childId);
        mvc.perform(body(auth(post("/api/v1/children/" + childId + "/goals"), teacher), Map.of("title", "인사하기")))
                .andExpect(status().isCreated());
        mvc.perform(body(auth(post("/api/v1/children/" + childId + "/observations"), teacher),
                        Map.of("content", "먼저 인사했어요.", "observedOn", LocalDate.now().toString())))
                .andExpect(status().isCreated());

        mvc.perform(body(auth(delete("/api/v1/me"), teacher), Map.of("password", PASSWORD)))
                .andExpect(status().isNoContent());
        for (String table : new String[]{"teacher_links", "child_goals", "observation_notes"}) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE teacher_id = ?", Integer.class,
                    teacherId)).as(table).isZero();
        }
        // 사용한 코드에 남아 있던 선생님 id도 비워진다
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM invite_codes WHERE used_by = ?", Integer.class,
                teacherId)).isZero();
        // 아이와 부모 쪽 데이터는 그대로
        mvc.perform(auth(get("/api/v1/children/" + childId), parent)).andExpect(status().isOk());
        mvc.perform(auth(get("/api/v1/children/" + childId + "/goals"), parent))
                .andExpect(jsonPath("$.length()").value(0));
        mvc.perform(auth(get("/api/v1/children/" + childId + "/teachers"), parent))
                .andExpect(jsonPath("$.length()").value(0));
    }
}
