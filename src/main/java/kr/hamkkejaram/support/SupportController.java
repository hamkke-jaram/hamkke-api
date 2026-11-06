package kr.hamkkejaram.support;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;

/** 상담·기관 연결 안내. 안전 기능이라 로그인·결제와 상관없이 항상 제공한다. */
@RestController
@RequestMapping("/api/v1/support-resources")
public class SupportController {
    private final SupportResourceRepository repository;

    public SupportController(SupportResourceRepository repository) {
        this.repository = repository;
    }

    public record SupportResourceResponse(long id, String name, String description, String phone, String url,
                                          String hours, String category) {
        public static SupportResourceResponse from(SupportResource r) {
            return new SupportResourceResponse(r.getId(), r.getName(), r.getDescription(), r.getPhone(),
                    r.getUrl(), r.getHours(), r.getCategory());
        }
    }

    @GetMapping
    @Transactional(readOnly = true)
    public ResponseEntity<List<SupportResourceResponse>> list() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic())
                .body(all());
    }

    @Transactional(readOnly = true)
    public List<SupportResourceResponse> all() {
        return repository.findAllByOrderBySortOrderAscIdAsc().stream().map(SupportResourceResponse::from).toList();
    }
}
