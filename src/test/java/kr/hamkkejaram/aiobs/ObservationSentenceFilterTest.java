package kr.hamkkejaram.aiobs;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 문장 검사(docs/ai-observation.md 6장). 금지 표현 낱말은 이 파일에 직접 적지 않고
 * 필터가 내주는 보기 낱말과 검수 스크립트의 목록을 읽어서 쓴다.
 */
class ObservationSentenceFilterTest {

    @Test
    void plainObservationsPass() {
        assertThat(ObservationSentenceFilter.isAllowed("어른과 아이가 블록을 사이에 두고 마주 앉아 있어요.")).isTrue();
        assertThat(ObservationSentenceFilter.isAllowed("아이가 블록을 밀어내자 어른이 잠시 기다린 뒤 말을 걸어요.")).isTrue();
        assertThat(ObservationSentenceFilter.isAllowed("아이가 울면서 어른에게 다가가요.")).isTrue();
        assertThat(ObservationSentenceFilter.isAllowed("아이가 공을 바닥에 던져요.")).isTrue();
    }

    @Test
    void emptyTextIsNotStored() {
        assertThat(ObservationSentenceFilter.isAllowed(null)).isFalse();
        assertThat(ObservationSentenceFilter.isAllowed("")).isFalse();
        assertThat(ObservationSentenceFilter.isAllowed("   ")).isFalse();
    }

    @Test
    void everyForbiddenExpressionIsCaught() {
        assertThat(ObservationSentenceFilter.forbiddenExamples()).isNotEmpty();
        for (String word : ObservationSentenceFilter.forbiddenExamples()) {
            assertThat(ObservationSentenceFilter.isAllowed("아이가 놀고 있어요. " + word + " 같아요.")).as(word).isFalse();
            // 영문은 대소문자를 가리지 않는다
            assertThat(ObservationSentenceFilter.isAllowed("아이가 놀아요 " + word.toUpperCase() + " 이에요")).as(word)
                    .isFalse();
        }
    }

    @Test
    void judgingAndAdvisingWordsAreCaught() {
        // 계약에 적힌 16개 낱말: 평가·분류·원인 추정·조언처럼 읽히는 말
        assertThat(ObservationSentenceFilter.judgingWords()).containsExactlyInAnyOrder(
                "문제", "장애", "이상", "정상", "비정상", "지연", "부족", "산만", "공격", "과잉", "결핍", "의심",
                "해야", "하세요", "바랍니다", "권장");
        for (String word : ObservationSentenceFilter.judgingWords()) {
            assertThat(ObservationSentenceFilter.isAllowed("아이가 놀고 있어요. " + word)).as(word).isFalse();
        }
        assertThat(ObservationSentenceFilter.isAllowed("아이를 더 자주 안아 주세요. 그렇게 하세요.")).isFalse();
        assertThat(ObservationSentenceFilter.isAllowed("아이가 산만해 보여요.")).isFalse();
    }

    /** 필터의 금지 표현 목록은 scripts/check-expressions.mjs 의 FORBIDDEN 과 같아야 한다. */
    @Test
    void forbiddenListMatchesTheCheckerScript() throws IOException {
        List<Pattern> checker = checkerPatterns();
        List<String> examples = ObservationSentenceFilter.forbiddenExamples();
        assertThat(checker).hasSameSizeAs(examples);
        // 검수 스크립트의 식마다 필터의 보기 낱말 하나가 걸리고, 그 반대도 같다.
        for (Pattern p : checker) {
            assertThat(examples).as("checker pattern %s", p).anyMatch(e -> p.matcher(e).find());
        }
        for (String e : examples) {
            assertThat(checker).as("filter example").anyMatch(p -> p.matcher(e).find());
        }
    }

    private static List<Pattern> checkerPatterns() throws IOException {
        String script = Files.readString(Path.of("scripts", "check-expressions.mjs"), StandardCharsets.UTF_8);
        int start = script.indexOf("const FORBIDDEN = [");
        int end = script.indexOf("];", start);
        assertThat(start).isGreaterThanOrEqualTo(0);
        String body = script.substring(start + "const FORBIDDEN = [".length(), end);
        Matcher m = Pattern.compile("/((?:[^/\\\\\\n]|\\\\.)+)/(i?)").matcher(body);
        List<Pattern> patterns = new ArrayList<>();
        while (m.find()) {
            int flags = Pattern.UNICODE_CHARACTER_CLASS | (m.group(2).isEmpty() ? 0 : Pattern.CASE_INSENSITIVE);
            patterns.add(Pattern.compile(m.group(1), flags));
        }
        return patterns;
    }
}
