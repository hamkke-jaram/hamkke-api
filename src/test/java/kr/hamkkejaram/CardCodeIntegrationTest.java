package kr.hamkkejaram;

import com.fasterxml.jackson.databind.JsonNode;
import kr.hamkkejaram.aiobs.TestReceipts;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 카드 응답에 카드 코드({@code code})가 실리는지 본다. 화면이 이 값으로 카드에 맞는 그림을 고른다.
 * 카드가 응답에 담기는 모든 길(기록 저장, 재전송, 카드 이력, '해봤어요', AI 관찰 기록, 선생님의 카드 이력)을 지난다.
 */
class CardCodeIntegrationTest extends IntegrationTestSupport {

    /** 웹이 이미 쓰는 필드는 이름과 순서를 그대로 두고, code만 맨 뒤에 붙인다. */
    private static final List<String> CARD_FIELDS = List.of("id", "title", "body", "situation", "metricFocus",
            "evidenceTag", "sourceTitle", "sourceUrl", "author", "reviewer", "code");

    private String path(long childId, String rest) {
        return "/api/v1/children/" + childId + rest;
    }

    private String codeInDb(long cardId) {
        return jdbc.queryForObject("SELECT code FROM coaching_cards WHERE id = ?", String.class, cardId);
    }

    /** 카드 한 장이 DB의 코드를 그대로 싣고 있고, 필드가 약속한 순서인지. */
    private void assertCarriesCode(JsonNode card) {
        assertThat(card.isObject()).as("card must be present").isTrue();
        List<String> names = new ArrayList<>();
        card.fieldNames().forEachRemaining(names::add);
        assertThat(names).containsExactlyElementsOf(CARD_FIELDS);
        assertThat(card.get("code").isTextual()).isTrue();
        assertThat(card.get("code").asText()).isNotBlank().isEqualTo(codeInDb(card.get("id").asLong()));
    }

    @Test
    void recordResponsesAndCardHistoryCarryTheCardCode() throws Exception {
        String parent = token(signup("PARENT"));
        long childId = createChild(parent);
        Map<String, Object> request = metrics(Instant.now().minus(1, ChronoUnit.DAYS));

        // 기록 저장 응답
        JsonNode saved = json(mvc.perform(body(auth(post(path(childId, "/interaction-records")), parent), request))
                .andExpect(status().isCreated()).andReturn());
        JsonNode card = saved.at("/coaching/delivery/card");
        assertCarriesCode(card);
        String code = card.get("code").asText();

        // 같은 기록을 다시 보낸 응답
        JsonNode again = json(mvc.perform(body(auth(post(path(childId, "/interaction-records")), parent), request))
                .andExpect(status().isOk()).andReturn());
        assertCarriesCode(again.at("/coaching/delivery/card"));
        assertThat(again.at("/coaching/delivery/card/code").asText()).isEqualTo(code);

        // 카드 이력
        JsonNode list = json(mvc.perform(auth(get(path(childId, "/card-deliveries")), parent))
                .andExpect(status().isOk()).andReturn());
        assertThat(list.size()).isEqualTo(1);
        assertCarriesCode(list.get(0).get("card"));
        assertThat(list.get(0).at("/card/code").asText()).isEqualTo(code);

        // '해봤어요' 응답
        long deliveryId = saved.at("/coaching/delivery/id").asLong();
        JsonNode tried = json(mvc.perform(auth(post(path(childId, "/card-deliveries/" + deliveryId + "/tried")), parent))
                .andExpect(status().isOk()).andReturn());
        assertCarriesCode(tried.get("card"));
        assertThat(tried.at("/card/code").asText()).isEqualTo(code);
    }

    @Test
    void aiObservationResponseCarriesTheCardCode() throws Exception {
        JsonNode account = signup("PARENT");
        String parent = token(account);
        long childId = createChild(parent);

        mvc.perform(body(auth(put(path(childId, "/ai-consent")), parent), Map.of("agreed", true, "version", V)))
                .andExpect(status().isOk());
        String session = json(mvc.perform(auth(post(path(childId, "/ai-sessions")), parent))
                .andExpect(status().isCreated()).andReturn()).get("token").asText();
        Map<String, Object> claims = TestReceipts.claims(userId(account), childId, "HOME");
        claims.put("jti", TestReceipts.payload(session).get("jti").asText());

        JsonNode saved = json(mvc.perform(body(auth(post(path(childId, "/ai-observations")), parent),
                Map.of("receipt", TestReceipts.sign(claims)))).andExpect(status().isCreated()).andReturn());
        assertCarriesCode(saved.at("/coaching/delivery/card"));

        JsonNode list = json(mvc.perform(auth(get(path(childId, "/card-deliveries")), parent))
                .andExpect(status().isOk()).andReturn());
        assertThat(list.size()).isEqualTo(1);
        assertCarriesCode(list.get(0).get("card"));
        assertThat(list.get(0).at("/card/code").asText())
                .isEqualTo(saved.at("/coaching/delivery/card/code").asText());
    }

    @Test
    void linkedTeacherSeesTheCardCodeWhenCardsAreShared() throws Exception {
        String parent = token(signup("PARENT"));
        String teacher = token(signup("TEACHER"));
        long childId = createChild(parent);
        postRecord(parent, childId, Instant.now().minus(1, ChronoUnit.DAYS));
        link(parent, teacher, childId);
        mvc.perform(body(auth(put(path(childId, "/share-settings")), parent),
                Map.of("jointPlay", false, "responseLatency", false, "distress", false, "coachingCards", true)))
                .andExpect(status().isOk());

        JsonNode list = json(mvc.perform(auth(get(path(childId, "/card-deliveries")), teacher))
                .andExpect(status().isOk()).andReturn());
        assertThat(list.size()).isEqualTo(1);
        assertCarriesCode(list.get(0).get("card"));
    }

    /** 그림을 코드로 고르려면 코드가 카드마다 달라야 하고 비어 있으면 안 된다. */
    @Test
    void everySeededCardHasItsOwnCode() {
        List<String> codes = jdbc.queryForList("SELECT code FROM coaching_cards", String.class);
        assertThat(codes).isNotEmpty().doesNotHaveDuplicates().allSatisfy(c -> assertThat(c).isNotBlank());
    }
}
