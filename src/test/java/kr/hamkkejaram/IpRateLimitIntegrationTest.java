package kr.hamkkejaram;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * IP당 분당 요청 한도가 실제 요청에서 429를 내는지, 그리고 용도별로 따로 세는지(ACC-05·ACC-06·TCH-01).
 * 한도를 작게 잡은 별도 설정으로 돌린다(다른 통합 테스트는 분당 1000회라 한도에 닿지 않는다).
 * 테스트마다 다른 IP를 써서 서로의 횟수가 섞이지 않게 한다.
 */
@TestPropertySource(properties = {
        "app.rate-limit.auth-requests-per-minute=3",
        "app.rate-limit.refresh-requests-per-minute=3",
        "app.rate-limit.password-requests-per-minute=2",
        "app.rate-limit.invite-requests-per-minute=2"})
class IpRateLimitIntegrationTest extends IntegrationTestSupport {
    private static final AtomicInteger IP_SEQ = new AtomicInteger();

    /** 테스트용 문서 대역(198.51.100.0/24)의 새 주소. */
    private static String newIp() {
        return "198.51.100." + IP_SEQ.incrementAndGet();
    }

    private static MockHttpServletRequestBuilder from(MockHttpServletRequestBuilder b, String ip) {
        return b.with(request -> {
            request.setRemoteAddr(ip);
            return request;
        });
    }

    /** 가입도 가입·로그인 한도를 쓰므로, 준비용 계정은 매번 새 IP에서 만든다. */
    private JsonNode signupFromNewIp(String role) throws Exception {
        return json(mvc.perform(from(body(post("/api/v1/auth/signup"),
                        signupRequest(uniqueEmail(role.toLowerCase()), PASSWORD, role)), newIp()))
                .andExpect(status().isCreated()).andReturn());
    }

    private ResultActions login(String ip) throws Exception {
        // 없는 이메일(매번 다름)이라 이메일 잠금과는 무관하게 401이 난다
        return mvc.perform(from(body(post("/api/v1/auth/login"),
                Map.of("email", uniqueEmail("nobody"), "password", "wrong999")), ip));
    }

    private ResultActions refresh(String ip) throws Exception {
        return mvc.perform(from(body(post("/api/v1/auth/refresh"), Map.of("refreshToken", "no-such-token")), ip));
    }

    private ResultActions changePassword(String ip, String token) throws Exception {
        return mvc.perform(from(body(auth(put("/api/v1/me/password"), token),
                Map.of("currentPassword", "wrong999", "newPassword", "newpass99")), ip));
    }

    private ResultActions deleteAccount(String ip, String token) throws Exception {
        return mvc.perform(from(body(auth(delete("/api/v1/me"), token), Map.of("password", "wrong999")), ip));
    }

    private ResultActions enterInviteCode(String ip, String teacher) throws Exception {
        return mvc.perform(from(body(auth(post("/api/v1/teacher/links"), teacher), Map.of("code", "ZZZZZZZZ")), ip));
    }

    private static void expectTooMany(ResultActions r) throws Exception {
        r.andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("TOO_MANY_ATTEMPTS"));
    }

    @Test
    void loginAndSignupShareOneLimitAndOtherPurposesStayOpen() throws Exception {
        String parent = token(signupFromNewIp("PARENT"));
        String teacher = token(signupFromNewIp("TEACHER"));
        String ip = newIp();

        for (int i = 0; i < 3; i++) login(ip).andExpect(status().isUnauthorized());
        expectTooMany(login(ip));
        // 가입도 같은 통이다
        expectTooMany(mvc.perform(from(body(post("/api/v1/auth/signup"),
                signupRequest(uniqueEmail("late"), PASSWORD, "PARENT")), ip)));

        // 같은 IP여도 다른 용도는 막히지 않는다(각자의 응답이 그대로 나온다)
        refresh(ip).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
        changePassword(ip, parent).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        enterInviteCode(ip, teacher).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVITE_CODE_INVALID"));

        // 다른 IP의 로그인도 막히지 않는다
        login(newIp()).andExpect(status().isUnauthorized());
    }

    @Test
    void tokenRefreshHasItsOwnLimit() throws Exception {
        String ip = newIp();
        for (int i = 0; i < 3; i++) refresh(ip).andExpect(status().isUnauthorized());
        expectTooMany(refresh(ip));

        // 갱신 한도를 다 써도 같은 IP의 로그인은 된다
        login(ip).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void passwordChangeAndAccountDeletionShareOneLimit() throws Exception {
        String parent = token(signupFromNewIp("PARENT"));
        String ip = newIp();

        changePassword(ip, parent).andExpect(status().isUnauthorized());
        deleteAccount(ip, parent).andExpect(status().isUnauthorized());
        expectTooMany(changePassword(ip, parent));
        expectTooMany(deleteAccount(ip, parent));

        // 같은 계정이어도 다른 IP에서는 된다(틀린 횟수는 아직 2번이라 계정 잠금 전이다)
        changePassword(newIp(), parent).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        // 비밀번호 확인 한도를 다 써도 같은 IP의 토큰 갱신은 막히지 않는다
        refresh(ip).andExpect(status().isUnauthorized());
    }

    @Test
    void inviteCodeEntryHasItsOwnLimit() throws Exception {
        String teacher = token(signupFromNewIp("TEACHER"));
        String ip = newIp();

        for (int i = 0; i < 2; i++) enterInviteCode(ip, teacher).andExpect(status().isUnprocessableEntity());
        expectTooMany(enterInviteCode(ip, teacher));

        // 코드 입력 한도를 다 써도 같은 IP의 로그인·토큰 갱신은 된다
        login(ip).andExpect(status().isUnauthorized());
        refresh(ip).andExpect(status().isUnauthorized());
    }
}
