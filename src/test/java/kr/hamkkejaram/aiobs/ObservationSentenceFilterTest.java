package kr.hamkkejaram.aiobs;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
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

    /*
     * 아래 세 테스트는 hamkke-ai 와 같은 문장을 쓴다(AIO-09: 두 번째 검사도 같은 폭).
     * 두 저장소가 함께 쓰는 전체 예문은 ObservationWordingCasesTest 가 wording-cases.json 으로 돌린다.
     */

    @Test
    void adviceInPoliteFormIsCaught() {
        for (String s : List.of(
                "아이가 울면 꼭 안아 주세요.",
                "아이에게 먼저 말을 걸어 보세요.",
                "어른이 아이를 달래 주면 좋겠어요.",
                "그럴 때는 그러지 마세요",
                "옆에서 기다려 주면 돼요.",
                "먼저 안아 주는 게 좋아요.",
                "어른의 도움이 필요해 보여요.",
                "천천히 기다려 주십시오.")) {
            assertThat(ObservationSentenceFilter.isAllowed(s)).as(s).isFalse();
        }
    }

    @Test
    void guessesAboutReasonsOrFeelingsAreCaught() {
        for (String s : List.of(
                "아이가 졸려서 우는 것 같아요.",
                "블록이 무너졌기 때문이에요.",
                "아이가 졸린가 봐요.",
                "아이가 자나 봐요.",
                "아이가 졸린 듯해요.",
                "아마 배가 고파서 울어요.")) {
            assertThat(ObservationSentenceFilter.isAllowed(s)).as(s).isFalse();
        }
    }

    @Test
    void agesAndLooksAreCaught() {
        for (String s : List.of(
                "5살쯤 된 아이가 앉아 있어요.",
                "세 살 아이가 앉아 있어요.",
                "열두 살 아이가 서 있어요.",
                "아이는 3세예요.",
                "18개월쯤 된 아이가 걸어요.",
                "통통한 아이가 웃어요.")) {
            assertThat(ObservationSentenceFilter.isAllowed(s)).as(s).isFalse();
        }
    }

    @Test
    void lookAlikeEverydayObservationsPass() {
        for (String s : List.of(
                "아이가 공을 통통 튀겨요.",
                "아이가 블록 3세트를 쌓아요.",
                "아이가 자세 살펴보듯 블록을 들여다봐요.",
                "아이가 어른을 봐요.",
                "어른이 아이가 쌓은 블록을 봐요.",
                "아이가 블록을 좋아해요.",
                "아이가 어른과 같이 놀아요.",
                "아이가 블록 2개를 세워요.",
                "어른이 아이에게 다가가 봐요.")) {
            assertThat(ObservationSentenceFilter.isAllowed(s)).as(s).isTrue();
        }
    }

    // ---------- 2026-11 에 넓힌 갈래. 문장 전체 목록은 공용 예문 파일로 돌린다(ObservationWordingCasesTest) ----------

    @Test
    void looksLikeGuessesAreCaughtButShowingIsKept() {
        // 예전에는 지나가던 문장이다. '-아/어 보여요'(속마음 짐작)를 거르기로 하면서 걸리는 쪽으로 옮겼다(hamkke-ai 와 같다).
        assertThat(ObservationSentenceFilter.isAllowed("아이가 기분이 좋아 보여요.")).isFalse();
        assertThat(ObservationSentenceFilter.isAllowed("아이가 졸려 보여요.")).isFalse();
        // 내보인다는 뜻의 '보여 줘요'와, 눈에 보인다는 뜻의 '보여요'는 남는다
        assertThat(ObservationSentenceFilter.isAllowed("아이가 어른에게 블록을 보여 줘요.")).isTrue();
        assertThat(ObservationSentenceFilter.isAllowed("탁자 위에 블록이 보여요.")).isTrue();
    }

    // ---------- 글자 맞추기: 보이지 않는 글자, 전각 글자, 풀어 쓴 자모 ----------

    /**
     * 낱말 사이에 끼워 넣을 수 있는 보이지 않는 글자들. 소스에 글자 자체를 적지 않으려고 코드 포인트로 둔다.
     * 폭 없는 공백·비연결자·연결자, 낱말 이음표, BOM, 부드러운 하이픈, 결합 문자 이음표, 한글 채움 문자 넷,
     * 크메르 모음 둘, 이형 선택자 처음과 끝, 오른쪽에서 왼쪽으로 덮어쓰기, 아랍 문자 표시.
     */
    private static final int[] INVISIBLE = {
            0x200B, 0x200C, 0x200D, 0x2060, 0xFEFF, 0x00AD, 0x034F, 0x115F, 0x1160, 0x3164, 0xFFA0,
            0x17B4, 0x17B5, 0xFE00, 0xFE0F, 0x202E, 0x061C};

    /** 글자마다 사이에 끼운다: 가나다 → 가(끼움)나(끼움)다. */
    private static String interleave(String word, int codePoint) {
        return String.join(Character.toString(codePoint), word.codePoints().mapToObj(Character::toString).toList());
    }

    @Test
    void forbiddenExpressionsAreCaughtWithInvisibleCharactersInside() {
        for (String word : ObservationSentenceFilter.forbiddenExamples()) {
            for (int invisible : INVISIBLE) {
                String hidden = interleave(word, invisible);
                assertThat(hidden).isNotEqualTo(word);
                assertThat(ObservationSentenceFilter.isAllowed("아이가 놀고 있어요. " + hidden + " 이에요."))
                        .as("%s 사이에 U+%04X", word, invisible).isFalse();
            }
        }
    }

    @Test
    void judgingWordsAreCaughtWithInvisibleCharactersInside() {
        for (String word : ObservationSentenceFilter.judgingWords()) {
            for (int invisible : INVISIBLE) {
                assertThat(ObservationSentenceFilter.isAllowed("아이가 놀고 있어요. " + interleave(word, invisible)))
                        .as("%s 사이에 U+%04X", word, invisible).isFalse();
            }
        }
    }

    @Test
    void forbiddenExpressionsAreCaughtInFullWidthOrDecomposedForm() {
        for (String word : ObservationSentenceFilter.forbiddenExamples()) {
            // 풀어 쓴 한글 자모(NFD)
            String decomposed = Normalizer.normalize(word, Normalizer.Form.NFD);
            assertThat(ObservationSentenceFilter.isAllowed("아이가 놀고 있어요. " + decomposed + " 이에요."))
                    .as("풀어 쓴 %s", word).isFalse();
            // 전각 영문·숫자(반각보다 0xFEE0 뒤에 있다)
            String fullWidth = word.codePoints()
                    .map(cp -> cp > 0x20 && cp < 0x7F ? cp + 0xFEE0 : cp)
                    .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString();
            assertThat(ObservationSentenceFilter.isAllowed("아이가 놀고 있어요. " + fullWidth + " 이에요."))
                    .as("전각 %s", word).isFalse();
        }
    }

    @Test
    void normaliseFoldsWidthAndRemovesInvisibleCharacters() {
        String fullWidthThree = Character.toString('3' + 0xFEE0);
        for (int invisible : INVISIBLE) {
            String raw = "아이가 블록 " + fullWidthThree + "개를 쌓" + Character.toString(invisible) + "아요.";
            String normalised = ObservationSentenceFilter.normalise(raw);
            assertThat(normalised).as("U+%04X", invisible).isEqualTo("아이가 블록 3개를 쌓아요.");
            // 이미 맞춘 글에 다시 해도 같다(AI 서버가 맞춘 글을 영수증에 넣는다)
            assertThat(ObservationSentenceFilter.normalise(normalised)).isEqualTo(normalised);
            // 걸릴 것이 없는 문장은 보이지 않는 글자가 끼어 있어도 지나간다(검사는 맞춘 글로 한다)
            assertThat(ObservationSentenceFilter.isAllowed(raw)).as("U+%04X", invisible).isTrue();
        }
    }

    @Test
    void textMadeOnlyOfInvisibleCharactersIsNotStored() {
        StringBuilder sb = new StringBuilder();
        for (int invisible : INVISIBLE) sb.appendCodePoint(invisible);
        assertThat(ObservationSentenceFilter.normalise(sb.toString())).isEmpty();
        assertThat(ObservationSentenceFilter.isAllowed(sb.toString())).isFalse();
        assertThat(ObservationSentenceFilter.isAllowed(" " + sb + " ")).isFalse();
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
