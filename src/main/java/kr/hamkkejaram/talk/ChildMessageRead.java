package kr.hamkkejaram.talk;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/** 한 사람이 한 아이의 이야기를 어디까지 읽었는지. */
@Entity
@Table(name = "child_message_reads")
public class ChildMessageRead {
    @Embeddable
    public static class Key implements Serializable {
        @Column(name = "child_id")
        private Long childId;

        @Column(name = "user_id")
        private Long userId;

        protected Key() {}

        public Key(Long childId, Long userId) {
            this.childId = childId;
            this.userId = userId;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && Objects.equals(childId, k.childId) && Objects.equals(userId, k.userId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(childId, userId);
        }
    }

    @EmbeddedId
    private Key key;

    @Column(name = "last_read_id", nullable = false)
    private Long lastReadId;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ChildMessageRead() {}

    public ChildMessageRead(long childId, long userId, long lastReadId, Instant now) {
        this.key = new Key(childId, userId);
        this.lastReadId = lastReadId;
        this.updatedAt = now;
    }

    public Long getLastReadId() { return lastReadId; }

    /** 읽은 자리는 앞으로만 간다(늦게 온 요청이 되돌리지 않게). */
    public void advance(long id, Instant now) {
        if (id > lastReadId) {
            lastReadId = id;
            updatedAt = now;
        }
    }
}
