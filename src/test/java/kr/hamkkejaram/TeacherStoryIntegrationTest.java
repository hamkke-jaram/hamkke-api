package kr.hamkkejaram;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 선생님 이야기: 제목·첨부한 코칭 카드(보호자가 카드 공유를 켠 경우, 이 아이가 받은 카드만), 보호자에게 보이는 모양,
 * 선생님의 기관·반 이름(선생님만).
 */
class TeacherStoryIntegrationTest extends IntegrationTestSupport {

    private String notes(long childId) {
        return "/api/v1/children/" + childId + "/observations";
    }

    private Map<String, Object> story(String title, String content, Long deliveryId) {
        Map<String, Object> m = new HashMap<>();
        m.put("content", content);
        m.put("observedOn", LocalDate.now().toString());
        if (title != null) m.put("title", title);
        if (deliveryId != null) m.put("cardDeliveryId", deliveryId);
        return m;
    }

    private long firstDelivery(String parent, long childId) throws Exception {
        JsonNode list = json(mvc.perform(auth(get("/api/v1/children/" + childId + "/card-deliveries"), parent))
                .andExpect(status().isOk()).andReturn());
        assertThat(list.size()).isGreaterThan(0);
        return list.get(0).get("id").asLong();
    }

    private void shareCards(String parent, long childId, boolean on) throws Exception {
        mvc.perform(body(auth(put("/api/v1/children/" + childId + "/share-settings"), parent),
                Map.of("jointPlay", true, "responseLatency", true, "distress", false, "coachingCards", on)))
                .andExpect(status().isOk());
    }

