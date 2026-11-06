package kr.hamkkejaram.card;

import kr.hamkkejaram.card.CardDtos.DeliveryResponse;
import kr.hamkkejaram.child.ChildAccess;
import kr.hamkkejaram.common.ApiException;
import kr.hamkkejaram.security.AuthUser;
import kr.hamkkejaram.share.ShareSettingRepository;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/children/{childId}/card-deliveries")
public class CardController {
    private final CoachingService coaching;
    private final ChildAccess access;
    private final ShareSettingRepository shareSettings;

    public CardController(CoachingService coaching, ChildAccess access, ShareSettingRepository shareSettings) {
        this.coaching = coaching;
        this.access = access;
        this.shareSettings = shareSettings;
    }

    /** 부모 또는 "받은 코칭 카드" 공유가 켜진 연결 교사. */
    @GetMapping
    @Transactional(readOnly = true)
    public List<DeliveryResponse> list(@AuthenticationPrincipal AuthUser user, @PathVariable long childId,
                                       @RequestParam(defaultValue = "20") int limit) {
        access.requireOwnerOrLinkedTeacher(user, childId);
        if (user.isTeacher()) {
            boolean shared = shareSettings.findById(childId).map(s -> s.isCoachingCards()).orElse(false);
            if (!shared) throw ApiException.notFound();
        }
        return coaching.recent(childId, Math.max(1, Math.min(50, limit)));
    }

    @PostMapping("/{deliveryId}/tried")
    public DeliveryResponse markTried(@AuthenticationPrincipal AuthUser user, @PathVariable long childId,
                                      @PathVariable long deliveryId) {
        access.requireOwner(user, childId);
        return coaching.markTried(childId, deliveryId);
    }
}
