package kr.hamkkejaram;

import com.fasterxml.jackson.databind.JsonNode;
import kr.hamkkejaram.card.CardDelivery;
import kr.hamkkejaram.card.CardDeliveryRepository;
import kr.hamkkejaram.card.CoachingCardRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DaycareCoachingIntegrationTest extends IntegrationTestSupport {
    @Autowired CardDeliveryRepository deliveries;
    @Autowired CoachingCardRepository cards;

    private String path(long childId) {
        return "/api/v1/teacher/children/" + childId + "/daycare-coaching/cards";
    }

    private void share(String parent, long childId, boolean enabled) throws Exception {
        mvc.perform(body(auth(put("/api/v1/children/" + childId + "/share-settings"), parent),
                Map.of("jointPlay", false, "responseLatency", false, "distress", false, "coachingCards", enabled)))
                .andExpect(status().isOk());
    }

    private void deliver(long childId, long cardId, String utcDateTime) {
        deliveries.save(new CardDelivery(childId, cards.getReferenceById(cardId), null, Instant.parse(utcDateTime)));
    }

    @Test
    void requiresCurrentTeacherLinkAndCoachingPermissionEvenForEmptyMonth() throws Exception {
        String parent = token(signup("PARENT"));
        String teacher = token(signup("TEACHER"));
        long childId = createChild(parent);
        String url = path(childId) + "?month=2021-01";
        mvc.perform(get(url)).andExpect(status().isUnauthorized());
        mvc.perform(auth(get(url), parent)).andExpect(status().isNotFound());
        share(parent, childId, true);
        mvc.perform(auth(get(url), teacher)).andExpect(status().isNotFound());
        link(parent, teacher, childId);
        mvc.perform(auth(get(url), teacher)).andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
        mvc.perform(auth(get(path(childId)).param("month", "2021-13"), teacher)).andExpect(status().isBadRequest());
        share(parent, childId, false);
        mvc.perform(auth(get(url), teacher)).andExpect(status().isNotFound());
        share(parent, childId, true);
        mvc.perform(auth(delete("/api/v1/teacher/links/" + childId), teacher)).andExpect(status().isNoContent());
        mvc.perform(auth(get(url), teacher)).andExpect(status().isNotFound());
    }

    @Test
    void returnsOnlyDistinctCatalogCopyForThisChildAndKoreanMonth() throws Exception {
        String teacher = token(signup("TEACHER"));
        String parent = token(signup("PARENT"));
        long childId = createChild(parent);
        link(parent, teacher, childId);
        share(parent, childId, true);
        deliver(childId, 1, "2021-01-31T14:59:59Z");
        deliver(childId, 2, "2021-01-31T15:00:00Z"); // 2월 1일 00:00 KST
        deliver(childId, 3, "2021-02-01T00:00:00Z");
        deliver(childId, 3, "2021-02-28T14:59:59Z");
        deliver(childId, 4, "2021-02-28T15:00:00Z"); // 3월 1일 00:00 KST
        deliver(createChild(parent), 5, "2021-02-02T00:00:00Z");
        JsonNode result = json(mvc.perform(auth(get(path(childId)).param("month", "2021-02"), teacher))
                .andExpect(status().isOk()).andReturn());
        assertThat(result.size()).isEqualTo(2);
        assertThat(result.get(0).path("code").asText()).isEqualTo("JP-G-01");
        assertThat(result.get(1).path("code").asText()).isEqualTo("FIRST-02");
        for (JsonNode row : result) {
            assertThat(row.size()).isEqualTo(3);
            assertThat(row.path("title").asText()).isNotBlank();
            assertThat(row.path("body").asText()).isNotBlank();
        }
    }
}
