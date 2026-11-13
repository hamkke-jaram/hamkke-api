package kr.hamkkejaram.aiobs;

import kr.hamkkejaram.common.ApiException;
import kr.hamkkejaram.common.AppProperties;
import kr.hamkkejaram.common.ErrorCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * AI 관찰 기록 설정(app.ai.*). 기능이 켜져 있는데 공유 비밀이 없거나 짧으면 기동하지 않는다.
 * 꺼져 있으면 비밀 없이도 기동하고, 관련 API는 503 {@code AI_UNAVAILABLE}로 응답한다(지표 3개 흐름은 그대로).
 */
@Component
public class AiSettings {
    static final int MIN_SECRET_BYTES = 32;
    /** local 프로필의 개발용 기본값(application.yml, hamkke-ai 서버의 개발용 기본값과 같다). 운영에서는 쓸 수 없다. */
    static final String LOCAL_DEV_SECRET = "local-dev-only-ai-shared-secret-change-me-0123456789";

    private final boolean enabled;
    private final SecretKey key;
    private final int maxFrames;
    private final Duration sessionTtl;

    @Autowired
    public AiSettings(AppProperties props, Environment env) {
        this(props, env.acceptsProfiles(Profiles.of("prod")));
    }

    /** @param production 운영(prod 프로필)인지. 운영에서는 개발용 기본 비밀로 기동하지 않는다. */
    AiSettings(AppProperties props, boolean production) {
        AppProperties.Ai ai = props.ai();
        this.enabled = ai.enabled();
        this.maxFrames = ai.maxFrames();
        this.sessionTtl = ai.sessionTtl();
        if (!enabled) {
            this.key = null;
            return;
        }
        // 앞뒤 공백은 지운다. hamkke-ai 서버(server/src/config.ts)도 같은 환경변수를 다듬어 쓰므로,
        // 한쪽만 다듬으면 서명이 어긋난다.
        String secret = ai.sharedSecret() == null ? "" : ai.sharedSecret().strip();
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("app.ai.enabled=true requires app.ai.shared-secret (AI_SHARED_SECRET) "
                    + "of at least " + MIN_SECRET_BYTES + " bytes. Set it, or turn the feature off with AI_ENABLED=false.");
        }
        if (secret.equals(props.jwt().secret())) {
            throw new IllegalStateException("app.ai.shared-secret (AI_SHARED_SECRET) must differ from the login "
                    + "token secret (JWT_SECRET).");
        }
        if (production && secret.equals(LOCAL_DEV_SECRET)) {
            throw new IllegalStateException("app.ai.shared-secret (AI_SHARED_SECRET) must not be the local "
                    + "development default in production.");
        }
        if (sessionTtl.isNegative() || sessionTtl.isZero()) {
            throw new IllegalStateException("app.ai.session-ttl must be positive.");
        }
        // 키 길이에 따라 알고리즘이 바뀌지 않도록 HS256용 키로 못 박는다(계약: 토큰·영수증 모두 HS256).
        this.key = new SecretKeySpec(bytes, "HmacSHA256");
    }

    public boolean enabled() { return enabled; }

    public int maxFrames() { return maxFrames; }

    public Duration sessionTtl() { return sessionTtl; }

    /** 꺼져 있으면 503. */
    public void requireEnabled() {
        if (!enabled) throw new ApiException(ErrorCode.AI_UNAVAILABLE);
    }

    /** 분석 토큰 서명·영수증 검증에 쓰는 키. 꺼져 있으면 503. */
    SecretKey key() {
        requireEnabled();
        return key;
    }
}
