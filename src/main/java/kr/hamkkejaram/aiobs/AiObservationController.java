package kr.hamkkejaram.aiobs;

import jakarta.validation.Valid;
import kr.hamkkejaram.aiobs.AiObservationDtos.AiConsentRequest;
import kr.hamkkejaram.aiobs.AiObservationDtos.AiConsentResponse;
import kr.hamkkejaram.aiobs.AiObservationDtos.AiSessionResponse;
import kr.hamkkejaram.aiobs.AiObservationDtos.HomeScene;
import kr.hamkkejaram.aiobs.AiObservationDtos.ObservationResponse;
import kr.hamkkejaram.aiobs.AiObservationDtos.SaveObservationRequest;
import kr.hamkkejaram.aiobs.AiObservationDtos.SaveObservationResponse;
import kr.hamkkejaram.aiobs.AiObservationDtos.TeacherVisibilityRequest;
import kr.hamkkejaram.security.AuthUser;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * AI 관찰 기록 API(docs/ai-observation.md 7장). 이 서버는 사진을 받지 않는다:
 * 동의·분석 토큰·영수증(글자)만 오간다. 사진은 웹이 hamkke-ai 서버로 직접 보낸다.
 */
@RestController
@RequestMapping("/api/v1/children/{childId}")
public class AiObservationController {
    private final AiConsentService consent;
    private final AiObservationService service;

    public AiObservationController(AiConsentService consent, AiObservationService service) {
        this.consent = consent;
        this.service = service;
    }

    @GetMapping("/ai-consent")
    public AiConsentResponse consent(@AuthenticationPrincipal AuthUser user, @PathVariable long childId) {
        return consent.get(user, childId);
    }

    @PutMapping("/ai-consent")
    public AiConsentResponse agree(@AuthenticationPrincipal AuthUser user, @PathVariable long childId,
                                   @Valid @RequestBody AiConsentRequest req) {
        return consent.agree(user, childId, req);
    }

    /** 철회 + 그 아이의 AI 관찰 기록 전부 삭제. */
    @DeleteMapping("/ai-consent")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@AuthenticationPrincipal AuthUser user, @PathVariable long childId) {
        consent.revoke(user, childId);
    }

    @PostMapping("/ai-sessions")
    @ResponseStatus(HttpStatus.CREATED)
    public AiSessionResponse issueSession(@AuthenticationPrincipal AuthUser user, @PathVariable long childId) {
        return service.issueSession(user, childId);
    }

    @PostMapping("/ai-observations")
    @ResponseStatus(HttpStatus.CREATED)
    public SaveObservationResponse save(@AuthenticationPrincipal AuthUser user, @PathVariable long childId,
                                        @Valid @RequestBody SaveObservationRequest req) {
        return service.save(user, childId, req);
    }

    @GetMapping("/ai-observations")
    public List<ObservationResponse> list(@AuthenticationPrincipal AuthUser user, @PathVariable long childId,
                                          @RequestParam(defaultValue = "20") int limit,
                                          @RequestParam(required = false) Long beforeId) {
        return service.list(user, childId, limit, beforeId);
    }

    /** 가정 장면 요약 목록(선생님용으로 줄인 형태). 보호자는 미리보기로, 선생님은 공유가 켜져 있을 때 본다. */
    @GetMapping("/home-scenes")
    public List<HomeScene> homeScenes(@AuthenticationPrincipal AuthUser user, @PathVariable long childId,
                                      @RequestParam(defaultValue = "20") int limit,
                                      @RequestParam(required = false) Long beforeId) {
        return service.homeScenes(user, childId, limit, beforeId);
    }

    /** 보호자: 가정 기록 한 건을 선생님에게 보낼지(hidden=false) 보내지 않을지(hidden=true). */
    @PutMapping("/ai-observations/{observationId}/teacher-visibility")
    public ObservationResponse setTeacherVisibility(@AuthenticationPrincipal AuthUser user, @PathVariable long childId,
                                                    @PathVariable long observationId,
                                                    @Valid @RequestBody TeacherVisibilityRequest req) {
        return service.setTeacherVisibility(user, childId, observationId, req.hidden());
    }

    @DeleteMapping("/ai-observations/{observationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AuthUser user, @PathVariable long childId,
                       @PathVariable long observationId) {
        service.delete(user, childId, observationId);
    }
}
