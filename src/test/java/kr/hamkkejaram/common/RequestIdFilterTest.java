package kr.hamkkejaram.common;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 요청 ID: 응답 헤더와 로그 문맥(MDC)에 같은 값이 실리고, 요청이 끝나면 문맥에서 사라진다. */
class RequestIdFilterTest {
    private static final String ID_SHAPE = "^[0-9a-f]{16}$";

    private final RequestIdFilter filter = new RequestIdFilter();

    private static MockHttpServletRequest request() {
        return new MockHttpServletRequest("GET", "/api/v1/policies");
    }

    @Test
    void sameIdIsOnResponseHeaderAndInLogContextDuringTheRequest() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seenInChain = new AtomicReference<>();

        filter.doFilter(request(), response, (req, res) -> seenInChain.set(MDC.get(RequestIdFilter.MDC_KEY)));

        String header = response.getHeader(RequestIdFilter.HEADER);
        assertThat(header).matches(ID_SHAPE);
        assertThat(seenInChain.get()).isEqualTo(header);
        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).as("요청이 끝나면 문맥에서 지운다").isNull();
    }

    @Test
    void logLinesWrittenDuringTheRequestCarryTheId() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger("request-id-filter-test");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request(), response, (req, res) -> logger.info("inside request"));
            logger.info("after request");

            String header = response.getHeader(RequestIdFilter.HEADER);
            assertThat(appender.list).hasSize(2);
            assertThat(appender.list.get(0).getMDCPropertyMap()).containsEntry(RequestIdFilter.MDC_KEY, header);
            assertThat(appender.list.get(1).getMDCPropertyMap()).doesNotContainKey(RequestIdFilter.MDC_KEY);
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void everyRequestGetsADifferentId() throws Exception {
        MockHttpServletResponse first = new MockHttpServletResponse();
        MockHttpServletResponse second = new MockHttpServletResponse();
        FilterChain noop = (req, res) -> {};
        filter.doFilter(request(), first, noop);
        filter.doFilter(request(), second, noop);
        assertThat(first.getHeader(RequestIdFilter.HEADER))
                .isNotEqualTo(second.getHeader(RequestIdFilter.HEADER));
    }

    @Test
    void idSentByTheClientIsNotUsed() throws Exception {
        MockHttpServletRequest request = request();
        request.addHeader(RequestIdFilter.HEADER, "parent@example.com child=7");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seenInChain = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> seenInChain.set(MDC.get(RequestIdFilter.MDC_KEY)));

        assertThat(response.getHeader(RequestIdFilter.HEADER)).matches(ID_SHAPE);
        assertThat(seenInChain.get()).matches(ID_SHAPE);
    }

    @Test
    void logContextIsClearedEvenWhenTheRequestFails() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThatThrownBy(() -> filter.doFilter(request(), response, (req, res) -> {
            throw new ServletException("boom");
        })).isInstanceOf(ServletException.class);

        assertThat(response.getHeader(RequestIdFilter.HEADER)).matches(ID_SHAPE);
        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
    }
}
