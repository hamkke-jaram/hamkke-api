package kr.hamkkejaram.support;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** 지역 기관 한 곳(SUP-03·04). 공공데이터 CSV 가져오기로만 들어오고, 아이·사용자 데이터와 연결하지 않는다. */
@Entity
@Table(name = "support_facilities")
public class SupportFacility {
    static final String TAG_SEPARATOR = ";";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private FacilityCategory category;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 20)
    private String sido;

    @Column(length = 30)
    private String sigungu;

    @Column(nullable = false, length = 200)
    private String address;

    @Column(length = 30)
    private String phone;

    @Column(length = 300)
    private String homepage;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private FacilityOperator operator;

    @Column(length = 300)
    private String tags;

    @Column(name = "source_name", nullable = false, length = 100)
    private String sourceName;

    @Column(name = "source_date", nullable = false)
    private LocalDate sourceDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private FacilityStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected SupportFacility() {}

    SupportFacility(FacilityRow row, FacilityStatus status, Instant now) {
        this.category = row.category();
        this.name = row.name();
        this.address = row.address();
        copyFrom(row);
        this.status = status;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** 키(유형·이름·주소)를 뺀 값이 이 행과 같은지. */
    boolean sameContentAs(FacilityRow row) {
        return Objects.equals(sido, row.sido()) && Objects.equals(sigungu, row.sigungu())
                && Objects.equals(phone, row.phone()) && Objects.equals(homepage, row.homepage())
                && operator == row.operator() && Objects.equals(tags, row.joinedTags())
                && Objects.equals(sourceName, row.sourceName()) && Objects.equals(sourceDate, row.sourceDate());
    }

    void update(FacilityRow row, FacilityStatus status, Instant now) {
        copyFrom(row);
        this.status = status;
        this.updatedAt = now;
    }

    private void copyFrom(FacilityRow row) {
        this.sido = row.sido();
        this.sigungu = row.sigungu();
        this.phone = row.phone();
        this.homepage = row.homepage();
        this.operator = row.operator();
        this.tags = row.joinedTags();
        this.sourceName = row.sourceName();
        this.sourceDate = row.sourceDate();
    }

    public Long getId() { return id; }

    public FacilityCategory getCategory() { return category; }

    public String getName() { return name; }

    public String getSido() { return sido; }

    public String getSigungu() { return sigungu; }

    public String getAddress() { return address; }

    public String getPhone() { return phone; }

    public String getHomepage() { return homepage; }

    public FacilityOperator getOperator() { return operator; }

    public List<String> getTagList() {
        if (tags == null || tags.isEmpty()) return List.of();
        return Arrays.stream(tags.split(TAG_SEPARATOR)).filter(t -> !t.isEmpty()).toList();
    }

    public String getSourceName() { return sourceName; }

    public LocalDate getSourceDate() { return sourceDate; }

    public FacilityStatus getStatus() { return status; }

    public Instant getUpdatedAt() { return updatedAt; }
}