    @Test
    void teacherWritesStoryWithTitleAndAttachedCoachingCard() throws Exception {
        String parent = token(signup("PARENT"));
        String teacher = token(signup("TEACHER"));
        long childId = createChild(parent);
        link(parent, teacher, childId);
        postRecord(parent, childId, Instant.now().minusSeconds(3600));
        long deliveryId = firstDelivery(parent, childId);

        // 카드 공유가 꺼져 있으면 첨부할 수 없다(없는 것처럼 404)
        mvc.perform(body(auth(post(notes(childId)), teacher), story("즐거운 공놀이", "친구들과 공놀이를 했어요.", deliveryId)))
                .andExpect(status().isNotFound());

        shareCards(parent, childId, true);
        JsonNode created = json(mvc.perform(body(auth(post(notes(childId)), teacher),
                        story("  즐거운 공놀이  ", "오늘 민서는 친구들과 함께 공놀이를 하며 즐거운 시간을 보냈어요!", deliveryId)))
                .andExpect(status().isCreated()).andReturn());
        assertThat(created.get("title").asText()).isEqualTo("즐거운 공놀이");
        assertThat(created.at("/card/deliveryId").asLong()).isEqualTo(deliveryId);
        assertThat(created.at("/card/title").asText()).isNotBlank();
        assertThat(created.at("/card/code").isMissingNode()).isFalse();

        // 제목·카드 없이도 쓸 수 있다(예전 모양 그대로)
        JsonNode plain = json(mvc.perform(body(auth(post(notes(childId)), teacher), story(null, "낮잠을 푹 잤어요.", null)))
                .andExpect(status().isCreated()).andReturn());
        assertThat(plain.get("title").isNull()).isTrue();
        assertThat(plain.get("card").isNull()).isTrue();

        // 보호자에게도 제목·카드가 보인다
        mvc.perform(auth(get(notes(childId)), parent)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[?(@.title == '즐거운 공놀이')].card.deliveryId").value(org.hamcrest.Matchers.hasItem((int) deliveryId)));

        // 제목 40자 넘으면 받지 않는다
        mvc.perform(body(auth(post(notes(childId)), teacher), story("가".repeat(41), "내용", null)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void revokedCardSharingHidesOldAttachmentsFromTeachersAndTheirExports() throws Exception {
        String parent = token(signup("PARENT"));
        String teacher = token(signup("TEACHER"));
        long childId = createChild(parent);
        link(parent, teacher, childId);
        postRecord(parent, childId, Instant.now().minusSeconds(3600));
        long deliveryId = firstDelivery(parent, childId);
        shareCards(parent, childId, true);
        JsonNode note = json(mvc.perform(body(auth(post(notes(childId)), teacher),
                        story("시연 이야기", "블록을 골랐어요.", deliveryId))).andExpect(status().isCreated()).andReturn());
        String noteDetail = notes(childId) + "/" + note.get("id").asLong();
        String cardDetail = "/api/v1/children/" + childId + "/card-deliveries/" + deliveryId;
        mvc.perform(auth(get(noteDetail), teacher)).andExpect(status().isOk())
                .andExpect(jsonPath("$.card.deliveryId").value(deliveryId));
        mvc.perform(auth(get(cardDetail), teacher)).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(deliveryId));
        long otherChild = createChild(parent);
        mvc.perform(auth(get(notes(otherChild) + "/" + note.get("id").asLong()), parent)).andExpect(status().isNotFound());
        mvc.perform(auth(get("/api/v1/children/" + otherChild + "/card-deliveries/" + deliveryId), parent))
                .andExpect(status().isNotFound());
        mvc.perform(auth(get("/api/v1/me/export"), teacher)).andExpect(status().isOk())
                .andExpect(jsonPath("$.teacher.observationNotes[0].cardDeliveryId").value(deliveryId));

        shareCards(parent, childId, false);
        String newTeacher = token(signup("TEACHER"));
        link(parent, newTeacher, childId);
        for (String viewer : java.util.List.of(teacher, newTeacher)) {
            mvc.perform(auth(get(noteDetail), viewer)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(note.get("id").asLong()))
                    .andExpect(jsonPath("$.card").doesNotExist());
            mvc.perform(auth(get(cardDetail), viewer)).andExpect(status().isNotFound());
            mvc.perform(auth(get(notes(childId)), viewer)).andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].title").value("시연 이야기"))
                    .andExpect(jsonPath("$[0].content").value("블록을 골랐어요."))
                    .andExpect(jsonPath("$[0].card").doesNotExist());
        }
        mvc.perform(auth(get("/api/v1/me/export"), teacher)).andExpect(status().isOk())
                .andExpect(jsonPath("$.teacher.observationNotes[0].title").value("시연 이야기"))
                .andExpect(jsonPath("$.teacher.observationNotes[0].cardDeliveryId").doesNotExist());
        mvc.perform(auth(get(notes(childId)), parent)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].card.deliveryId").value(deliveryId));
        mvc.perform(auth(get(noteDetail), parent)).andExpect(status().isOk())
                .andExpect(jsonPath("$.card.deliveryId").value(deliveryId));
        mvc.perform(auth(get(cardDetail), parent)).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(deliveryId));
        mvc.perform(auth(get("/api/v1/me/export"), parent)).andExpect(status().isOk())
                .andExpect(jsonPath("$.children[0].observationNotes[0].card.deliveryId").value(deliveryId));

        shareCards(parent, childId, true);
        mvc.perform(auth(get(notes(childId)), teacher)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].card.deliveryId").value(deliveryId));
        mvc.perform(auth(delete("/api/v1/children/" + childId + "/teachers/" + note.get("teacherId").asLong()), parent))
                .andExpect(status().isNoContent());
        mvc.perform(auth(get("/api/v1/me/export"), teacher)).andExpect(status().isOk())
                .andExpect(jsonPath("$.teacher.observationNotes[0].title").value("시연 이야기"))
                .andExpect(jsonPath("$.teacher.observationNotes[0].cardDeliveryId").doesNotExist());
    }

    @Test
    void cannotAttachAnotherChildsCard() throws Exception {
        String parent = token(signup("PARENT"));
        String teacher = token(signup("TEACHER"));
        long childA = createChild(parent);
        long childB = createChild(parent);
        link(parent, teacher, childA);
        link(parent, teacher, childB);
        shareCards(parent, childA, true);
        shareCards(parent, childB, true);
        postRecord(parent, childB, Instant.now().minusSeconds(3600));
        long deliveryOfB = firstDelivery(parent, childB);

        mvc.perform(body(auth(post(notes(childA)), teacher), story("제목", "내용", deliveryOfB)))
                .andExpect(status().isNotFound());
    }

    @Test
    void teacherSetsInstitutionAndClassOnlyForThemselves() throws Exception {
        String teacher = token(signup("TEACHER"));
        String parent = token(signup("PARENT"));

        mvc.perform(body(auth(put("/api/v1/me/teacher-profile"), teacher),
                        Map.of("institution", " 꿈나무 유치원 ", "className", "고우반")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.institution").value("꿈나무 유치원"))
                .andExpect(jsonPath("$.className").value("고우반"));
        mvc.perform(auth(get("/api/v1/me"), teacher)).andExpect(status().isOk())
                .andExpect(jsonPath("$.institution").value("꿈나무 유치원"));

        // 빈 값이면 지운다
        mvc.perform(body(auth(put("/api/v1/me/teacher-profile"), teacher), Map.of("institution", "  ", "className", "")))
                .andExpect(jsonPath("$.institution").doesNotExist());

        // 보호자는 쓸 수 없다
        mvc.perform(body(auth(put("/api/v1/me/teacher-profile"), parent), Map.of("institution", "어딘가")))
                .andExpect(status().isForbidden());
        // 너무 길면 받지 않는다
        mvc.perform(body(auth(put("/api/v1/me/teacher-profile"), teacher), Map.of("institution", "가".repeat(41))))
                .andExpect(status().isBadRequest());
    }
}
