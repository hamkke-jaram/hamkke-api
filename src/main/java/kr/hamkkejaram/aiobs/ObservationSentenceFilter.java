package kr.hamkkejaram.aiobs;

import java.text.Normalizer;
import java.util.List;
import java.util.regex.Pattern;

/**
 * VL 모델이 쓴 문장(요약·시각별 메모)을 저장하기 전에 한 번 더 거르는 검사(docs/ai-observation.md 6장 "문장 검사").
 * AI 서버가 먼저 거르고, 여기서 같은 목록으로 다시 거른다. 걸린 문장은 고쳐 쓰지 않고 통째로 버린다.
 * 검사 전에 글자를 고르게 맞춘다({@link #normalise}): 전각 글자나 낱말 사이에 끼운 보이지 않는 글자로 비켜 가지 못한다.
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

    /*
     * 여기부터는 2026-11 에 넓힌 갈래다(docs/ai-observation.md 6장, hamkke-ai docs/observation.md 6.1절).
     * hamkke-ai server/src/wording.ts 의 INVISIBLE_CHARACTERS·LABELING_PATTERNS·JUDGING_PATTERNS·INNER_STATE_PATTERNS·
     * PERSON_PATTERNS·READING_PATTERNS 와 '-ㄴ/ㄹ 듯' 검사를 그대로 옮긴 것이다. 버리는 쪽으로만 넓혔고,
     * 맞는 관찰 문장이 일부 버려지는 것을 받아들였다. 두 저장소는 같은 예문 파일(wording-cases.json)로
     * 결과가 같은지 확인한다(ObservationWordingCasesTest). 한쪽을 고치면 다른 쪽과 예문 파일도 함께 고친다.
     */

    /**
     * 보이지 않는 글자: 형식 문자(Cf: 폭 없는 공백·이음표, BOM, 방향 표시 등) 전체와 결합 문자 이음표(034F),
     * 한글 채움 문자(115F·1160), 크메르 모음 두 개(17B4·17B5), 이형 선택자(FE00~FE0F).
     * 낱말 사이에 끼워 넣어 검사를 비켜 갈 수 있어서 공백으로 바꾸지 않고 지운다.
     * (코드 포인트는 정규식의 \x{…} 로 적는다. 소스 파일에 보이지 않는 글자 자체가 들어가지 않게 하려는 것이다.)
     */
    private static final Pattern INVISIBLE = Pattern.compile(
            "[\\p{Cf}\\x{034F}\\x{115F}\\x{1160}\\x{17B4}\\x{17B5}\\x{FE00}-\\x{FE0F}]");

    /** 발달·또래 견주기, 아이에게 이름을 붙이는 말. */
    private static final List<Pattern> LABELING = patterns(
            "발달|발육|성장|지능|인지|언어|사회성|주의력|집중력|충동",
            "자폐|스펙트럼|아스퍼거|증후군|강박|우울|퇴행|상동|반향어|과민|예민|미숙|미성숙|특수\\s*(교육|학급|아동)",
            "(?<![A-Za-z])(ADHD|ADD|ASD|IQ)(?![A-Za-z])",
            "또래|동갑|월령|연령|학년|평균(?!대)|수준|나이에\\s*비해",
            "다른\\s*(아이|애)(들)?\\s*(보다|에\\s*비해|과\\s*달리|와\\s*달리|처럼|만큼)",
            "[가-힣](보다|에\\s*비해)\\s*(더\\s*|훨씬\\s*|많이\\s*|조금\\s*)?(빠르|빨라|빠른|느리|느려|느린|늦|이르|일러|작|크|커|큰|어리|어려|어린|뒤처|뒤떨|앞서)",
            "(말|말하기|걸음|걸음마|행동|반응|이해|표현|대답)(이|은|도|가|을)?\\s*(많이\\s*|조금\\s*|아직\\s*)?(늦|느리|느려|느린|더디|더뎌|서툴|어눌)",
            "눈맞춤|눈[을도은]?\\s*(잘\\s*)?(맞추지|마주치지)\\s*(않|못)|눈[을도은]?\\s*(잘\\s*)?(안|못)\\s*(맞추|맞춰|마주치|마주쳐)",
            "성향|기질|성격|경향|특성|습관",
            "내성적|외향적|소심|소극적|적극적|의존적|독립적|수줍|낯가림|낯을\\s*(가리|가려)|고집",
            "\\s편(이에요|이예요|입니다|이다|이라)",
            "평소|원래|항상|언제나|매번");

    /** 평가하는 말(버릇, 솜씨, 어른의 돌봄을 탓하는 말 등). */
    private static final List<Pattern> JUDGING = patterns(
            "버릇|말썽|함부로|멋대로|막무가내",
            "떼\\s*(를\\s*)?(쓰|써|썼|부리|부려|부렸)|생떼|떼쟁이|투정|응석|징징",
            "(?<![가-힣])말을?\\s*(잘\\s*|안\\s*)*(듣|들어|들으|들었)",
            "집중(을|이|은|도)?\\s*(잘\\s*|오래\\s*)?(못|안\\s)|집중하지|집중이\\s*(짧|약)",
            "(^|\\s)잘\\s+(?!(안\\s*)?보[이여])[가-힣]|(^|\\s)잘(해|하|했|한)|잘\\s*못|(^|\\s)못\\s*(해|하|했|한)",
            "(휴대폰|핸드폰|스마트폰|전화기|전화|폰|텔레비전|티비|TV|화면|게임|태블릿|패드)(에)?만(?![가-힣])",
            "기만\\s*(해|하|했|한)|(?<![가-힣])멍(하|때)",
            "방치|무관심|무시|외면|모른\\s*척|본체만체|건성|소홀|귀찮",
            "혼내|혼나|혼났|혼을\\s*내|야단|꾸짖|꾸중|다그치|다그쳐|윽박|나무라|잔소리|훈육|체벌",
            "(?<![가-힣])(착|심|순)(하|한|해)|얌전|의젓|기특|대견|똑똑(하|한|해)|영리|야무|씩씩",
            "나쁜|나쁘|나빠|못된|못됐|지나치게|지나친|거칠|난폭|폭력|사납|드세|극성|유난|별나|까다롭|까다로|어수선",
            "제대로|엉망|서툴|어설프|어설퍼|능숙|솜씨");

    /** '-아/어 보여요' 앞에 오는 글자: 받침이 없고 모음이 ㅏ·ㅓ·ㅕ·ㅝ 인 글자와 '해'. 가·서·자·터·며·다·까 는 뺐다. */
    private static final String LOOKS_LIKE_PRECEDING = "나따라마바빠사싸아짜차카타파하" + "거꺼너더떠러머버뻐써어저쩌처커퍼허"
            + "겨껴녀뎌뗘려벼뼈셔쎠여져쪄쳐켜텨펴혀" + "궈꿔눠둬뚸뤄뭐붜뿨숴쒀워줘쭤춰쿼퉈풔훠" + "해";
    /** '보여 줘요'(내보인다는 뜻)는 남긴다. */
    private static final String NOT_SHOWING = "(?!\\s*(줘|주|준|줬|드))";

    /** 속마음·까닭을 짐작하는 말('졸려 보여요', '나가고 싶어 해요', '피곤한지', '심심해서'). */
    private static final List<Pattern> INNER_STATE = patterns(
            "[" + LOOKS_LIKE_PRECEDING + "]\\s*보(여|이|입|였|인)" + NOT_SHOWING,
            "처럼\\s*보(여|이|입|였|인)|(?<!에)게\\s*보(여|이|입|였|인)" + NOT_SHOWING + "|로\\s*보(여|이|입|였|인)" + NOT_SHOWING,
            "싶",
            "기분|마음|느낌|감정|심정|눈치|기색|낌새",
            "(는|은|던|한|린|운|픈|쁜|든)지(?![가-힣])",
            "(심심|졸려|졸리|피곤|지루|답답|불안|짜증|궁금|속상|서운|억울|당황|긴장|걱정|무서|두려|슬퍼|슬프|기뻐|기쁘|즐거|행복|외로|부끄러|창피|귀찮|힘들|아파(?!트)|아프|배고|배가\\s*고|목말|목이\\s*말|화나|화가\\s*나|화났|신나|신이\\s*나|신났|삐져|삐쳐|놀라|좋아|싫어|재미있|재미없|재밌|원해|편안|편해|불편|지쳐|지치)[가-힣]{0,2}서(?![가-힣])");

    /** '-ㄴ/는/ㄹ 듯'(화가 난 듯, 울 듯). '듯' 앞 글자의 받침이 ㄴ·ㄹ 일 때만 걸린다('살펴보듯'은 지나간다). */
    private static final Pattern GUESS_NOUN = Pattern.compile("([가-힣])\\s*듯", FLAGS);

    /** 사람을 가리키는 말(관계·성별·나이대). 사진만으로는 알 수 없는 것을 정해 버리는 말이다. */
    private static final List<Pattern> PERSON = patterns(
            "엄마|아빠|어머니|아버지|어머님|아버님|부모|보호자|할머니|할아버지|조부모|이모|고모|삼촌|누나|언니|오빠|동생|형제|자매|남매|쌍둥이|가족|식구|아들|손자|손녀|조카|친구|선생|교사|원장|돌보미|시터|아저씨|아줌마|아주머니|어르신|노인",
            "(^|\\s)(딸|형)(이|은|과|을|의|도|에게|한테)?(?![가-힣])",
            "남자|여자|남성|여성|소년|소녀|사내아이|남녀",
            "아기(?!\\s*인형)|애기|유아|영아|신생아|갓난|꼬마|어린이(?!집)|청소년|학생|원생|원아|(^|\\s)어린\\s|젊은|늙은",
            "(한두|두세|두어|서너|너덧|네댓|네다섯|대여섯|예닐곱|일고여덟|여남은|스무|서른|마흔|쉰|몇)\\s*살",
            "살\\s*(쯤|정도|가량|배기|짜리|남짓|전후)",
            "돌\\s*(쯤|정도|무렵|전후|쟁이|잡이|배기)|돌(이|도)?\\s*(막\\s*)?(지난|지나|안\\s*된|된)",
            "개월|나이(?!프|테)|연세|[1-9]0\\s*대\\s*(의|로|쯤|인|초반|중반|후반|정도|어른|아이)");

    /** 사진 속 글자를 읽어 옮긴 말(이름표, 따옴표로 옮긴 말). */
    private static final List<Pattern> READING = patterns(
            "(이라고|라고|이라는|라는|이란)\\s*(적|쓰|써|새겨|글|이름|낱말|단어|문구)",
            "적혀|적힌|쓰여|쓰인|써\\s*있|써져|써진|새겨",
            "이름|명찰",
            "['\"‘’“”「」『』]");

    private static List<Pattern> patterns(String... regexes) {
        return java.util.Arrays.stream(regexes).map(r -> Pattern.compile(r, FLAGS)).toList();
    }

    private static boolean anyMatch(List<Pattern> list, String text) {
        for (Pattern p : list) {
            if (p.matcher(text).find()) return true;
        }
        return false;
    }

    /** 식에 잡힌 한글 한 글자(1번 묶음)의 받침이 wanted 가운데 하나인지. 받침 번호: ㄴ = 4, ㄹ = 8. */
    private static boolean finalConsonantIn(Pattern pattern, String text, int... wanted) {
        var m = pattern.matcher(text);
        while (m.find()) {
            int finalConsonant = (m.group(1).charAt(0) - 0xAC00) % 28;
            for (int w : wanted) {
                if (finalConsonant == w) return true;
            }
        }
        return false;
    }

    /**
     * 검사하기 전에 글자를 고르게 맞춘다. hamkke-ai 의 normaliseForCheck 와 같은 순서다.
     * <ol>
     *   <li>NFKC: 전각 숫자·영문 → 반각, 풀어 쓴 한글 자모 → 완성형, 한글 채움 문자(3164·FFA0) → 1160</li>
     *   <li>보이지 않는 글자를 지운다(공백으로 바꾸지 않는다). 1에서 1160 으로 모인 채움 문자도 여기서 지워진다.</li>
     * </ol>
     * 이미 맞춘 글에 다시 해도 결과가 같다. 저장하는 글도 이 결과를 쓴다({@link ObservationContentParser}):
     * 검사한 글과 저장하는 글이 같아야 한다.
     */
    public static String normalise(String text) {
        return INVISIBLE.matcher(Normalizer.normalize(text, Normalizer.Form.NFKC)).replaceAll("");
    }

    /**
     * 저장해도 되는 문장인지. null·빈 문장(보이지 않는 글자뿐인 문장 포함)은 저장할 것이 없으므로 false.
     * 글자를 맞춘 뒤({@link #normalise}) 갈래마다 본다. 하나라도 걸리면 버린다(순서는 결과에 영향이 없다).
     */
    public static boolean isAllowed(String raw) {
        if (raw == null) return false;
        String text = normalise(raw);
        if (text.isBlank()) return false;
        for (Rule rule : FORBIDDEN) {
            if (rule.pattern().matcher(text).find()) return false;
        }
        for (String word : JUDGING_WORDS) {
            if (text.contains(word)) return false;
        }
        if (anyMatch(ADVICE, text) || anyMatch(GUESS, text) || finalConsonantIn(GA_BWAYO, text, 4)
                || anyMatch(PERSONAL, text)) {
            return false;
        }
        if (anyMatch(LABELING, text) || anyMatch(JUDGING, text) || anyMatch(INNER_STATE, text)) return false;
        if (finalConsonantIn(GUESS_NOUN, text, 4, 8)) return false;
        return !anyMatch(PERSON, text) && !anyMatch(READING, text);
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
