package kr.hamkkejaram.aiobs;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import kr.hamkkejaram.aiobs.ObservationContent.Moment;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * AI 관찰 기록. VL 모델이 장면 사진을 보고 적은 글자만 담는다(요약·태그·시각별 메모). 사진은 저장하지 않는다.
 * AI 서버가 서명한 영수증으로만 만들어진다. 태그 목록과 메모는 JSON 글자로 담는다(V5 참고).
 */
@Entity
@Table(name = "ai_observations")
public class AiObservation {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {};
    private static final TypeReference<List<Moment>> MOMENTS = new TypeReference<>() {};

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "child_id", nullable = false)
    private Long childId;

    /** 만든 사람(가정 기록은 보호자, 기관 기록은 선생님). 그 계정이 지워지면 비워진다. */
    @Column(name = "created_by")
    private Long createdBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ObservationSource source;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    @Column(length = 600)
    private String summary;

    @Column(name = "child_states", nullable = false, length = 200)
    private String childStatesJson;

    @Column(name = "adult_responses", nullable = false, length = 200)
    private String adultResponsesJson;

    @Column(name = "moments", nullable = false, length = 4000)
    private String momentsJson;

    @Column(name = "people_count", nullable = false)
    private int peopleCount;

    @Column(name = "model_version", nullable = false, length = 100)
    private String modelVersion;

    @Column(name = "prompt_version", nullable = false, length = 40)
    private String promptVersion;

    @Column(name = "frame_count", nullable = false)
    private int frameCount;

    /** 분석 토큰의 jti. 같은 영수증으로 두 번 저장할 수 없다(UNIQUE). */
    @Column(name = "session_jti", nullable = false, unique = true, length = 36)
    private String sessionJti;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AiObservation() {}

    public AiObservation(Long childId, Long createdBy, ObservationSource source, Instant recordedAt,
                         ObservationContent content, String modelVersion, String promptVersion, int frameCount,
                         String sessionJti, Instant createdAt) {
        this.childId = childId;
        this.createdBy = createdBy;
        this.source = source;
        this.recordedAt = recordedAt;
        this.summary = content.summary();
        this.childStatesJson = write(content.childStates().stream().map(Enum::name).toList());
        this.adultResponsesJson = write(content.adultResponses().stream().map(Enum::name).toList());
        this.momentsJson = write(content.moments());
        this.peopleCount = content.peopleCount();
        this.modelVersion = modelVersion;
        this.promptVersion = promptVersion;
        this.frameCount = frameCount;
        this.sessionJti = sessionJti;
        this.createdAt = createdAt;
    }

    public List<ChildState> childStates() {
        return enums(childStatesJson, ChildState.class);
    }

    public List<AdultResponse> adultResponses() {
        return enums(adultResponsesJson, AdultResponse.class);
    }

    public List<Moment> moments() {
        try {
            return JSON.readValue(momentsJson, MOMENTS);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored moments are not valid JSON: id=" + id, e);
        }
    }

    public Long getId() { return id; }

    public Long getChildId() { return childId; }

    public Long getCreatedBy() { return createdBy; }

    public ObservationSource getSource() { return source; }

    public Instant getRecordedAt() { return recordedAt; }

    public String getSummary() { return summary; }

    public int getPeopleCount() { return peopleCount; }

    public String getModelVersion() { return modelVersion; }

    public String getPromptVersion() { return promptVersion; }

    public int getFrameCount() { return frameCount; }

    public String getSessionJti() { return sessionJti; }

    public Instant getCreatedAt() { return createdAt; }

    private static String write(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialize observation field", e);
        }
    }

    /** 저장된 태그를 읽는다. 나중에 목록에서 빠진 값이 남아 있어도 조회가 깨지지 않게 모르는 값은 건너뛴다. */
    private <E extends Enum<E>> List<E> enums(String json, Class<E> type) {
        List<String> names;
        try {
            names = JSON.readValue(json, STRINGS);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored tags are not valid JSON: id=" + id, e);
        }
        List<E> out = new ArrayList<>();
        for (String name : names) {
            try {
                out.add(Enum.valueOf(type, name));
            } catch (IllegalArgumentException ignored) {
                // 더 이상 쓰지 않는 태그
            }
        }
        return out;
    }
}
