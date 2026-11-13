package kr.hamkkejaram.share;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** 교사에게 보여줄 항목과 기관 촬영 허용 여부. 기본값은 모두 꺼짐. */
@Entity
@Table(name = "share_settings")
public class ShareSetting {
    @Id
    @Column(name = "child_id")
    private Long childId;

    @Column(name = "joint_play", nullable = false)
    private boolean jointPlay;

    @Column(name = "response_latency", nullable = false)
    private boolean responseLatency;

    @Column(nullable = false)
    private boolean distress;

    @Column(name = "coaching_cards", nullable = false)
    private boolean coachingCards;

    /** 가정에서 만든 AI 관찰 기록을 연결된 선생님에게 보여 준다. */
    @Column(name = "ai_observations", nullable = false)
    private boolean aiObservations;

    /** 연결된 선생님이 기관에서 촬영·분석할 수 있다(AI 동의가 함께 있어야 한다). */
    @Column(name = "institution_capture", nullable = false)
    private boolean institutionCapture;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ShareSetting() {}

    public static ShareSetting allPrivate(Long childId, Instant now) {
        ShareSetting s = new ShareSetting();
        s.childId = childId;
        s.updatedAt = now;
        return s;
    }

    public void update(boolean jointPlay, boolean responseLatency, boolean distress, boolean coachingCards,
                       Instant now) {
        this.jointPlay = jointPlay;
        this.responseLatency = responseLatency;
        this.distress = distress;
        this.coachingCards = coachingCards;
        this.updatedAt = now;
    }

    /** AI 관찰 기록 관련 두 항목. null은 "지금 값 그대로"다(두 항목을 모르는 예전 화면의 요청을 그대로 받기 위해). */
    public void updateAi(Boolean aiObservations, Boolean institutionCapture, Instant now) {
        if (aiObservations != null) this.aiObservations = aiObservations;
        if (institutionCapture != null) this.institutionCapture = institutionCapture;
        this.updatedAt = now;
    }

    public Long getChildId() { return childId; }

    public boolean isJointPlay() { return jointPlay; }

    public boolean isResponseLatency() { return responseLatency; }

    public boolean isDistress() { return distress; }

    public boolean isCoachingCards() { return coachingCards; }

    public boolean isAiObservations() { return aiObservations; }

    public boolean isInstitutionCapture() { return institutionCapture; }

    public Instant getUpdatedAt() { return updatedAt; }
}
