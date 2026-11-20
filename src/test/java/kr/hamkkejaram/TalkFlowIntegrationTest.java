package kr.hamkkejaram;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 보호자와 선생님이 주고받는 이야기(TLK): 주고받기, 관찰 메모에 답하기, 안 읽은 수와 읽음 표시,
 * 새 것만 받아 가기(afterId), 연결되지 않은 사람·끊은 선생님(404), 탈퇴·아이 삭제와 내 데이터 내려받기.
 */
class TalkFlowIntegrationTest extends IntegrationTestSupport {

    private String path(long childId) {
        return "/api/v1/children/" + childId + "/messages";
    }

    private JsonNode send(String token, long childId, String content, Long observationId) throws Exception {
        Map<String, Object> req = new HashMap<>();
        req.put("content", content);
        if (observationId != null) req.put("observationId", observationId);
        return json(mvc.perform(body(auth(post(path(childId)), token), req))
                .andExpect(status().isCreated()).andReturn());
    }

    private long unread(String token, long childId) throws Exception {
        return json(mvc.perform(auth(get(path(childId) + "/unread"), token))
                .andExpect(status().isOk()).andReturn()).get("unread").asLong();
    }

    private long postNote(String teacher, long childId) throws Exception {
        return json(mvc.perform(body(auth(post("/api/v1/children/" + childId + "/observations"), teacher),
                        Map.of("content", "블록을 친구에게 먼저 건넸어요.", "observedOn", LocalDate.now().toString())))
                .andExpect(status().isCreated()).andReturn()).get("id").asLong();
    }

