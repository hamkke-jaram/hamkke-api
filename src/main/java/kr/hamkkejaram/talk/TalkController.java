package kr.hamkkejaram.talk;

import jakarta.validation.Valid;
import kr.hamkkejaram.security.AuthUser;
import kr.hamkkejaram.talk.TalkDtos.MessageResponse;
import kr.hamkkejaram.talk.TalkDtos.ReadRequest;
import kr.hamkkejaram.talk.TalkDtos.SendMessageRequest;
import kr.hamkkejaram.talk.TalkDtos.UnreadResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 보호자와 선생님이 주고받는 이야기(TLK). */
@RestController
@RequestMapping("/api/v1/children/{childId}/messages")
public class TalkController {
    private final TalkService service;

    public TalkController(TalkService service) {
        this.service = service;
    }

    @GetMapping
    public List<MessageResponse> list(@AuthenticationPrincipal AuthUser user, @PathVariable long childId,
                                      @RequestParam(required = false) Long afterId,
                                      @RequestParam(defaultValue = "50") int limit) {
        return service.list(user, childId, afterId, limit);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MessageResponse send(@AuthenticationPrincipal AuthUser user, @PathVariable long childId,
                                @Valid @RequestBody SendMessageRequest req) {
        return service.send(user, childId, req);
    }

    @PostMapping("/read")
    public UnreadResponse read(@AuthenticationPrincipal AuthUser user, @PathVariable long childId,
                               @Valid @RequestBody ReadRequest req) {
        return service.read(user, childId, req.lastReadId());
    }

    @GetMapping("/unread")
    public UnreadResponse unread(@AuthenticationPrincipal AuthUser user, @PathVariable long childId) {
        return service.unread(user, childId);
    }
}
