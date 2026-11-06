package kr.hamkkejaram.support;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "support_resources")
public class SupportResource {
    @Id
    private Long id;

    @Column(nullable = false, length = 60)
    private String name;

    @Column(nullable = false, length = 300)
    private String description;

    @Column(length = 30)
    private String phone;

    @Column(length = 300)
    private String url;

    @Column(length = 60)
    private String hours;

    @Column(nullable = false, length = 20)
    private String category;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    protected SupportResource() {}

    public Long getId() { return id; }

    public String getName() { return name; }

    public String getDescription() { return description; }

    public String getPhone() { return phone; }

    public String getUrl() { return url; }

    public String getHours() { return hours; }

    public String getCategory() { return category; }

    public int getSortOrder() { return sortOrder; }
}
