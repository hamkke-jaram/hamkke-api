package kr.hamkkejaram.aiobs;

import io.jsonwebtoken.Claims;
import kr.hamkkejaram.aiobs.AiReviewDtos.Recommendation;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Only the PC AI retrieves sources. Browsers can omit an offered action but cannot supply its citation or text. */
final class AiGuidanceVerifier {
    static final String AUDIENCE = "hamkke-api-guidance";
    record Guidance(String summary, List<Recommendation> recommendations, String corpusVersion) {}

    private AiGuidanceVerifier() {}

    static Guidance read(Claims claims, long reviewerId, long childId, long observationId) {
        if (!"ai-guidance".equals(claims.get("typ"))
                || AiReceiptVerifier.positiveLong(claims.get("sub"), "sub") != reviewerId
                || AiReceiptVerifier.positiveLong(claims.get("cid"), "cid") != childId
                || AiReceiptVerifier.positiveLong(claims.get("oid"), "oid") != observationId) {
            throw invalid();
        }
        String summary = text(claims, "summary", 500);
        String version = text(claims, "corpusVersion", 100);
        if (!(claims.get("recommendations") instanceof List<?> rows) || rows.size() > 2) throw invalid();
        List<Recommendation> result = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (Object item : rows) {
            if (!(item instanceof Map<?,?> row)) throw invalid();
            String id = text(row, "sourceId", 100);
            if (!ids.add(id)) throw invalid();
            String url = text(row, "sourceUrl", 1200);
            try {
                URI uri = URI.create(url);
                if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null)
                    throw invalid();
            } catch (IllegalArgumentException e) {
                throw invalid();
            }
            result.add(new Recommendation(text(row, "observation", 500), text(row, "action", 500),
                    text(row, "reason", 500), id, text(row, "sourceTitle", 300),
                    text(row, "sourcePublisher", 300), url, text(row, "sourceSection", 300)));
        }
        return new Guidance(summary, List.copyOf(result), version);
    }

    private static String text(Map<?,?> values, String key, int max) {
        if (!(values.get(key) instanceof String s) || s.isBlank() || s.length() > max
                || s.codePoints().anyMatch(cp -> Character.getType(cp) == Character.SURROGATE
                    || Character.isISOControl(cp) && cp != '\n' && cp != '\r' && cp != '\t')) throw invalid();
        return s.strip();
    }

    private static InvalidReceiptException invalid() {
        return new InvalidReceiptException("guidance_malformed");
    }
}
