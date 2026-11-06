package kr.hamkkejaram.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Locale;

/**
 * 원본 영상·음성이 서버로 들어오지 않도록 하는 관문.
 * multipart·미디어·바이너리 본문은 415로, 64KB를 넘는 본문은 413으로 거부한다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestBodyGuardFilter extends OncePerRequestFilter {
    public static final int MAX_BODY_BYTES = 64 * 1024;

    private final ObjectMapper objectMapper;

    public RequestBodyGuardFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String contentType = request.getContentType();
        if (contentType != null && isForbiddenType(contentType)) {
            reject(response, ErrorCode.UNSUPPORTED_MEDIA_TYPE);
            return;
        }
        long length = request.getContentLengthLong();
        if (length > MAX_BODY_BYTES) {
            reject(response, ErrorCode.PAYLOAD_TOO_LARGE);
            return;
        }
        chain.doFilter(length < 0 ? new LimitedRequest(request) : request, response);
    }

    static boolean isForbiddenType(String contentType) {
        String ct = contentType.toLowerCase(Locale.ROOT);
        return ct.startsWith("multipart/") || ct.startsWith("video/") || ct.startsWith("audio/")
                || ct.startsWith("image/") || ct.startsWith(MediaType.APPLICATION_OCTET_STREAM_VALUE);
    }

    private void reject(HttpServletResponse response, ErrorCode code) throws IOException {
        response.setStatus(code.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getOutputStream(), ErrorResponse.of(code));
    }

    /** Content-Length 없이(chunked) 들어오는 본문도 한도를 넘으면 읽기를 멈춘다. */
    private static final class LimitedRequest extends HttpServletRequestWrapper {
        LimitedRequest(HttpServletRequest request) {
            super(request);
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            ServletInputStream in = super.getInputStream();
            return new ServletInputStream() {
                private long read;

                @Override
                public int read() throws IOException {
                    int b = in.read();
                    if (b >= 0 && ++read > MAX_BODY_BYTES) throw new PayloadTooLargeException();
                    return b;
                }

                @Override
                public int read(byte[] buf, int off, int len) throws IOException {
                    int n = in.read(buf, off, len);
                    if (n > 0 && (read += n) > MAX_BODY_BYTES) throw new PayloadTooLargeException();
                    return n;
                }

                @Override public boolean isFinished() { return in.isFinished(); }

                @Override public boolean isReady() { return in.isReady(); }

                @Override public void setReadListener(ReadListener listener) { in.setReadListener(listener); }
            };
        }
    }
}
