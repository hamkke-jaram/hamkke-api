package kr.hamkkejaram.share;

import jakarta.validation.Valid;
import kr.hamkkejaram.security.AuthUser;
import kr.hamkkejaram.share.ShareDtos.InviteCodeResponse;
import kr.hamkkejaram.share.ShareDtos.LinkedTeacherResponse;
import kr.hamkkejaram.share.ShareDtos.ShareSettingsRequest;
import kr.hamkkejaram.share.ShareDtos.ShareSettingsResponse;
import kr.hamkkejaram.share.ShareDtos.UsableInviteCodeResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/children/{childId}")
public class ShareController {
    private final ShareService service;

    public ShareController(ShareService service) {
        this.service = service;
    }

    @GetMapping("/share-settings")
    public ShareSettingsResponse get(@AuthenticationPrincipal AuthUser user, @PathVariable long childId) {
        return service.getSettings(user, childId);
    }

    @PutMapping("/share-settings")
    public ShareSettingsResponse update(@AuthenticationPrincipal AuthUser user, @PathVariable long childId,
                                        @Valid @RequestBody ShareSettingsRequest req) {
        return service.updateSettings(user, childId, req);
    }

    @PostMapping("/invite-codes")
    @ResponseStatus(HttpStatus.CREATED)
    public InviteCodeResponse issue(@AuthenticationPrincipal AuthUser user, @PathVariable long childId) {
        return service.issueInviteCode(user, childId);
    }

    /** 아직 쓸 수 있는 코드만, 최신 발급순. */
    @GetMapping("/invite-codes")
    public List<UsableInviteCodeResponse> inviteCodes(@AuthenticationPrincipal AuthUser user,
                                                      @PathVariable long childId) {
        return service.usableInviteCodes(user, childId);
    }

    @DeleteMapping("/invite-codes/{code}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeInviteCode(@AuthenticationPrincipal AuthUser user, @PathVariable long childId,
                                 @PathVariable String code) {
        service.revokeInviteCode(user, childId, code);
    }

    @GetMapping("/teachers")
    public List<LinkedTeacherResponse> teachers(@AuthenticationPrincipal AuthUser user, @PathVariable long childId) {
        return service.linkedTeachers(user, childId);
    }

    @DeleteMapping("/teachers/{teacherId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unlink(@AuthenticationPrincipal AuthUser user, @PathVariable long childId,
                       @PathVariable long teacherId) {
        service.unlinkTeacher(user, childId, teacherId);
    }
}
