package kr.hamkkejaram.user;

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
@Table(name = "users")
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 254)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(name = "display_name", nullable = false, length = 30)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected User() {}

    public User(String email, String passwordHash, String displayName, Role role, Instant createdAt) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.displayName = displayName;
        this.role = role;
        this.createdAt = createdAt;
    }

    /** 표시명 수정. 호출하는 쪽이 앞뒤 공백을 지우고 길이(1~30자)를 확인한다. */
    public void changeDisplayName(String displayName) {
        this.displayName = displayName;
    }

    /** 비밀번호 변경. BCrypt 해시만 받는다. */
    public void changePasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public Long getId() { return id; }

    public String getEmail() { return email; }

    public String getPasswordHash() { return passwordHash; }

    public String getDisplayName() { return displayName; }

    public Role getRole() { return role; }

    public Instant getCreatedAt() { return createdAt; }
}
