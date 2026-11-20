package kr.hamkkejaram.support;

import java.io.IOException;
import java.io.Reader;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 정규화한 기관 CSV 읽기(SUP-04). UTF-8, 첫 줄은 머리글, 쉼표 구분, 큰따옴표로 감싼 칸 안의 쉼표·줄바꿈·""는 그대로.
 * 머리글은 {@link #COLUMNS} 그대로이거나 그 뒤에 {@code status} 하나를 더한 것만 받는다.
 * 한 줄이라도 틀리면 전체를 받지 않는다(일부만 들어가 목록이 어긋나는 일을 막는다).
 */
final class FacilityCsv {
    private FacilityCsv() {}

    static final List<String> COLUMNS = List.of("category", "name", "sido", "sigungu", "address", "phone",
            "homepage", "operator", "tags", "source_name", "source_date");
    static final String STATUS_COLUMN = "status";

    static final int MAX_ROWS = 50_000;
    static final int MAX_TAGS = 10;
    static final int MAX_TAG_LENGTH = 20;
    /**
     * 국내 전화번호 모양(마지막 묶음 4자리). 화면이 이 값으로 바로 전화를 걸기 때문에 모양이 다르면 받지 않는다.
     * scripts/facilities/convert.mjs의 PHONE과 같은 식이다(스크립트 테스트가 비교한다).
     * 02 서울, 031~033·041~044·051~055·061~064 지역번호, 070·080, 010·011·016~019 휴대전화, 050X 안심번호, 1[5-9]XX 대표번호.
     */
    static final Pattern PHONE = Pattern.compile(
            "^(?:02-\\d{3,4}|0(?:3[1-3]|4[1-4]|5[1-5]|6[1-4]|70|80)-\\d{3,4}|01[016789]-\\d{3,4}|050\\d-\\d{4}|1[5-9]\\d{2})-\\d{4}$");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    static List<FacilityRow> parse(Reader reader) throws IOException {
        List<List<String>> records = records(reader);
        if (records.isEmpty()) throw new FacilityImportException("머리글이 없어요.");
        List<String> header = records.get(0).stream().map(String::strip).toList();
        boolean hasStatus;
        if (header.equals(COLUMNS)) {
            hasStatus = false;
        } else if (header.size() == COLUMNS.size() + 1 && header.subList(0, COLUMNS.size()).equals(COLUMNS)
                && header.get(COLUMNS.size()).equals(STATUS_COLUMN)) {
            hasStatus = true;
        } else {
            throw new FacilityImportException("머리글이 약속한 열(" + String.join(",", COLUMNS) + "[,status])과 달라요.");
        }
        if (records.size() - 1 > MAX_ROWS) throw new FacilityImportException("줄이 " + MAX_ROWS + "개보다 많아요.");

        List<FacilityRow> rows = new ArrayList<>();
        Map<FacilityRow.Key, Integer> seen = new HashMap<>();
        for (int i = 1; i < records.size(); i++) {
            List<String> cells = records.get(i);
            int line = i + 1;
            if (cells.size() == 1 && cells.get(0).isBlank()) continue; // 빈 줄
            if (cells.size() != header.size()) {
                throw FacilityImportException.at(line, "-", "칸 수가 " + header.size() + "개가 아니에요(" + cells.size() + "개).");
            }
            FacilityRow row = row(line, cells, hasStatus);
            Integer before = seen.putIfAbsent(row.key(), line);
            if (before != null) {
                throw FacilityImportException.at(line, "category,name,address", before + "번째 줄과 같은 기관이에요.");
            }
            rows.add(row);
        }
        return rows;
    }

    private static FacilityRow row(int line, List<String> c, boolean hasStatus) {
        FacilityCategory category = enumValue(line, "category", c.get(0), FacilityCategory.class, true);
        String name = text(line, "name", c.get(1), 100, true);
        String sido = Sido.forImport(c.get(2))
                .orElseThrow(() -> FacilityImportException.at(line, "sido", "16개 시·도 이름이나 정해진 옛 이름이 아니에요."));
        String sigungu = text(line, "sigungu", c.get(3), 30, false);
        String address = text(line, "address", c.get(4), 200, true);
        String phone = text(line, "phone", c.get(5), 30, false);
        if (phone != null && !PHONE.matcher(phone).matches()) {
            throw FacilityImportException.at(line, "phone",
                    "국내 전화번호 모양(예: 02-123-4567, 0507-1234-5678, 1577-0199)이 아니에요. 변환 스크립트로 먼저 바꿔 주세요.");
        }
        String homepage = text(line, "homepage", c.get(6), 300, false);
        if (homepage != null && !(homepage.startsWith("https://") || homepage.startsWith("http://"))) {
            throw FacilityImportException.at(line, "homepage", "http:// 또는 https://로 시작해야 해요.");
        }
        FacilityOperator operator = enumValue(line, "operator", c.get(7), FacilityOperator.class, false);
        List<String> tags = tags(line, c.get(8));
        String sourceName = text(line, "source_name", c.get(9), 100, true);
        LocalDate sourceDate;
        try {
            sourceDate = LocalDate.parse(c.get(10).strip());
        } catch (DateTimeParseException e) {
            throw FacilityImportException.at(line, "source_date", "YYYY-MM-DD 날짜가 아니에요.");
        }
        FacilityStatus status = hasStatus ? enumValue(line, "status", c.get(11), FacilityStatus.class, false) : null;
        return new FacilityRow(line, category, name, sido, sigungu, address, phone, homepage, operator, tags,
                sourceName, sourceDate, status);
    }

    /** 앞뒤 공백을 지우고 안의 연속 공백을 하나로. 비면 null. */
    static String clean(String raw) {
        if (raw == null) return null;
        String s = SPACES.matcher(raw.strip()).replaceAll(" ");
        return s.isEmpty() ? null : s;
    }

    private static String text(int line, String column, String raw, int max, boolean required) {
        String s = clean(raw);
        if (s == null) {
            if (required) throw FacilityImportException.at(line, column, "비어 있어요.");
            return null;
        }
        if (s.length() > max) throw FacilityImportException.at(line, column, max + "자보다 길어요.");
        if (s.chars().anyMatch(Character::isISOControl)) throw FacilityImportException.at(line, column, "제어 문자가 있어요.");
        return s;
    }

    private static <E extends Enum<E>> E enumValue(int line, String column, String raw, Class<E> type, boolean required) {
        String s = clean(raw);
        if (s == null) {
            if (required) throw FacilityImportException.at(line, column, "비어 있어요.");
            return null;
        }
        try {
            return Enum.valueOf(type, s);
        } catch (IllegalArgumentException e) {
            throw FacilityImportException.at(line, column, "정해진 값이 아니에요.");
        }
    }

    private static List<String> tags(int line, String raw) {
        if (clean(raw) == null) return List.of();
        Set<String> out = new LinkedHashSet<>();
        for (String part : raw.split(SupportFacility.TAG_SEPARATOR)) {
            String t = clean(part);
            if (t == null) continue;
            if (t.length() > MAX_TAG_LENGTH) throw FacilityImportException.at(line, "tags", "태그가 " + MAX_TAG_LENGTH + "자보다 길어요.");
            out.add(t);
        }
        if (out.size() > MAX_TAGS) throw FacilityImportException.at(line, "tags", "태그가 " + MAX_TAGS + "개보다 많아요.");
        if (String.join(SupportFacility.TAG_SEPARATOR, out).length() > 300) {
            throw FacilityImportException.at(line, "tags", "태그를 합친 길이가 300자보다 길어요.");
        }
        return List.copyOf(out);
    }

    /** RFC 4180 방식으로 줄과 칸을 나눈다. 맨 앞의 BOM은 버린다. */
    static List<List<String>> records(Reader reader) throws IOException {
        List<List<String>> out = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        boolean any = false;
        int ch = reader.read();
        if (ch == 0xFEFF) ch = reader.read();
        while (ch != -1) {
            any = true;
            if (quoted) {
                if (ch == '"') {
                    int next = reader.read();
                    if (next == '"') {
                        cell.append('"');
                    } else {
                        quoted = false;
                        ch = next;
                        continue;
                    }
                } else {
                    cell.append((char) ch);
                }
            } else if (ch == '"' && cell.isEmpty()) {
                quoted = true;
            } else if (ch == ',') {
                row.add(cell.toString());
                cell.setLength(0);
            } else if (ch == '\r' || ch == '\n') {
                if (ch == '\r') {
                    int next = reader.read();
                    if (next != '\n') {
                        row.add(cell.toString());
                        cell.setLength(0);
                        out.add(row);
                        row = new ArrayList<>();
                        any = false;
                        ch = next;
                        continue;
                    }
                }
                row.add(cell.toString());
                cell.setLength(0);
                out.add(row);
                row = new ArrayList<>();
                any = false;
            } else {
                cell.append((char) ch);
            }
            ch = reader.read();
        }
        if (quoted) throw new FacilityImportException("큰따옴표가 닫히지 않았어요.");
        if (any) {
            row.add(cell.toString());
            out.add(row);
        }
        return out;
    }
}
