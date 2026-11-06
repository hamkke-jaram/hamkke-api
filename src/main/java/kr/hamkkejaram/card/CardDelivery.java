package kr.hamkkejaram.card;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "card_deliveries")
public class CardDelivery {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "child_id", nullable = false)
    private Long childId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "card_id", nullable = false)
    private CoachingCard card;

    @Column(name = "record_id")
    private Long recordId;

    @Column(name = "delivered_at", nullable = false)
    private Instant deliveredAt;

    @Column(name = "tried_at")
    private Instant triedAt;

    protected CardDelivery() {}

    public CardDelivery(Long childId, CoachingCard card, Long recordId, Instant deliveredAt) {
        this.childId = childId;
        this.card = card;
        this.recordId = recordId;
        this.deliveredAt = deliveredAt;
    }

    public void markTried(Instant now) {
        if (triedAt == null) triedAt = now;
    }

    public Long getId() { return id; }

    public Long getChildId() { return childId; }

    public CoachingCard getCard() { return card; }

    public Long getRecordId() { return recordId; }

    public Instant getDeliveredAt() { return deliveredAt; }

    public Instant getTriedAt() { return triedAt; }
}
