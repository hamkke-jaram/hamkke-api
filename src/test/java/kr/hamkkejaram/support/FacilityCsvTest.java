package kr.hamkkejaram.support;

import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FacilityCsvTest {
    private static final String HEADER = String.join(",", FacilityCsv.COLUMNS);

    private static List<FacilityRow> parse(String csv) throws Exception {
        return FacilityCsv.parse(new StringReader(csv));
    }

    @Test
    void readsQuotedCellsCrLfAndBom() throws Exception {
        String csv = Character.toString(0xFEFF) + HEADER + "\r\n"
                + "SPECIAL_SCHOOL,\"시험 \"\"새싹\"\" 학교\",서울특별시,시험구,\"서울특별시 시험구 시험로 1, 2층\","
                + "02-0000-0001,https://example.com,PRIVATE,\" 유치원 과정 ; 지적장애;;유치원 과정 \",시험 공공데이터,2026-04-01\r\n"
                + "\r\n";
        List<FacilityRow> rows = parse(csv);
        assertThat(rows).hasSize(1);
        FacilityRow r = rows.get(0);
        assertThat(r.name()).isEqualTo("시험 \"새싹\" 학교");
        assertThat(r.sido()).isEqualTo("서울특별시");
        assertThat(r.address()).isEqualTo("서울특별시 시험구 시험로 1, 2층");
        assertThat(r.operator()).isEqualTo(FacilityOperator.PRIVATE);
        assertThat(r.tags()).containsExactly("유치원 과정", "지적장애"); // 앞뒤 공백·빈 칸·중복은 버린다
        assertThat(r.joinedTags()).isEqualTo("유치원 과정;지적장애");
        assertThat(r.sourceDate()).isEqualTo(LocalDate.of(2026, 4, 1));
        assertThat(r.status()).isNull();
        assertThat(r.line()).isEqualTo(2);
    }

    @Test
    void collapsesSpacesSoTheKeyIsStable() throws Exception {
        FacilityRow r = parse(HEADER + "\nSPECIAL_SCHOOL,  시험   특수학교  1 ,서울특별시,,서울특별시   시험로 1,,,,,시험 공공데이터,2026-04-01")
                .get(0);
        assertThat(r.name()).isEqualTo("시험 특수학교 1");
        assertThat(r.address()).isEqualTo("서울특별시 시험로 1");
        assertThat(r.sigungu()).isNull();
        assertThat(r.phone()).isNull();
        assertThat(r.tags()).isEmpty();
        assertThat(r.joinedTags()).isNull();
    }

    @Test
    void acceptsAnOptionalStatusColumnOnlyAtTheEnd() throws Exception {
        String row = "SPECIAL_SCHOOL,시험 특수학교 1,서울특별시,,서울특별시 시험로 1,,,,,시험 공공데이터,2026-04-01";
        assertThat(parse(HEADER + ",status\n" + row + ",CONFIRMED").get(0).status()).isEqualTo(FacilityStatus.CONFIRMED);
        assertThat(parse(HEADER + ",status\n" + row + ",").get(0).status()).isNull();
        assertThatThrownBy(() -> parse(HEADER + ",status\n" + row + ",VERIFIED")).isInstanceOf(FacilityImportException.class);
        assertThatThrownBy(() -> parse("status," + HEADER + "\n,CONFIRMED" + row)).isInstanceOf(FacilityImportException.class);
    }

    @Test
    void sidoTakesTheSixteenNamesAndOnlyTheListedFormerNames() throws Exception {
        String row = "SPECIAL_SCHOOL,시험 특수학교 1,%s,,시험시 시험로 1,,,,,시험 공공데이터,2026-04-01";
        String[][] mapped = {
                {"광주광역시", "전남광주통합특별시"}, {"전라남도", "전남광주통합특별시"}, {"강원도", "강원특별자치도"},
                {"전라북도", "전북특별자치도"}, {"제주도", "제주특별자치도"}, {"세종자치시", "세종특별자치시"},
                {"전남광주통합특별시", "전남광주통합특별시"}, {" 경기도 ", "경기도"}};
        for (String[] m : mapped) {
            assertThat(parse(HEADER + "\n" + row.formatted(m[0])).get(0).sido()).as(m[0]).isEqualTo(m[1]);
        }
        assertThat(Sido.NAMES).hasSize(16).doesNotHaveDuplicates()
                .doesNotContain("광주광역시", "전라남도", "강원도", "전라북도", "제주도");
        for (String name : Sido.NAMES) {
            assertThat(parse(HEADER + "\n" + row.formatted(name)).get(0).sido()).isEqualTo(name);
        }
        // 줄인 이름·표에 없는 이름은 멈춘다
        for (String bad : new String[]{"서울", "경기", "제주특별자치시", "세종시", "광주", "시험도"}) {
            assertThatThrownBy(() -> parse(HEADER + "\n" + row.formatted(bad))).as(bad)
                    .isInstanceOf(FacilityImportException.class).hasMessageContaining("sido");
        }
    }

    @Test
    void searchTakesOnlyTheSixteenOfficialNames() {
        assertThat(Sido.official("전남광주통합특별시")).contains("전남광주통합특별시");
        assertThat(Sido.official(" 서울특별시 ")).contains("서울특별시");
        assertThat(Sido.official("광주광역시")).isEmpty();
        assertThat(Sido.official("강원도")).isEmpty();
        assertThat(Sido.official("서울")).isEmpty();
        assertThat(Sido.official(null)).isEmpty();
    }

    @Test
    void errorsNameTheLineAndColumnButNotTheValue() {
        String csv = HEADER + "\nSPECIAL_SCHOOL,시험 특수학교 1,비밀시,,서울특별시 시험로 1,,,,,시험 공공데이터,2026-04-01";
        assertThatThrownBy(() -> parse(csv)).isInstanceOf(FacilityImportException.class)
                .hasMessageContaining("2번째 줄").hasMessageContaining("sido").hasMessageNotContaining("비밀시");
    }

    @Test
    void phoneMustLookLikeAKoreanNumber() throws Exception {
        String row = "SPECIAL_SCHOOL,시험 특수학교 1,서울특별시,,서울특별시 시험로 1,%s,,,,시험 공공데이터,2026-04-01";
        for (String ok : new String[]{"02-123-4567", "02-1234-5678", "033-251-1970", "070-0000-0000", "010-0000-0000",
                "0507-0000-0000", "1577-0199", "080-000-0000", ""}) {
            FacilityRow r = parse(HEADER + "\n" + row.formatted(ok)).get(0);
            assertThat(r.phone()).as(ok).isEqualTo(ok.isEmpty() ? null : ok);
        }
        // 내선이 붙은 번호, 지역번호가 아닌 앞자리, 묶음 수가 다른 번호, '-' 없는 번호는 줄이 아니라 파일 전체를 거절한다.
        // 변환 스크립트(convert.mjs)가 먼저 그 칸을 비우므로, 여기까지 오면 손으로 고친 파일이다.
        for (String bad : new String[]{"033-251-1970240", "033-251-1970(240)", "000-0000-0001", "039-000-0000",
                "02-12-3456", "1577-019", "0212345678", "112", "02-123-4567-1"}) {
            assertThatThrownBy(() -> parse(HEADER + "\n" + row.formatted(bad))).as(bad)
                    .isInstanceOf(FacilityImportException.class).hasMessageContaining("phone");
        }
    }

    @Test
    void rejectsEmptyFilesAndOpenQuotes() {
        assertThatThrownBy(() -> parse("")).isInstanceOf(FacilityImportException.class);
        assertThatThrownBy(() -> parse(HEADER + "\nSPECIAL_SCHOOL,\"시험,서울")).isInstanceOf(FacilityImportException.class);
    }

    @Test
    void headerOnlyMeansNothingToImport() throws Exception {
        assertThat(parse(HEADER + "\n")).isEmpty();
    }
}
