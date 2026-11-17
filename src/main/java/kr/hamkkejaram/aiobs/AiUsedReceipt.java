package kr.hamkkejaram.aiobs;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

import java.time.Instant;

/**
 * 저장에 쓴 영수증 하나(V6). 관찰 기록을 지운 뒤에도 같은 영수증으로 다시 저장하지 못하게 한다(AIO-07).
 *
 * <p>아이·사용자를 가리키는 값은 두지 않는다: {@code jti}(무작위 UUID)와, 그 영수증이 더는 통하지 않는 시각뿐이다.
 * 관찰 문장도 영수증 자체도 담지 않는다. 만료된 행은 {@link UsedReceipts#purgeExpired()}가 지운다.
 */
@Entity
@Table(name = "ai_used_receipts")
public class AiUsedReceipt implements Persistable<String> {
    @Id
    @Column(nullable = false, length = 36)
    private String jti;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /** id를 직접 정하므로, 새 행인지 Spring Data에 알려 준다(조회 없이 바로 INSERT → 겹치면 UNIQUE 위반). */
    @Transient
    private boolean fresh = true;

    protected AiUsedReceipt() {}

    public AiUsedReceipt(String jti, Instant expiresAt) {
        this.jti = jti;
        this.expiresAt = expiresAt;
    }

    @Override
    public String getId() { return jti; }

    @Override
    public boolean isNew() { return fresh; }

    @PostLoad
    @PostPersist
    void markStored() {
        this.fresh = false;
    }

    public String getJti() { return jti; }

    public Instant getExpiresAt() { return expiresAt; }
}
