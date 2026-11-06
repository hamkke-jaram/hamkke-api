package kr.hamkkejaram.share;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** 교사에게 보여줄 항목. 기본값은 모두 비공개. */
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

    public Long getChildId() { return childId; }

    public boolean isJointPlay() { return jointPlay; }

    public boolean isResponseLatency() { return responseLatency; }

    public boolean isDistress() { return distress; }

    public boolean isCoachingCards() { return coachingCards; }

    public Instant getUpdatedAt() { return updatedAt; }
}
