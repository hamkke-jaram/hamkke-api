package kr.hamkkejaram.share;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import kr.hamkkejaram.security.AuthUser;
import kr.hamkkejaram.security.RateLimiter;
import kr.hamkkejaram.share.ShareDtos.LinkRequest;
import kr.hamkkejaram.share.ShareDtos.SharedSummaryResponse;
import kr.hamkkejaram.share.ShareDtos.TeacherChildResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/teacher")
public class TeacherController {
    private final ShareService service;
    private final RateLimiter rateLimiter;

    public TeacherController(ShareService service, RateLimiter rateLimiter) {
        this.service = service;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping("/links")
    @ResponseStatus(HttpStatus.CREATED)
    public TeacherChildResponse link(@AuthenticationPrincipal AuthUser user, @Valid @RequestBody LinkRequest req,
                                     HttpServletRequest http) {
        rateLimiter.checkAuthRequest(http.getRemoteAddr()); // 코드 대입 시도 제한
        return service.link(user, req.code());
    }

    /** 선생님이 스스로 연결을 끊는다. 이미 쓴 목표·관찰 메모는 남는다(TCH-07). */
    @DeleteMapping("/links/{childId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unlink(@AuthenticationPrincipal AuthUser user, @PathVariable long childId) {
        service.unlinkSelf(user, childId);
    }

    @GetMapping("/children")
    public List<TeacherChildResponse> children(@AuthenticationPrincipal AuthUser user) {
        return service.teacherChildren(user);
    }

    @GetMapping("/children/{childId}/shared-summary")
    public SharedSummaryResponse sharedSummary(@AuthenticationPrincipal AuthUser user, @PathVariable long childId,
                                               @RequestParam(defaultValue = "8") int weeks) {
        return service.sharedSummary(user, childId, weeks);
    }
}
