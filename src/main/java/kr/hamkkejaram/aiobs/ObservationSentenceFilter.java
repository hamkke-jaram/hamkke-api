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

    /** 저장해도 되는 문장인지. null·빈 문장은 저장할 것이 없으므로 false. */
    public static boolean isAllowed(String text) {
        if (text == null || text.isBlank()) return false;
        for (Rule rule : FORBIDDEN) {
            if (rule.pattern().matcher(text).find()) return false;
        }
        for (String word : JUDGING_WORDS) {
            if (text.contains(word)) return false;
        }
        return true;
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
