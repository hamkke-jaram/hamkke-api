package kr.hamkkejaram.talk;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import kr.hamkkejaram.user.Role;

import java.time.Instant;

/** 보호자와 선생님이 아이에 대해 주고받는 이야기 한 건. 관찰 메모에 단 답이면 observationId가 있다. */
@Entity
@Table(name = "child_messages")
public class ChildMessage {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "child_id", nullable = false)
    private Long childId;

    @Column(name = "author_id", nullable = false)
    private Long authorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "author_role", nullable = false, length = 16)
    private Role authorRole;

    @Column(name = "observation_id")
    private Long observationId;

    @Column(nullable = false, length = 500)
    private String content;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ChildMessage() {}

    public ChildMessage(Long childId, Long authorId, Role authorRole, Long observationId, String content,
                        Instant createdAt) {
        this.childId = childId;
        this.authorId = authorId;
        this.authorRole = authorRole;
        this.observationId = observationId;
        this.content = content;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }

    public Long getChildId() { return childId; }

    public Long getAuthorId() { return authorId; }

    public Role getAuthorRole() { return authorRole; }

    public Long getObservationId() { return observationId; }

    public String getContent() { return content; }

    public Instant getCreatedAt() { return createdAt; }
}
