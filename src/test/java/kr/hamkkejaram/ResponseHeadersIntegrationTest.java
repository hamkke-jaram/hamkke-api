package kr.hamkkejaram;

import jakarta.servlet.Filter;
import kr.hamkkejaram.common.RequestIdFilter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 응답 헤더: 요청 ID({@code X-Request-Id})가 성공·실패 응답 모두에 실리고 같은 값이 로그 줄에 찍히는지,
 * 허용한 다른 출처의 화면이 그 헤더를 읽을 수 있는지, 그리고 API 응답이 캐시되지 않게 나가는지.
 */
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties = "app.cors-allowed-origins=" + ResponseHeadersIntegrationTest.DEV_ORIGIN)
class ResponseHeadersIntegrationTest extends IntegrationTestSupport {
    private static final String ID_SHAPE = "^[0-9a-f]{16}$";
    private static final String PROBE_HEADER = "X-Test-Log-Probe";
    /** 개발 서버처럼 다른 출처에서 부르는 화면(운영은 같은 출처라 CORS가 꺼져 있다). */
    static final String DEV_ORIGIN = "http://localhost:5173";

    /** 요청을 처리하는 도중에 로그 한 줄을 남기는 시험용 필터(헤더를 보낸 요청에만). */
    @TestConfiguration
    static class LogProbeConfig {
        private static final Logger log = LoggerFactory.getLogger("request-id-probe");

        @Bean
        Filter logProbeFilter() {
            return (request, response, chain) -> {
                if (((jakarta.servlet.http.HttpServletRequest) request).getHeader(PROBE_HEADER) != null) {
                    log.info("probe line");
                }
                chain.doFilter(request, response);
            };
        }
    }

    private static String requestId(MvcResult r) {
        return r.getResponse().getHeader(RequestIdFilter.HEADER);
    }

    @Test
    void everyResponseCarriesItsOwnRequestId() throws Exception {
        String first = requestId(mvc.perform(get("/api/v1/policies")).andExpect(status().isOk()).andReturn());
        String second = requestId(mvc.perform(get("/api/v1/policies")).andExpect(status().isOk()).andReturn());
        assertThat(first).matches(ID_SHAPE);
        assertThat(second).matches(ID_SHAPE).isNotEqualTo(first);
    }

    @Test
    void errorResponsesCarryRequestIdToo() throws Exception {
        // 인증 실패(보안 필터가 직접 쓰는 응답)
        assertThat(requestId(mvc.perform(get("/api/v1/me")).andExpect(status().isUnauthorized()).andReturn()))
                .matches(ID_SHAPE);
        // 본문 관문이 돌려보내는 응답(파일 업로드 시도)
        assertThat(requestId(mvc.perform(multipart("/api/v1/auth/signup").file("file", new byte[]{1, 2, 3}))
                .andExpect(status().isUnsupportedMediaType()).andReturn())).matches(ID_SHAPE);
        // 검증 오류(컨트롤러까지 간 응답)
        assertThat(requestId(mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andExpect(status().isBadRequest()).andReturn())).matches(ID_SHAPE);
        // 없는 경로(전부 거부 규칙)
        assertThat(requestId(mvc.perform(get("/nowhere")).andReturn())).matches(ID_SHAPE);
    }

    @Test
    void requestIdSentByTheClientIsIgnored() throws Exception {
        String sent = "parent@example.com";
        String got = requestId(mvc.perform(get("/api/v1/policies").header(RequestIdFilter.HEADER, sent))
                .andExpect(status().isOk()).andReturn());
        assertThat(got).matches(ID_SHAPE).isNotEqualTo(sent);
    }

    @Test
    void logLinesWrittenDuringARequestShowItsRequestId(CapturedOutput output) throws Exception {
        String id = requestId(mvc.perform(get("/api/v1/policies").header(PROBE_HEADER, "1"))
                .andExpect(status().isOk()).andReturn());
        assertThat(id).matches(ID_SHAPE);
        assertThat(output.getOut().lines().filter(line -> line.contains("probe line")).toList())
                .as("요청 중에 남긴 로그 줄에는 그 요청의 ID가 [..] 안에 찍힌다")
                .hasSize(1)
                .allSatisfy(line -> assertThat(line).contains("[" + id + "] "));
    }

    @Test
    void allowedCrossOriginPagesMayReadTheRequestIdHeader() throws Exception {
        MvcResult r = mvc.perform(get("/api/v1/policies").header(HttpHeaders.ORIGIN, DEV_ORIGIN))
                .andExpect(status().isOk()).andReturn();
        assertThat(r.getResponse().getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isEqualTo(DEV_ORIGIN);
        assertThat(r.getResponse().getHeader(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS))
                .contains(RequestIdFilter.HEADER);
        assertThat(requestId(r)).matches(ID_SHAPE);
    }

    @Test
    void apiResponsesAreNotCacheable() throws Exception {
        String parent = token(signup("PARENT"));
        MvcResult me = mvc.perform(auth(get("/api/v1/me"), parent)).andExpect(status().isOk()).andReturn();
        assertThat(me.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store");

        MvcResult children = mvc.perform(auth(get("/api/v1/children"), parent)).andExpect(status().isOk())
                .andReturn();
        assertThat(children.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store");

        // 에러 응답도 같다
        MvcResult denied = mvc.perform(get("/api/v1/me")).andExpect(status().isUnauthorized()).andReturn();
        assertThat(denied.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store");
    }
}
