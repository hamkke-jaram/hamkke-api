package kr.hamkkejaram.common;

import jakarta.validation.ConstraintViolation;
import org.springframework.validation.FieldError;

import java.util.Collection;
import java.util.Map;

/**
 * 검증 오류(fieldErrors)의 문구를 만든다.
 * Bean Validation 기본 문구는 요청 언어(Accept-Language)에 따라 영어가 되고 어느 칸인지도 알려 주지 않아서,
 * 기본 문구일 때는 칸 이름을 넣은 한국어 해요체 문장으로 바꾼다. DTO에 직접 적은 문구(message=)는 그대로 쓴다.
 * 문구는 표현 규칙(SPEC 2.1)을 따르고, 입력값 자체는 문구에 넣지 않는다.
 */
public final class FieldErrorMessages {
    private FieldErrorMessages() {}

    private record Label(String text, boolean choice) {}

    private static final Label FALLBACK = new Label("입력값", false);

    /** 필드 이름(경로의 마지막 부분) → 화면에 보이는 칸 이름. choice=true면 "선택해 주세요"로 안내한다. */
    private static final Map<String, Label> LABELS = Map.ofEntries(
            Map.entry("email", new Label("이메일", false)),
            Map.entry("password", new Label("비밀번호", false)),
            Map.entry("currentPassword", new Label("지금 비밀번호", false)),
            Map.entry("newPassword", new Label("새 비밀번호", false)),
            Map.entry("displayName", new Label("이름", false)),
            Map.entry("role", new Label("역할", true)),
            Map.entry("consents", new Label("동의 항목", true)),
            Map.entry("type", new Label("동의 종류", true)),
            Map.entry("version", new Label("동의 문서 버전", false)),
            Map.entry("agreed", new Label("동의", true)),
            Map.entry("refreshToken", new Label("로그인 정보", false)),
            Map.entry("nickname", new Label("별칭", false)),
            Map.entry("birthYearMonth", new Label("생년월", false)),
            Map.entry("guardianConsent", new Label("법정대리인 동의", true)),
            Map.entry("clientRecordId", new Label("기록 번호", false)),
            Map.entry("recordedAt", new Label("기록 시각", false)),
            Map.entry("analyzedSeconds", new Label("분석한 시간", false)),
            Map.entry("jointPlaySeconds", new Label("함께 논 시간", false)),
            Map.entry("responseLatencySec", new Label("반응 시간", false)),
            Map.entry("childBidCount", new Label("아이가 건넨 신호 수", false)),
            Map.entry("respondedBidCount", new Label("반응한 신호 수", false)),
            Map.entry("distressEpisodeCount", new Label("울음·감정 폭발 횟수", false)),
            Map.entry("modelVersion", new Label("분석 모델 버전", false)),
            Map.entry("specVersion", new Label("지표 버전", false)),
            Map.entry("jointPlay", new Label("공유 설정", true)),
            Map.entry("responseLatency", new Label("공유 설정", true)),
            Map.entry("distress", new Label("공유 설정", true)),
            Map.entry("coachingCards", new Label("공유 설정", true)),
            Map.entry("aiObservations", new Label("공유 설정", true)),
            Map.entry("institutionCapture", new Label("공유 설정", true)),
            Map.entry("code", new Label("초대 코드", false)),
            Map.entry("title", new Label("목표 제목", false)),
            Map.entry("description", new Label("목표 설명", false)),
            Map.entry("status", new Label("목표 상태", true)),
            Map.entry("content", new Label("관찰 메모", false)),
            Map.entry("observedOn", new Label("관찰한 날짜", false)),
            Map.entry("receipt", new Label("관찰 기록", false)));

    public static String of(FieldError f) {
        Label label = LABELS.getOrDefault(lastSegment(f.getField()), FALLBACK);
        if (!f.contains(ConstraintViolation.class)) {
            return label.text() + obj(label.text()) + " 확인해 주세요.";
        }
        ConstraintViolation<?> v = f.unwrap(ConstraintViolation.class);
        String template = v.getMessageTemplate();
        boolean builtIn = template != null && (template.startsWith("{jakarta.validation.constraints.")
                || template.startsWith("{org.hibernate.validator.constraints."));
        if (!builtIn && f.getDefaultMessage() != null) {
            return f.getDefaultMessage(); // DTO에 직접 적은 한국어 문구
        }
        Map<String, Object> attrs = v.getConstraintDescriptor().getAttributes();
        String t = label.text();
        String code = v.getConstraintDescriptor().getAnnotation().annotationType().getSimpleName();
        return switch (code) {
            case "NotBlank", "NotEmpty", "NotNull" ->
                    t + obj(t) + (label.choice() ? " 선택해 주세요." : " 입력해 주세요.");
            case "Size" -> size(t, attrs, f.getRejectedValue() instanceof Collection<?>);
            case "Pattern", "Email" -> t + " 형식을 확인해 주세요.";
            case "Min", "DecimalMin" -> t + topic(t) + " " + attrs.get("value") + " 이상이어야 해요.";
            case "Max", "DecimalMax" -> t + topic(t) + " " + attrs.get("value") + " 이하여야 해요.";
            default -> t + obj(t) + " 확인해 주세요.";
        };
    }

    private static String size(String t, Map<String, Object> attrs, boolean collection) {
        int min = ((Number) attrs.getOrDefault("min", 0)).intValue();
        int max = ((Number) attrs.getOrDefault("max", Integer.MAX_VALUE)).intValue();
        if (collection) {
            return t + topic(t) + " " + max + "개까지 보낼 수 있어요.";
        }
        if (min > 0 && max < Integer.MAX_VALUE) {
            return t + topic(t) + " " + min + "~" + max + "자로 입력해 주세요.";
        }
        if (max < Integer.MAX_VALUE) {
            return t + topic(t) + " " + max + "자까지 입력할 수 있어요.";
        }
        return t + topic(t) + " " + min + "자 이상 입력해 주세요.";
    }

    static String lastSegment(String path) {
        String p = path.replaceAll("\\[[^]]*]", "");
        int dot = p.lastIndexOf('.');
        return dot < 0 ? p : p.substring(dot + 1);
    }

    /** 받침이 있으면 true(한글 음절로 끝날 때만 판단, 그 밖에는 받침 없음으로 본다). */
    static boolean hasFinalConsonant(String word) {
        if (word.isEmpty()) return false;
        char c = word.charAt(word.length() - 1);
        return c >= 0xAC00 && c <= 0xD7A3 && (c - 0xAC00) % 28 != 0;
    }

    private static String topic(String word) {
        return hasFinalConsonant(word) ? "은" : "는";
    }

    private static String obj(String word) {
        return hasFinalConsonant(word) ? "을" : "를";
    }
}
