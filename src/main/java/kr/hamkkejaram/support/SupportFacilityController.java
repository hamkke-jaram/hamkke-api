package kr.hamkkejaram.support;

import kr.hamkkejaram.common.ApiException;
import kr.hamkkejaram.common.ErrorCode;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 지역 기관 찾기(SUP-03). 공공데이터로 만든 공개 목록이라 인증 없이 준다.
 * 아이·사용자 데이터를 읽지 않고, 기관을 평가하거나 순위를 매기지 않는다(정렬은 이름순뿐).
 */
@RestController
@RequestMapping("/api/v1/support-facilities")
public class SupportFacilityController {
    static final int MAX_QUERY_LENGTH = 40;
    static final int MAX_SIZE = 50;
    static final int MAX_PAGE = 10_000;

    private final SupportFacilityRepository repository;

    public SupportFacilityController(SupportFacilityRepository repository) {
        this.repository = repository;
    }

    public record Source(String name, LocalDate date) {}

    public record FacilityResponse(long id, FacilityCategory category, String name, String sido, String sigungu,
                                   String address, String phone, String homepage, FacilityOperator operator,
                                   List<String> tags, Source source, FacilityStatus status) {
        static FacilityResponse from(SupportFacility f) {
            return new FacilityResponse(f.getId(), f.getCategory(), f.getName(), f.getSido(), f.getSigungu(),
                    f.getAddress(), f.getPhone(), f.getHomepage(), f.getOperator(), f.getTagList(),
                    new Source(f.getSourceName(), f.getSourceDate()), f.getStatus());
        }
    }

    public record FacilityPage(List<FacilityResponse> items, long total, int page, int size,
                               Map<FacilityCategory, Long> counts) {}

    @GetMapping
    @Transactional(readOnly = true)
    public ResponseEntity<FacilityPage> search(@RequestParam(required = false) String sido,
                                               @RequestParam(required = false) String category,
                                               @RequestParam(required = false) String q,
                                               @RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "20") int size) {
        String sidoName = blank(sido) ? null : Sido.official(sido)
                .orElseThrow(() -> invalid("시·도 이름을 다시 확인해 주세요."));
        FacilityCategory cat = blank(category) ? null : parseCategory(category.strip());
        String pattern = pattern(q);
        int p = Math.max(0, Math.min(MAX_PAGE, page));
        int s = Math.max(1, Math.min(MAX_SIZE, size));

        Page<SupportFacility> found = repository.search(sidoName, cat, pattern,
                PageRequest.of(p, s, Sort.by("name", "id")));
        Map<FacilityCategory, Long> counts = new LinkedHashMap<>();
        for (FacilityCategory c : FacilityCategory.values()) counts.put(c, 0L);
        for (Object[] row : repository.countByCategory(sidoName, pattern)) {
            counts.put((FacilityCategory) row[0], ((Number) row[1]).longValue());
        }
        FacilityPage body = new FacilityPage(found.getContent().stream().map(FacilityResponse::from).toList(),
                found.getTotalElements(), p, s, counts);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic())
                .body(body);
    }

    /** 앞뒤 공백을 지우고 0~40자. 비면 null(조건 없음). LIKE 특수 문자는 '!'로 이스케이프한다. */
    static String pattern(String q) {
        String s = FacilityCsv.clean(q);
        if (s == null) return null;
        if (s.length() > MAX_QUERY_LENGTH) throw invalid("검색어는 " + MAX_QUERY_LENGTH + "자까지 쓸 수 있어요.");
        String escaped = s.toLowerCase(Locale.ROOT)
                .replace("!", "!!").replace("%", "!%").replace("_", "!_");
        return "%" + escaped + "%";
    }

    private static FacilityCategory parseCategory(String raw) {
        try {
            return FacilityCategory.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw invalid("기관 유형을 다시 확인해 주세요.");
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static ApiException invalid(String message) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, message);
    }
}
