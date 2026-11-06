package kr.hamkkejaram.share;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "teacher_links")
public class TeacherLink {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "teacher_id", nullable = false)
    private Long teacherId;

    @Column(name = "child_id", nullable = false)
    private Long childId;

    @Column(name = "linked_at", nullable = false)
    private Instant linkedAt;

    protected TeacherLink() {}

    public TeacherLink(Long teacherId, Long childId, Instant linkedAt) {
        this.teacherId = teacherId;
        this.childId = childId;
        this.linkedAt = linkedAt;
    }

    public Long getTeacherId() { return teacherId; }

    public Long getChildId() { return childId; }

    public Instant getLinkedAt() { return linkedAt; }
}
