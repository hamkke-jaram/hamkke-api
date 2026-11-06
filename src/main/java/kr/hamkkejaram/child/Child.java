package kr.hamkkejaram.child;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;

/** 아동. 실명·주민번호는 받지 않고 별칭과 생년월만 둔다. */
@Entity
@Table(name = "children")
public class Child {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "parent_id", nullable = false)
    private Long parentId;

    @Column(nullable = false, length = 20)
    private String nickname;

    @Column(name = "birth_year_month", nullable = false, length = 7)
    private String birthYearMonth;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Child() {}

    public Child(Long parentId, String nickname, YearMonth birthYearMonth, Instant createdAt) {
        this.parentId = parentId;
        this.nickname = nickname;
        this.birthYearMonth = birthYearMonth.toString();
        this.createdAt = createdAt;
    }

    public void update(String nickname, YearMonth birthYearMonth) {
        if (nickname != null) this.nickname = nickname;
        if (birthYearMonth != null) this.birthYearMonth = birthYearMonth.toString();
    }

    public int ageMonths(LocalDate today) {
        YearMonth birth = YearMonth.parse(birthYearMonth);
        long months = ChronoUnit.MONTHS.between(birth, YearMonth.from(today));
        return (int) Math.max(0, months);
    }

    public Long getId() { return id; }

    public Long getParentId() { return parentId; }

    public String getNickname() { return nickname; }

    public String getBirthYearMonth() { return birthYearMonth; }

    public Instant getCreatedAt() { return createdAt; }
}
