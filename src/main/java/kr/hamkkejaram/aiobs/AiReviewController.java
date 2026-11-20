package kr.hamkkejaram.aiobs;

import jakarta.validation.Valid;
import kr.hamkkejaram.aiobs.AiObservationDtos.ObservationResponse;
import kr.hamkkejaram.aiobs.AiReviewDtos.PublishRequest;
import kr.hamkkejaram.aiobs.AiReviewDtos.TeacherReview;
import kr.hamkkejaram.security.AuthUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/children/{childId}/ai-observations/{observationId}/review")
public class AiReviewController {
    private final AiReviewService service;

    public AiReviewController(AiReviewService service) { this.service = service; }

    @GetMapping
    public TeacherReview review(@AuthenticationPrincipal AuthUser user, @PathVariable long childId,
                                @PathVariable long observationId) {
        return service.review(user, childId, observationId);
    }

    @PostMapping("/publish")
    public ObservationResponse publish(@AuthenticationPrincipal AuthUser user, @PathVariable long childId,
                                       @PathVariable long observationId, @Valid @RequestBody PublishRequest request) {
        return service.publish(user, childId, observationId, request);
    }
}
