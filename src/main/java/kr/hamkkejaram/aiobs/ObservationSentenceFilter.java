package kr.hamkkejaram.aiobs;

import java.util.List;
import java.util.regex.Pattern;

/**
 * VL 모델이 쓴 문장(요약·시각별 메모)을 저장하기 전에 한 번 더 거르는 검사(docs/ai-observation.md 6장 "문장 검사").
 * AI 서버가 먼저 거르고, 여기서 같은 목록으로 다시 거른다. 걸린 문장은 고쳐 쓰지 않고 통째로 버린다.
 *
 * <p><b>이 파일만</b> 금지 표현 목록을 그대로 담고 있다. 문장을 거르려면 목록이 코드에 있어야 하기 때문이다.
 * 그래서 {@code scripts/check-expressions.mjs}는 이 파일 하나만 검사에서 뺀다. 목록은 그 스크립트의
 * {@code FORBIDDEN}과 같게 유지한다(고유명사 허용 목록은 여기서 쓰지 않는다. 관찰 문장에 나올 이유가 없다).
 * 다른 파일에서 이 목록의 낱말을 직접 적지 말고, 테스트는 {@link #forbiddenExamples()}를 쓴다.
 */
public final class ObservationSentenceFilter {
    private ObservationSentenceFilter() {}

    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS;

    /** 금지 표현 하나: 찾는 식과, 그 식에 걸리는 보기 낱말(테스트용). */
    private record Rule(Pattern pattern, String example) {
        static Rule of(String regex, String example) {
            return new Rule(Pattern.compile(regex, FLAGS), example);
        }
    }

    /** scripts/check-expressions.mjs 의 FORBIDDEN 과 같은 목록. */
    private static final List<Rule> FORBIDDEN = List.of(
            Rule.of("진단", "진단"),
            Rule.of("판별", "판별"),
            Rule.of("선별", "선별"),
            Rule.of("위험도", "위험도"),
            Rule.of("위험\\s*예측", "위험 예측"),
            Rule.of("치료", "치료"),
            Rule.of("증상", "증상"),
            Rule.of("예방", "예방"),
            Rule.of("경계선\\s*지능", "경계선 지능"),
            Rule.of("정확도", "정확도"),
            Rule.of("WHO\\s*프로그램", "WHO 프로그램"),
            // 외부 프로그램 상표명. 영문·숫자에 붙어 있지 않을 때만(낱말 단위).
            Rule.of("(?<![A-Za-z0-9_])(Hanen|ESDM|PACT|JASPER)(?![A-Za-z0-9_])", "ESDM"),
            Rule.of("diagnos", "diagnosis"),
            Rule.of("screening", "screening"),
            Rule.of("risk", "risk"),
            Rule.of("treatment", "treatment"),
            Rule.of("symptom", "symptom"),
            Rule.of("therap", "therapy"),
            Rule.of("accuracy", "accuracy"),
            Rule.of("predict", "predict"),
            Rule.of("prevent", "prevent"),
            Rule.of("borderline", "borderline"));

    /** 평가·분류·원인 추정·조언처럼 읽히는 말. 들어 있으면 그 문장을 버린다. */
    private static final List<String> JUDGING_WORDS = List.of(
            "문제", "장애", "이상", "정상", "비정상", "지연", "부족", "산만", "공격", "과잉", "결핍", "의심",
            "해야", "하세요", "바랍니다", "권장");

    /*
     * 아래 세 목록은 hamkke-ai 서버(server/src/wording.ts)의 ADVICE_PATTERNS·GUESS_PATTERNS·PERSONAL_PATTERNS 와
     * 같은 식이다. AI 서버가 먼저 거르지만, 설정이 잘못되었거나 다른 서버가 서명한 영수증이 와도 저장하지 않도록
     * 두 번째 검사도 같은 폭으로 거른다(AIO-09). 한쪽을 고치면 다른 쪽도 고친다.
     */

    /** 조언·부탁 말투('안아 주세요', '달래 주면 좋겠어요'). '하세요'만으로는 해요체 조언이 지나가서 어미까지 본다. */
    private static final List<Pattern> ADVICE = patterns(
            "세요(?![가-힣])",
            "십시오|시길|시기\\s*바라",
            "좋겠|좋을\\s*(것|거|듯)|(는|은)\\s*(게|것이|편이)\\s*좋|면\\s*좋아요",
            "면\\s*(돼요|되어요|됩니다)",
            "필요|추천|권해|권합|권유|바라요|바래요");

    /** 까닭이나 속마음을 짐작하는 말투('~ 때문이에요', '졸린 것 같아요', '자나 봐요'). */
    private static final List<Pattern> GUESS = patterns(
            "때문|덕분",
            "(것|거|듯)\\s*같",
            "듯\\s*(해|합|하|싶)",
            "(^|\\s)아마(도)?(?=\\s)",
            "어쩌면|추측|짐작|추정",
            "[가-힣]나\\s?봐요");

    /** '-ㄴ가 봐요'(졸린가 봐요). '아이가 봐요'처럼 받침 없는 말 뒤의 '가'는 주격 조사라 건너뛴다. */
    private static final Pattern GA_BWAYO = Pattern.compile("([가-힣])가\\s*봐요", FLAGS);

    /** 나이 표기와 생김새를 평가하는 말. 흔한 관찰 문장('살펴봐요', '블록 3세트')은 걸리지 않게 좁게 잡는다. */
    private static final List<Pattern> PERSONAL = patterns(
            "\\d+\\s*살(?![펴피짝며살금])",
            "\\d+\\s*개월",
            "\\d+\\s*세(?![트대기션팅워우웠])",
            "(^|[^가-힣])(열\\s?)?(한|두|세|네|다섯|여섯|일곱|여덟|아홉|열)\\s*살(?![펴피짝며살금])",
            "예쁘|예쁜|예뻐|귀엽|귀여|잘생|못생|뚱뚱|통통(하|한|해|했)|날씬|말랐");

    private static List<Pattern> patterns(String... regexes) {
        return java.util.Arrays.stream(regexes).map(r -> Pattern.compile(r, FLAGS)).toList();
    }

    private static boolean anyMatch(List<Pattern> list, String text) {
        for (Pattern p : list) {
            if (p.matcher(text).find()) return true;
        }
        return false;
    }

    private static boolean hasGuessEnding(String text) {
        var m = GA_BWAYO.matcher(text);
        while (m.find()) {
            int finalConsonant = (m.group(1).charAt(0) - 0xAC00) % 28;
            if (finalConsonant == 4) return true; // 받침 ㄴ
        }
        return false;
    }

    /** 저장해도 되는 문장인지. null·빈 문장은 저장할 것이 없으므로 false. */
    public static boolean isAllowed(String text) {
        if (text == null || text.isBlank()) return false;
        for (Rule rule : FORBIDDEN) {
            if (rule.pattern().matcher(text).find()) return false;
        }
        for (String word : JUDGING_WORDS) {
            if (text.contains(word)) return false;
        }
        return !anyMatch(ADVICE, text) && !anyMatch(GUESS, text) && !hasGuessEnding(text) && !anyMatch(PERSONAL, text);
    }

    /** 금지 표현마다 하나씩, 그 식에 걸리는 보기 낱말. 테스트가 이 낱말들을 직접 적지 않도록 여기서 내준다. */
    public static List<String> forbiddenExamples() {
        return FORBIDDEN.stream().map(Rule::example).toList();
    }

    /** 평가·조언처럼 읽히는 말 목록(테스트용). */
    public static List<String> judgingWords() {
        return JUDGING_WORDS;
    }
}
