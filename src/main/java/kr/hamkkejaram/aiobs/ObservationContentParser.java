package kr.hamkkejaram.aiobs;

import kr.hamkkejaram.aiobs.ObservationContent.Moment;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 영수증의 {@code obs} 클레임(JSON 객체)을 엄격하게 읽는다(docs/ai-observation.md 6장).
 *
 * <ul>
 *   <li>모양이 약속과 다르면(자료형, 목록에 없는 태그, 개수·길이·범위 초과) 영수증 전체를 받지 않는다.</li>
 *   <li>문장 검사에 걸린 문장만은 영수증을 버리지 않고 그 문장만 버린다: {@code summary}는 null, 해당 moment는 제거.</li>
 * </ul>
 *
 * AI 서버가 이미 같은 검사를 하므로 여기서 걸리는 것은 약속이 어긋났다는 뜻이다. 그래서 고쳐서 받지 않는다.
 */
public final class ObservationContentParser {
    private ObservationContentParser() {}

    /** 사진 시각(초)의 상한. 하루를 넘는 값은 받지 않는다. */
    static final double MAX_MOMENT_SECONDS = 86_400;

    public static ObservationContent parse(Object claim) {
        if (!(claim instanceof Map<?, ?> obs)) throw new InvalidReceiptException("obs_not_object");

        String summary = summary(obs.get("summary"));
        List<ChildState> childStates = tags(obs.get("childStates"), ChildState.class, "childStates");
        List<AdultResponse> adultResponses = tags(obs.get("adultResponses"), AdultResponse.class, "adultResponses");
        List<Moment> moments = moments(obs.get("moments"));
        int peopleCount = intInRange(obs.get("peopleCount"), 0, ObservationContent.MAX_PEOPLE_COUNT, "peopleCount");

        return new ObservationContent(summary, childStates, adultResponses, moments, peopleCount);
    }

    private static String summary(Object value) {
        if (value == null) return null;
        if (!(value instanceof String raw)) throw new InvalidReceiptException("summary_not_string");
        String text = clean(raw);
        if (text.isEmpty()) return null;
        if (length(text) > ObservationContent.MAX_SUMMARY_CHARS) throw new InvalidReceiptException("summary_too_long");
        return ObservationSentenceFilter.isAllowed(text) ? text : null;
    }

    private static <E extends Enum<E>> List<E> tags(Object value, Class<E> type, String field) {
        if (value == null) return List.of();
        if (!(value instanceof List<?> list)) throw new InvalidReceiptException(field + "_not_array");
        if (list.size() > ObservationContent.MAX_TAGS) throw new InvalidReceiptException(field + "_too_many");
        Set<E> tags = new LinkedHashSet<>();
        for (Object item : list) {
            if (!(item instanceof String name)) throw new InvalidReceiptException(field + "_not_string");
            try {
                tags.add(Enum.valueOf(type, name));
            } catch (IllegalArgumentException e) {
                throw new InvalidReceiptException(field + "_unknown_value");
            }
        }
        return List.copyOf(tags);
    }

    private static List<Moment> moments(Object value) {
        if (value == null) return List.of();
        if (!(value instanceof List<?> list)) throw new InvalidReceiptException("moments_not_array");
        if (list.size() > ObservationContent.MAX_MOMENTS) throw new InvalidReceiptException("moments_too_many");
        List<Moment> moments = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> m)) throw new InvalidReceiptException("moment_not_object");
            if (!(m.get("t") instanceof Number n)) throw new InvalidReceiptException("moment_t_not_number");
            double t = n.doubleValue();
            if (!Double.isFinite(t) || t < 0 || t > MAX_MOMENT_SECONDS) {
                throw new InvalidReceiptException("moment_t_out_of_range");
            }
            if (!(m.get("note") instanceof String raw)) throw new InvalidReceiptException("moment_note_not_string");
            String note = clean(raw);
            if (length(note) > ObservationContent.MAX_NOTE_CHARS) throw new InvalidReceiptException("moment_note_too_long");
            // 빈 메모와 문장 검사에 걸린 메모는 그 항목만 버린다.
            if (!note.isEmpty() && ObservationSentenceFilter.isAllowed(note)) moments.add(new Moment(t, note));
        }
        return moments;
    }

    static int intInRange(Object value, int min, int max, String field) {
        if (!(value instanceof Number n)) throw new InvalidReceiptException(field + "_not_number");
        double d = n.doubleValue();
        if (!Double.isFinite(d) || d != Math.rint(d) || d < min || d > max) {
            throw new InvalidReceiptException(field + "_out_of_range");
        }
        return (int) d;
    }

    /** 제어 문자(줄바꿈·탭 포함)는 공백으로 바꾸고 앞뒤 공백을 지운다. 짝이 맞지 않는 대리 문자는 받지 않는다(DB 저장 불가). */
    private static String clean(String raw) {
        StringBuilder sb = new StringBuilder(raw.length());
        raw.codePoints().forEach(cp -> {
            if (cp >= Character.MIN_SURROGATE && cp <= Character.MAX_SURROGATE) {
                throw new InvalidReceiptException("text_malformed");
            }
            sb.appendCodePoint(Character.isISOControl(cp) ? ' ' : cp);
        });
        return sb.toString().strip();
    }

    /** 글자 수는 코드 포인트로 센다(이모지 한 글자를 두 글자로 세지 않는다). */
    private static int length(String text) {
        return text.codePointCount(0, text.length());
    }
}