    @Test
    void parentAndLinkedTeacherTalkWithUnreadCounts() throws Exception {
        String parent = token(signup("PARENT"));
        String teacher = token(signup("TEACHER"));
        long childId = createChild(parent);
        link(parent, teacher, childId);

        assertThat(unread(parent, childId)).isZero();
        mvc.perform(auth(get(path(childId)), parent)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        // 선생님이 관찰 메모를 쓰고, 보호자가 그 메모에 답한다
        long noteId = postNote(teacher, childId);
        JsonNode reply = send(parent, childId, "  감사해요! 집에서도 해 볼게요.  ", noteId);
        assertThat(reply.get("content").asText()).isEqualTo("감사해요! 집에서도 해 볼게요.");
        assertThat(reply.get("authorRole").asText()).isEqualTo("PARENT");
        assertThat(reply.get("authorName").asText()).isEqualTo("하늘맘");
        assertThat(reply.get("mine").asBoolean()).isTrue();
        assertThat(reply.get("observationId").asLong()).isEqualTo(noteId);

        // 선생님에게는 안 읽은 이야기 1, 보호자에게는 0(내가 쓴 것)
        assertThat(unread(teacher, childId)).isEqualTo(1);
        assertThat(unread(parent, childId)).isZero();

        JsonNode answer = send(teacher, childId, "네, 내일도 함께 지켜볼게요.", null);
        assertThat(answer.get("authorRole").asText()).isEqualTo("TEACHER");
        assertThat(answer.get("authorName").asText()).isEqualTo("김선생");
        assertThat(answer.get("observationId").isNull()).isTrue();
        // 선생님이 답했으니 선생님 쪽은 다 읽은 것, 보호자 쪽은 1
        assertThat(unread(teacher, childId)).isZero();
        assertThat(unread(parent, childId)).isEqualTo(1);

        // 오래된 순 목록, 보는 사람 기준 mine
        mvc.perform(auth(get(path(childId)), parent)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].mine").value(true))
                .andExpect(jsonPath("$[1].mine").value(false))
                .andExpect(jsonPath("$[1].content").value("네, 내일도 함께 지켜볼게요."));

        // 새 것만 받아 가기
        long firstId = reply.get("id").asLong();
        mvc.perform(auth(get(path(childId) + "?afterId=" + firstId), parent))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(answer.get("id").asLong()));

        // 읽음 표시: 있지도 않은 앞자리를 보내도 가장 최근까지만, 뒤로 되돌리지 않는다
        mvc.perform(body(auth(post(path(childId) + "/read"), parent), Map.of("lastReadId", 999_999_999L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unread").value(0))
                .andExpect(jsonPath("$.latestId").value(answer.get("id").asLong()));
        mvc.perform(body(auth(post(path(childId) + "/read"), parent), Map.of("lastReadId", firstId)))
                .andExpect(jsonPath("$.unread").value(0));
        JsonNode third = send(teacher, childId, "오늘 낮잠을 푹 잤어요.", null);
        assertThat(unread(parent, childId)).isEqualTo(1);
        assertThat(third.get("id").asLong()).isGreaterThan(answer.get("id").asLong());
    }

    @Test
    void strangersAndUnlinkedTeachersCannotReadOrWrite() throws Exception {
        String parent = token(signup("PARENT"));
        String teacher = token(signup("TEACHER"));
        String otherParent = token(signup("PARENT"));
        String otherTeacher = token(signup("TEACHER"));
        long childId = createChild(parent);
        long otherChild = createChild(otherParent);
        link(parent, teacher, childId);
        link(otherParent, otherTeacher, otherChild);

        send(teacher, childId, "처음 인사드려요.", null);
        for (String stranger : new String[]{otherParent, otherTeacher}) {
            mvc.perform(auth(get(path(childId)), stranger)).andExpect(status().isNotFound());
            mvc.perform(body(auth(post(path(childId)), stranger), Map.of("content", "안녕하세요")))
                    .andExpect(status().isNotFound());
            mvc.perform(auth(get(path(childId) + "/unread"), stranger)).andExpect(status().isNotFound());
        }

        // 다른 아이의 관찰 메모에는 답할 수 없다(없는 것처럼 404)
        long otherNote = postNote(otherTeacher, otherChild);
        mvc.perform(body(auth(post(path(childId)), parent), Map.of("content", "답장", "observationId", otherNote)))
                .andExpect(status().isNotFound());

        // 빈 글·너무 긴 글은 받지 않는다
        mvc.perform(body(auth(post(path(childId)), parent), Map.of("content", "   ")))
                .andExpect(status().isBadRequest());
        mvc.perform(body(auth(post(path(childId)), parent), Map.of("content", "가".repeat(501))))
                .andExpect(status().isBadRequest());

        // 선생님이 연결을 끊으면 더는 읽지 못하고, 보호자에게는 이미 쓴 이야기가 남는다
        mvc.perform(auth(delete("/api/v1/teacher/links/" + childId), teacher)).andExpect(status().isNoContent());
        mvc.perform(auth(get(path(childId)), teacher)).andExpect(status().isNotFound());
        mvc.perform(auth(get(path(childId)), parent)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].authorRole").value("TEACHER"));
    }

    @Test
    void messagesAreInDataExportAndGoneWithTheChild() throws Exception {
        String parent = token(signup("PARENT"));
        String teacher = token(signup("TEACHER"));
        long childId = createChild(parent);
        link(parent, teacher, childId);
        send(parent, childId, "오늘 집에서 그림책을 같이 읽었어요.", null);
        send(teacher, childId, "어린이집에서도 그 책을 찾아볼게요.", null);

        JsonNode ex = json(mvc.perform(auth(get("/api/v1/me/export"), parent)).andExpect(status().isOk()).andReturn());
        JsonNode msgs = ex.at("/children/0/messages");
        assertThat(msgs.size()).isEqualTo(2);
        assertThat(msgs.get(0).get("authorRole").asText()).isEqualTo("PARENT");
        assertThat(msgs.get(1).get("authorName").asText()).isEqualTo("김선생");

        JsonNode tex = json(mvc.perform(auth(get("/api/v1/me/export"), teacher)).andExpect(status().isOk()).andReturn());
        assertThat(tex.at("/teacher/messages").size()).isEqualTo(1);
        assertThat(tex.at("/teacher/messages/0/childId").asLong()).isEqualTo(childId);

        // 아이를 지우면 이야기와 읽음 표시도 함께 지워진다
        mvc.perform(auth(delete("/api/v1/children/" + childId), parent)).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM child_messages WHERE child_id = ?", Integer.class, childId))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM child_message_reads WHERE child_id = ?", Integer.class,
                childId)).isZero();
    }
}
