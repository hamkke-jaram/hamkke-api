package kr.hamkkejaram.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * 요청마다 요청 ID를 만들어 로그 문맥(MDC)에 싣고 응답 헤더 {@code X-Request-Id}로 돌려준다(SPEC 7장 '로그').
 * 사용자가 알려 준 ID 하나로 그 요청의 로그를 찾을 수 있게 하려는 것이다.
 *
 * <ul>
 *   <li>ID는 난수 16진수 16자다. 사용자·아이·시각을 알아볼 수 있는 값은 넣지 않는다.</li>
 *   <li>클라이언트가 보낸 {@code X-Request-Id}는 쓰지 않는다(로그에 임의의 글자가 들어오지 않게).</li>
 *   <li>본문 관문({@link RequestBodyGuardFilter})과 인증보다 먼저 돌아서 415·413·401 응답에도 실린다.</li>
 * </ul>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class RequestIdFilter extends OncePerRequestFilter {
    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "requestId";

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int ID_BYTES = 8;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String id = newId();
        response.setHeader(HEADER, id);
        MDC.put(MDC_KEY, id);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    static String newId() {
        byte[] bytes = new byte[ID_BYTES];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
