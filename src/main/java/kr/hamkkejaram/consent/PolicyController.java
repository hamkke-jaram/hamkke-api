package kr.hamkkejaram.consent;

import kr.hamkkejaram.common.AppProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/policies")
public class PolicyController {
    public static final String DISCLAIMER =
            "본 서비스는 의료기기가 아니며 발달 상태를 판단하지 않습니다. 걱정되는 점은 전문의와 상담하세요.";

    private final AppProperties.Policy policy;
    private final boolean aiObservationEnabled;

    public PolicyController(AppProperties props) {
        this.policy = props.policy();
        this.aiObservationEnabled = props.ai().enabled();
    }

    /** aiObservationEnabled: AI 서버가 설정돼 'AI 관찰 기록'을 쓸 수 있는지. 꺼져 있으면 화면은 그 기능만 숨긴다. */
    public record PolicyResponse(String termsVersion, String privacyVersion, String guardianVersion,
                                 String aiAnalysisVersion, boolean aiObservationEnabled, String disclaimer) {}

    @GetMapping
    public PolicyResponse get() {
        return new PolicyResponse(policy.termsVersion(), policy.privacyVersion(), policy.guardianVersion(),
                policy.aiAnalysisVersion(), aiObservationEnabled, DISCLAIMER);
    }
}
