package kr.hamkkejaram.card;

import jakarta.validation.constraints.Pattern;
import kr.hamkkejaram.child.ChildAccess;
import kr.hamkkejaram.common.ApiException;
import kr.hamkkejaram.common.TimeConfig;
import kr.hamkkejaram.security.AuthUser;
import kr.hamkkejaram.share.ShareSetting;
import kr.hamkkejaram.share.ShareSettingRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;

@RestController
public class DaycareCoachingController {
    private final CardDeliveryRepository deliveries;
    private final ChildAccess access;
    private final ShareSettingRepository settings;
    private final Clock clock;

    public DaycareCoachingController(CardDeliveryRepository deliveries, ChildAccess access,
                                     ShareSettingRepository settings, Clock clock) {
        this.deliveries = deliveries;
        this.access = access;
        this.settings = settings;
        this.clock = clock;
    }

    @GetMapping("/api/v1/teacher/children/{childId}/daycare-coaching/cards")
    @Transactional(readOnly = true)
    public List<SharedCoachingCard> cards(@AuthenticationPrincipal AuthUser user, @PathVariable long childId,
            @RequestParam @Pattern(regexp = "[0-9]{4}-(0[1-9]|1[0-2])") String month) {
        access.requireLinkedTeacher(user, childId);
        if (!settings.findById(childId).map(ShareSetting::isCoachingCards).orElse(false)) {
            throw ApiException.notFound();
        }
        YearMonth selected = YearMonth.parse(month);
        if (selected.getYear() < 1000 || selected.isAfter(YearMonth.now(clock.withZone(TimeConfig.SERVICE_ZONE)))) {
            return List.of();
        }
        Instant start = selected.atDay(1).atStartOfDay(TimeConfig.SERVICE_ZONE).toInstant();
        Instant end = selected.plusMonths(1).atDay(1).atStartOfDay(TimeConfig.SERVICE_ZONE).toInstant();
        return deliveries.findMonthlyCoachingCards(childId, start, end, PageRequest.of(0, 8));
    }
}
