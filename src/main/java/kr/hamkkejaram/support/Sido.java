package kr.hamkkejaram.support;

import java.util.Map;
import java.util.Optional;
import java.util.List;

/**
 * 16개 시·도(2026-07-01 광주광역시·전라남도가 전남광주통합특별시로 합쳐진 뒤). 저장하고 응답하는 값은 공식 이름이다.
 * 조회는 공식 이름만 받는다. 가져오기는 공공데이터 원본에 남아 있는 옛 이름도 받아 공식 이름으로 바꾼다.
 */
public final class Sido {
    private Sido() {}

    /** 공식 이름, 화면에 보이는 순서. */
    public static final List<String> NAMES = List.of(
            "서울특별시", "부산광역시", "대구광역시", "인천광역시", "대전광역시", "울산광역시", "세종특별자치시",
            "경기도", "강원특별자치도", "충청북도", "충청남도", "전북특별자치도", "전남광주통합특별시",
            "경상북도", "경상남도", "제주특별자치도");

    /** 가져올 때만 받는 옛 이름 → 공식 이름. 이 표에 없는 이름은 받지 않는다. */
    static final Map<String, String> FORMER_NAMES = Map.of(
            "광주광역시", "전남광주통합특별시",
            "전라남도", "전남광주통합특별시",
            "강원도", "강원특별자치도",
            "전라북도", "전북특별자치도",
            "제주도", "제주특별자치도",
            "세종자치시", "세종특별자치시");

    /** 조회용: 16개 공식 이름 가운데 하나일 때만. */
    public static Optional<String> official(String raw) {
        if (raw == null) return Optional.empty();
        String s = raw.strip();
        return NAMES.contains(s) ? Optional.of(s) : Optional.empty();
    }

    /** 가져오기용: 공식 이름 또는 {@link #FORMER_NAMES}의 옛 이름을 공식 이름으로. */
    public static Optional<String> forImport(String raw) {
        if (raw == null) return Optional.empty();
        String s = raw.strip();
        if (NAMES.contains(s)) return Optional.of(s);
        return Optional.ofNullable(FORMER_NAMES.get(s));
    }
}
