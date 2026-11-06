package kr.hamkkejaram.consent;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "consents")
public class Consent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "child_id")
    private Long childId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private ConsentType type;

    @Column(nullable = false, length = 30)
    private String version;

    @Column(name = "agreed_at", nullable = false)
    private Instant agreedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected Consent() {}

    public Consent(Long userId, Long childId, ConsentType type, String version, Instant agreedAt) {
        this.userId = userId;
        this.childId = childId;
        this.type = type;
        this.version = version;
        this.agreedAt = agreedAt;
    }

    public void revoke(Instant at) {
        if (revokedAt == null) revokedAt = at;
    }

    public Long getId() { return id; }

    public Long getUserId() { return userId; }

    public Long getChildId() { return childId; }

    public ConsentType getType() { return type; }

    public String getVersion() { return version; }

    public Instant getAgreedAt() { return agreedAt; }

    public Instant getRevokedAt() { return revokedAt; }
}
