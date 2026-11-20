package kr.hamkkejaram;

import com.fasterxml.jackson.databind.JsonNode;
import kr.hamkkejaram.support.FacilityImportException;
import kr.hamkkejaram.support.FacilityImporter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.web.servlet.MvcResult;

import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * 지역 기관 찾기(SUP-03)와 기관 CSV 가져오기(SUP-04).
 * 시험 데이터는 src/test/resources/facilities/sample-facilities.csv(누가 봐도 가짜인 이름·주소, 8곳)다.
 * 이 표는 이 테스트만 쓰므로 테스트마다 비우고 다시 넣는다.
 */
class SupportFacilityIntegrationTest extends IntegrationTestSupport {
    private static final String HEADER =
            "category,name,sido,sigungu,address,phone,homepage,operator,tags,source_name,source_date\n";
    private static final String ROW_1 =
            "SPECIAL_SCHOOL,시험 특수학교 1,서울특별시,시험구,서울특별시 시험구 시험로 1,02-0000-0001,"
                    + "https://example.com/school-1,PUBLIC,유치원 과정;지적장애,시험 공공데이터,2026-04-01";

    @Autowired FacilityImporter importer;

    @BeforeEach
    void loadSample() throws Exception {
        jdbc.update("DELETE FROM support_facilities");
        try (Reader r = new InputStreamReader(
                new ClassPathResource("facilities/sample-facilities.csv").getInputStream(), StandardCharsets.UTF_8)) {
            FacilityImporter.Result result = importer.importCsv(r);
            assertThat(result).isEqualTo(new FacilityImporter.Result(8, 0, 0));
        }
    }

    /** query는 인코딩하지 않은 "?a=b&c=d" 꼴. 값은 .param()으로 넘겨 그대로 전달된다. */
    private MvcResult call(String query) throws Exception {
        var req = get("/api/v1/support-facilities");
        if (!query.isEmpty()) {
            for (String pair : query.substring(1).split("&")) {
                int eq = pair.indexOf('=');
                req.param(pair.substring(0, eq), pair.substring(eq + 1));
            }
        }
        return mvc.perform(req).andReturn();
    }

    private JsonNode search(String query) throws Exception {
        MvcResult r = call(query);
        assertThat(r.getResponse().getStatus()).as(query).isEqualTo(200);
        return json(r);
    }

    private static List<String> names(JsonNode page) {
        List<String> out = new ArrayList<>();
        page.get("items").forEach(i -> out.add(i.get("name").asText()));
        return out;
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> out = new ArrayList<>();
        node.fieldNames().forEachRemaining(out::add);
        return out;
    }

