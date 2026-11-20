package kr.hamkkejaram.note;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "observation_notes")
public class ObservationNote {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "child_id", nullable = false)
    private Long childId;

    @Column(name = "teacher_id", nullable = false)
    private Long teacherId;

    @Column(nullable = false, length = 1000)
    private String content;

    @Column(name = "observed_on", nullable = false)
    private LocalDate observedOn;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** 선생님 이야기의 제목(선택). */
    @Column(length = 40)
    private String title;

    /** 첨부한 코칭 카드(이 아이가 받은 카드 전달 기록, 선택). */
    @Column(name = "card_delivery_id")
    private Long cardDeliveryId;

    protected ObservationNote() {}

    public ObservationNote(Long childId, Long teacherId, String title, String content, LocalDate observedOn,
                           Long cardDeliveryId, Instant createdAt) {
        this(childId, teacherId, content, observedOn, createdAt);
        this.title = title;
        this.cardDeliveryId = cardDeliveryId;
    }

    public ObservationNote(Long childId, Long teacherId, String content, LocalDate observedOn, Instant createdAt) {
        this.childId = childId;
        this.teacherId = teacherId;
        this.content = content;
        this.observedOn = observedOn;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }

    public Long getChildId() { return childId; }

    public Long getTeacherId() { return teacherId; }

    public String getContent() { return content; }

    public LocalDate getObservedOn() { return observedOn; }

    public Instant getCreatedAt() { return createdAt; }

    public String getTitle() { return title; }

    public Long getCardDeliveryId() { return cardDeliveryId; }
}
