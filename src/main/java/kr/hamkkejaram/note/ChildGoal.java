package kr.hamkkejaram.note;

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
@Table(name = "child_goals")
public class ChildGoal {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "child_id", nullable = false)
    private Long childId;

    @Column(name = "teacher_id", nullable = false)
    private Long teacherId;

    @Column(nullable = false, length = 60)
    private String title;

    @Column(nullable = false, length = 500)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private GoalStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ChildGoal() {}

    public ChildGoal(Long childId, Long teacherId, String title, String description, Instant now) {
        this.childId = childId;
        this.teacherId = teacherId;
        this.title = title;
        this.description = description;
        this.status = GoalStatus.IN_PROGRESS;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void update(String title, String description, GoalStatus status, Instant now) {
        if (title != null) this.title = title;
        if (description != null) this.description = description;
        if (status != null) this.status = status;
        this.updatedAt = now;
    }

    public Long getId() { return id; }

    public Long getChildId() { return childId; }

    public Long getTeacherId() { return teacherId; }

    public String getTitle() { return title; }

    public String getDescription() { return description; }

    public GoalStatus getStatus() { return status; }

    public Instant getCreatedAt() { return createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
}