    private int facilityRows() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM support_facilities", Integer.class);
    }

    @Test
    void listsEverythingByNameWithTheAgreedShape() throws Exception {
        MvcResult r = call("");
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(r.getResponse().getHeader("Cache-Control")).contains("max-age=3600").contains("public");
        JsonNode page = json(r);

        assertThat(fieldNames(page)).containsExactly("items", "total", "page", "size", "counts");
        assertThat(page.get("total").asLong()).isEqualTo(8);
        assertThat(page.get("page").asInt()).isZero();
        assertThat(page.get("size").asInt()).isEqualTo(20);
        assertThat(names(page)).containsExactly("가나 시험 특수학교", "시험 100% 센터", "시험 육아종합지원센터 1",
                "시험 장애인복지관 1", "시험 정신건강복지센터 1", "시험 특수교육지원센터 1", "시험 특수학교 1", "시험 특수학교 2");

        JsonNode first = page.get("items").get(6);
        assertThat(fieldNames(first)).containsExactly("id", "category", "name", "sido", "sigungu", "address",
                "phone", "homepage", "operator", "tags", "source", "status");
        assertThat(first.get("category").asText()).isEqualTo("SPECIAL_SCHOOL");
        assertThat(first.get("sido").asText()).isEqualTo("서울특별시");
        assertThat(first.get("sigungu").asText()).isEqualTo("시험구");
        assertThat(first.get("address").asText()).isEqualTo("서울특별시 시험구 시험로 1");
        assertThat(first.get("phone").asText()).isEqualTo("02-0000-0001");
        assertThat(first.get("homepage").asText()).isEqualTo("https://example.com/school-1");
        assertThat(first.get("operator").asText()).isEqualTo("PUBLIC");
        assertThat(first.get("tags")).hasSize(2);
        assertThat(first.at("/tags/0").asText()).isEqualTo("유치원 과정");
        assertThat(first.at("/tags/1").asText()).isEqualTo("지적장애");
        assertThat(fieldNames(first.get("source"))).containsExactly("name", "date");
        assertThat(first.at("/source/name").asText()).isEqualTo("시험 공공데이터");
        assertThat(first.at("/source/date").asText()).isEqualTo("2026-04-01");
        assertThat(first.get("status").asText()).isEqualTo("PUBLIC_DATA");

        // 비어 있는 값은 null, 태그가 없으면 빈 배열
        JsonNode bare = page.get("items").get(0);
        assertThat(bare.get("phone").isNull()).isTrue();
        assertThat(bare.get("homepage").isNull()).isTrue();
        assertThat(bare.get("operator").isNull()).isTrue();
        assertThat(bare.get("tags").isArray()).isTrue();
        assertThat(bare.get("tags")).isEmpty();

        JsonNode counts = page.get("counts");
        assertThat(fieldNames(counts)).containsExactly("SPECIAL_SCHOOL", "SPECIAL_ED_SUPPORT_CENTER",
                "CHILDCARE_SUPPORT_CENTER", "MENTAL_HEALTH_CENTER", "DISABILITY_WELFARE_CENTER");
        assertThat(counts.get("SPECIAL_SCHOOL").asLong()).isEqualTo(3);
        assertThat(counts.get("SPECIAL_ED_SUPPORT_CENTER").asLong()).isEqualTo(1);
        assertThat(counts.get("CHILDCARE_SUPPORT_CENTER").asLong()).isEqualTo(2);
        assertThat(counts.get("MENTAL_HEALTH_CENTER").asLong()).isEqualTo(1);
        assertThat(counts.get("DISABILITY_WELFARE_CENTER").asLong()).isEqualTo(1);
    }

    @Test
    void filtersBySidoUsingTheSixteenOfficialNames() throws Exception {
        JsonNode seoul = search("?sido=서울특별시");
        assertThat(names(seoul)).containsExactly("시험 장애인복지관 1", "시험 특수교육지원센터 1", "시험 특수학교 1", "시험 특수학교 2");
        seoul.get("items").forEach(i -> assertThat(i.get("sido").asText()).isEqualTo("서울특별시"));

        // 가져올 때 옛 이름(강원도, 광주광역시)으로 적힌 기관은 지금 이름으로 저장된다
        JsonNode gangwon = search("?sido=강원특별자치도");
        assertThat(names(gangwon)).containsExactly("시험 정신건강복지센터 1");
        assertThat(gangwon.at("/items/0/sido").asText()).isEqualTo("강원특별자치도");
        JsonNode merged = search("?sido=전남광주통합특별시");
        assertThat(names(merged)).containsExactly("가나 시험 특수학교");
        assertThat(merged.at("/items/0/sido").asText()).isEqualTo("전남광주통합특별시");

        assertThat(search("?sido=제주특별자치도").get("total").asLong()).isZero();
        assertThat(search("?sido=").get("total").asLong()).isEqualTo(8);
    }

    @Test
    void countsIgnoreTheCategoryButFollowSidoAndQuery() throws Exception {
        JsonNode page = search("?sido=서울특별시&category=SPECIAL_SCHOOL");
        assertThat(names(page)).containsExactly("시험 특수학교 1", "시험 특수학교 2");
        assertThat(page.get("total").asLong()).isEqualTo(2);
        JsonNode counts = page.get("counts");
        assertThat(counts.get("SPECIAL_SCHOOL").asLong()).isEqualTo(2);
        assertThat(counts.get("SPECIAL_ED_SUPPORT_CENTER").asLong()).isEqualTo(1);
        assertThat(counts.get("DISABILITY_WELFARE_CENTER").asLong()).isEqualTo(1);
        assertThat(counts.get("CHILDCARE_SUPPORT_CENTER").asLong()).isZero();
        assertThat(counts.get("MENTAL_HEALTH_CENTER").asLong()).isZero();

        JsonNode withQuery = search("?category=CHILDCARE_SUPPORT_CENTER&q=시험로 4");
        assertThat(names(withQuery)).containsExactly("시험 육아종합지원센터 1");
        assertThat(withQuery.at("/counts/CHILDCARE_SUPPORT_CENTER").asLong()).isEqualTo(1);
        assertThat(withQuery.at("/counts/SPECIAL_SCHOOL").asLong()).isZero();
    }

    @Test
    void searchesNameAddressAndTags() throws Exception {
        assertThat(names(search("?q=지적장애"))).containsExactly("시험 특수학교 1");            // 태그
        assertThat(names(search("?q=  발달재활  "))).containsExactly("시험 장애인복지관 1");   // 앞뒤 공백은 지운다
        assertThat(names(search("?q=시험로 2, 2층"))).containsExactly("시험 특수학교 2");     // 주소
        assertThat(names(search("?q=육아종합"))).containsExactly("시험 육아종합지원센터 1");   // 이름
        assertThat(search("?q=").get("total").asLong()).isEqualTo(8);
        assertThat(search("?q=  ").get("total").asLong()).isEqualTo(8);
        assertThat(search("?q=없는기관").get("total").asLong()).isZero();
        // LIKE의 특수 문자는 글자 그대로 찾는다
        assertThat(names(search("?q=100%"))).containsExactly("시험 100% 센터");
        assertThat(names(search("?q=%"))).containsExactly("시험 100% 센터");
        assertThat(search("?q=_").get("total").asLong()).isZero();
        // 시·도와 함께
        assertThat(names(search("?sido=전남광주통합특별시&q=특수학교"))).containsExactly("가나 시험 특수학교");
        assertThat(search("?q=" + "가".repeat(40)).get("total").asLong()).isZero();
    }

    /** 조회만 로그인 없이 열려 있다. 같은 경로의 다른 메서드와 하위 경로는 열리지 않는다. */
    @Test
    void onlyGetIsOpenWithoutLogin() throws Exception {
        assertThat(mvc.perform(get("/api/v1/support-facilities")).andReturn().getResponse().getStatus()).isEqualTo(200);
        for (var req : List.of(post("/api/v1/support-facilities"), put("/api/v1/support-facilities"),
                patch("/api/v1/support-facilities"), delete("/api/v1/support-facilities"),
                get("/api/v1/support-facilities/1"), post("/api/v1/support-facilities/import"))) {
            MvcResult r = mvc.perform(req.contentType("application/json").content("{}")).andReturn();
            assertThat(r.getResponse().getStatus()).isEqualTo(401);
            assertThat(json(r).get("code").asText()).isEqualTo("UNAUTHORIZED");
        }
    }

    @Test
    void rejectsUnknownSidoCategoryAndLongQuery() throws Exception {
        // 조회는 16개 공식 이름만 받는다(줄인 이름·옛 이름은 가져올 때만 받는다)
        for (String query : List.of("?sido=서울특별도", "?sido=Seoul", "?sido=시험시", "?sido=서울", "?sido=강원도",
                "?sido=광주광역시", "?sido=전라남도", "?category=HOSPITAL",
                "?category=special_school", "?q=" + "가".repeat(41), "?page=abc", "?size=x")) {
            MvcResult r = call(query);
            assertThat(r.getResponse().getStatus()).as(query).isEqualTo(400);
            assertThat(json(r).get("code").asText()).as(query).isEqualTo("VALIDATION_FAILED");
        }
    }

    @Test
    void pagesAndClampsPageAndSize() throws Exception {
        JsonNode second = search("?size=3&page=1");
        assertThat(names(second)).containsExactly("시험 장애인복지관 1", "시험 정신건강복지센터 1", "시험 특수교육지원센터 1");
        assertThat(second.get("total").asLong()).isEqualTo(8);
        assertThat(second.get("page").asInt()).isEqualTo(1);
        assertThat(second.get("size").asInt()).isEqualTo(3);
        assertThat(search("?size=3&page=3").get("items")).isEmpty();

        JsonNode tooSmall = search("?size=0");
        assertThat(tooSmall.get("size").asInt()).isEqualTo(1);
        assertThat(tooSmall.get("items")).hasSize(1);
        assertThat(search("?size=51").get("size").asInt()).isEqualTo(50);
        assertThat(search("?size=50").get("size").asInt()).isEqualTo(50);
        JsonNode negative = search("?page=-1");
        assertThat(negative.get("page").asInt()).isZero();
        assertThat(negative.get("items")).hasSize(8);
    }

    @Test
    void importUpsertsByCategoryNameAndAddress() throws Exception {
        String sample = new String(new ClassPathResource("facilities/sample-facilities.csv").getInputStream()
                .readAllBytes(), StandardCharsets.UTF_8);
        // 같은 파일을 다시 넣으면 바뀌는 것이 없다
        assertThat(importer.importCsv(new StringReader(sample))).isEqualTo(new FacilityImporter.Result(0, 0, 8));

        // 전화번호가 바뀐 한 곳은 고치고, 새 기관은 더하고, 파일에 없는 기관은 지우지 않는다
        String changed = HEADER + ROW_1.replace("02-0000-0001", "02-0000-0101") + "\n"
                + "SPECIAL_SCHOOL,시험 특수학교 9,세종특별자치시,,세종특별자치시 시험로 9,,,PUBLIC,,시험 공공데이터,2026-05-01\n";
        assertThat(importer.importCsv(new StringReader(changed))).isEqualTo(new FacilityImporter.Result(1, 1, 0));
        assertThat(facilityRows()).isEqualTo(9);
        assertThat(jdbc.queryForObject("SELECT phone FROM support_facilities WHERE name = '시험 특수학교 1'",
                String.class)).isEqualTo("02-0000-0101");
        assertThat(names(search("?sido=세종특별자치시"))).containsExactly("시험 특수학교 9");

        // 이름이 같아도 주소가 다르면 다른 기관이다
        String sameNameOtherAddress = HEADER + ROW_1.replace("시험로 1,", "시험로 11,") + "\n";
        assertThat(importer.importCsv(new StringReader(sameNameOtherAddress))).isEqualTo(new FacilityImporter.Result(1, 0, 0));
        assertThat(facilityRows()).isEqualTo(10);
    }

    @Test
    void confirmedStatusSurvivesTheSameDataButNotChangedData() throws Exception {
        String confirmed = HEADER.replace("source_date\n", "source_date,status\n") + ROW_1 + ",CONFIRMED\n";
        assertThat(importer.importCsv(new StringReader(confirmed))).isEqualTo(new FacilityImporter.Result(0, 1, 0));
        assertThat(search("?q=유치원 과정").at("/items/0/status").asText()).isEqualTo("CONFIRMED");

        // status 열 없이 같은 내용을 다시 넣으면 확인 표시는 그대로
        assertThat(importer.importCsv(new StringReader(HEADER + ROW_1 + "\n"))).isEqualTo(new FacilityImporter.Result(0, 0, 1));
        assertThat(search("?q=유치원 과정").at("/items/0/status").asText()).isEqualTo("CONFIRMED");

        // 내용이 바뀌면 우리가 확인한 내용이 아니므로 공공데이터로 돌아간다
        String newer = HEADER + ROW_1.replace("2026-04-01", "2026-10-01") + "\n";
        assertThat(importer.importCsv(new StringReader(newer))).isEqualTo(new FacilityImporter.Result(0, 1, 0));
        JsonNode item = search("?q=유치원 과정").at("/items/0");
        assertThat(item.get("status").asText()).isEqualTo("PUBLIC_DATA");
        assertThat(item.at("/source/date").asText()).isEqualTo("2026-10-01");
    }

    @Test
    void aBadRowRejectsTheWholeFileAndChangesNothing() {
        String good = "SPECIAL_SCHOOL,시험 특수학교 8,서울특별시,,서울특별시 시험로 8,,,,,시험 공공데이터,2026-05-01\n";
        List<String> bad = List.of(
                HEADER.replace("tags,", "") + good,                                                      // 머리글이 다름
                HEADER + good + good,                                                                   // 같은 기관 두 번
                HEADER + good + good.replace("서울특별시,,", "서울특별도,,").replace("8,", "7,"),                  // 모르는 시·도
                HEADER + good + good.replace(",,,,,", ",,javascript:alert(1),,,").replace("8,", "7,"),     // 누리집은 http(s)만
                HEADER + good + good.replace("SPECIAL_SCHOOL", "HOSPITAL").replace("8,", "7,"),           // 모르는 유형
                HEADER + good + good.replace("2026-05-01", "2026/05/01").replace("8,", "7,"),             // 날짜 형식
                HEADER + good + "SPECIAL_SCHOOL,시험 특수학교 7,서울\n",                                    // 칸 수
                HEADER + good + good.replace("8,", "7,").replace(",,,,,", ",,,,\"" + String.join(";", java.util.stream.IntStream.rangeClosed(1, 11).mapToObj(i -> "t" + i).toList()) + "\","), // 태그 수
                HEADER + good + good.replace("8,", "7,").replace(",,,,,", ",전화,,,,"));                   // 전화번호
        for (String csv : bad) {
            assertThatThrownBy(() -> importer.importCsv(new StringReader(csv)))
                    .as(csv).isInstanceOf(FacilityImportException.class);
        }
        assertThat(facilityRows()).isEqualTo(8);
    }

    @Test
    void importFileRefusesFilesThatAreNotUtf8(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        Path euckr = dir.resolve("raw.csv");
        Files.write(euckr, (HEADER + "SPECIAL_SCHOOL,시험 특수학교 8,서울특별시,,서울특별시 시험로 8,,,,,시험 공공데이터,2026-05-01\n")
                .getBytes("EUC-KR"));
        assertThatThrownBy(() -> importer.importFile(euckr)).isInstanceOf(FacilityImportException.class)
                .hasMessageContaining("UTF-8");
        assertThatThrownBy(() -> importer.importFile(dir.resolve("missing.csv"))).isInstanceOf(FacilityImportException.class);
        assertThat(facilityRows()).isEqualTo(8);

        Path utf8 = dir.resolve("ok.csv");
        Files.writeString(utf8, Character.toString(0xFEFF) + HEADER + "SPECIAL_SCHOOL,시험 특수학교 8,서울특별시,,서울특별시 시험로 8,,,,,시험 공공데이터,2026-05-01\r\n");
        assertThat(importer.importFile(utf8)).isEqualTo(new FacilityImporter.Result(1, 0, 0));
    }
}
