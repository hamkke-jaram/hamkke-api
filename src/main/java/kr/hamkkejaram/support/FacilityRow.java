package kr.hamkkejaram.support;

import java.time.LocalDate;
import java.util.List;

/**
 * 정규화한 CSV의 한 줄을 검사한 값. 키는 (category, name, address).
 * status가 null이면 CSV에 status 열이 없었다는 뜻이다(가져오기가 정한다).
 */
record FacilityRow(int line, FacilityCategory category, String name, String sido, String sigungu, String address,
                   String phone, String homepage, FacilityOperator operator, List<String> tags,
                   String sourceName, LocalDate sourceDate, FacilityStatus status) {

    record Key(FacilityCategory category, String name, String address) {}

    Key key() {
        return new Key(category, name, address);
    }

    String joinedTags() {
        return tags.isEmpty() ? null : String.join(SupportFacility.TAG_SEPARATOR, tags);
    }
}
