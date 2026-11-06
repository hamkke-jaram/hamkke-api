package kr.hamkkejaram.card;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 사람이 쓴 코칭 카드. AI는 이 카드를 고르기만 하고 문장을 만들지 않는다. 출처는 필수. */
@Entity
@Table(name = "coaching_cards")
public class CoachingCard {
    @Id
    private Long id;

    @Column(nullable = false, unique = true, length = 40)
    private String code;

    @Column(nullable = false, length = 80)
    private String title;

    @Column(nullable = false, length = 2000)
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CardSituation situation;

    @Enumerated(EnumType.STRING)
    @Column(name = "metric_focus", nullable = false, length = 20)
    private MetricFocus metricFocus;

    @Column(name = "evidence_tag", nullable = false, length = 120)
    private String evidenceTag;

    @Column(name = "source_title", nullable = false, length = 300)
    private String sourceTitle;

    @Column(name = "source_url", length = 500)
    private String sourceUrl;

    @Column(nullable = false, length = 60)
    private String author;

    @Column(length = 60)
    private String reviewer;

    @Column(nullable = false)
    private boolean active;

    protected CoachingCard() {}

    public Long getId() { return id; }

    public String getCode() { return code; }

    public String getTitle() { return title; }

    public String getBody() { return body; }

    public CardSituation getSituation() { return situation; }

    public MetricFocus getMetricFocus() { return metricFocus; }

    public String getEvidenceTag() { return evidenceTag; }

    public String getSourceTitle() { return sourceTitle; }

    public String getSourceUrl() { return sourceUrl; }

    public String getAuthor() { return author; }

    public String getReviewer() { return reviewer; }

    public boolean isActive() { return active; }
}
