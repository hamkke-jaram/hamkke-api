package kr.hamkkejaram.record;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** 기기에서 계산된 상호작용 지표. 원본 영상·음성은 이 테이블에도, 어디에도 저장하지 않는다. */
@Entity
@Table(name = "interaction_records")
public class InteractionRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "child_id", nullable = false)
    private Long childId;

    @Column(name = "client_record_id", nullable = false, length = 36)
    private String clientRecordId;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    @Column(name = "analyzed_seconds", nullable = false)
    private double analyzedSeconds;

    @Column(name = "joint_play_seconds", nullable = false)
    private double jointPlaySeconds;

    @Column(name = "response_latency_sec")
    private Double responseLatencySec;

    @Column(name = "child_bid_count", nullable = false)
    private int childBidCount;

    @Column(name = "responded_bid_count", nullable = false)
    private int respondedBidCount;

    @Column(name = "distress_count", nullable = false)
    private int distressCount;

    @Column(name = "model_version", nullable = false, length = 100)
    private String modelVersion;

    @Column(name = "spec_version", nullable = false, length = 20)
    private String specVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "change_trend", nullable = false, length = 20)
    private ChangeTrend changeTrend;

    @Enumerated(EnumType.STRING)
    @Column(name = "joint_play_change", nullable = false, length = 10)
    private MetricChange jointPlayChange;

    @Enumerated(EnumType.STRING)
    @Column(name = "response_change", nullable = false, length = 10)
    private MetricChange responseChange;

    @Enumerated(EnumType.STRING)
    @Column(name = "calm_change", nullable = false, length = 10)
    private MetricChange calmChange;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected InteractionRecord() {}

    public InteractionRecord(Long childId, String clientRecordId, Instant recordedAt, double analyzedSeconds,
                             double jointPlaySeconds, Double responseLatencySec, int childBidCount,
                             int respondedBidCount, int distressCount, String modelVersion, String specVersion,
                             Instant createdAt) {
        this.childId = childId;
        this.clientRecordId = clientRecordId;
        this.recordedAt = recordedAt;
        this.analyzedSeconds = analyzedSeconds;
        this.jointPlaySeconds = jointPlaySeconds;
        this.responseLatencySec = responseLatencySec;
        this.childBidCount = childBidCount;
        this.respondedBidCount = respondedBidCount;
        this.distressCount = distressCount;
        this.modelVersion = modelVersion;
        this.specVersion = specVersion;
        this.createdAt = createdAt;
    }

    public void applyTrend(TrendCalculator.Result result) {
        this.changeTrend = result.trend();
        this.jointPlayChange = result.changes().jointPlay();
        this.responseChange = result.changes().response();
        this.calmChange = result.changes().calm();
    }

    public MetricValues values() {
        return new MetricValues(jointPlayRatio(), responseLatencySec, distressPerTenMinutes());
    }

    public double jointPlayRatio() {
        return analyzedSeconds <= 0 ? 0 : jointPlaySeconds / analyzedSeconds;
    }

    public double distressPerTenMinutes() {
        return analyzedSeconds <= 0 ? 0 : distressCount / analyzedSeconds * 600.0;
    }

    public MetricChanges changes() {
        return new MetricChanges(jointPlayChange, responseChange, calmChange);
    }

    public Long getId() { return id; }

    public Long getChildId() { return childId; }

    public String getClientRecordId() { return clientRecordId; }

    public Instant getRecordedAt() { return recordedAt; }

    public double getAnalyzedSeconds() { return analyzedSeconds; }

    public double getJointPlaySeconds() { return jointPlaySeconds; }

    public Double getResponseLatencySec() { return responseLatencySec; }

    public int getChildBidCount() { return childBidCount; }

    public int getRespondedBidCount() { return respondedBidCount; }

    public int getDistressCount() { return distressCount; }

    public String getModelVersion() { return modelVersion; }

    public String getSpecVersion() { return specVersion; }

    public ChangeTrend getChangeTrend() { return changeTrend; }

    public Instant getCreatedAt() { return createdAt; }
}
