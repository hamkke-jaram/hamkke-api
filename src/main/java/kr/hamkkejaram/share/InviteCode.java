package kr.hamkkejaram.share;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "invite_codes")
public class InviteCode {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "child_id", nullable = false)
    private Long childId;

    @Column(nullable = false, unique = true, length = 8)
    private String code;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "used_by")
    private Long usedBy;

    protected InviteCode() {}

    public InviteCode(Long childId, String code, Instant createdAt, Instant expiresAt) {
        this.childId = childId;
        this.code = code;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public boolean isUsable(Instant now) {
        return usedAt == null && now.isBefore(expiresAt);
    }

    public void markUsed(Long teacherId, Instant now) {
        this.usedAt = now;
        this.usedBy = teacherId;
    }

    public Long getChildId() { return childId; }

    public String getCode() { return code; }

    public Instant getCreatedAt() { return createdAt; }

    public Instant getExpiresAt() { return expiresAt; }
}
