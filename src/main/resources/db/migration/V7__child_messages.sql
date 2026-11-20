-- 보호자와 연결된 선생님이 아이에 대해 주고받는 이야기(TLK). 관찰 메모에 단 답이면 observation_id를 둔다.
-- 아이·작성자·메모가 지워지면 함께 지운다(관찰 메모와 같은 규칙).
CREATE TABLE child_messages (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    child_id       BIGINT       NOT NULL,
    author_id      BIGINT       NOT NULL,
    author_role    VARCHAR(16)  NOT NULL,
    observation_id BIGINT       NULL,
    content        VARCHAR(500) NOT NULL,
    created_at     DATETIME(6)  NOT NULL,
    CONSTRAINT fk_messages_child FOREIGN KEY (child_id) REFERENCES children (id) ON DELETE CASCADE,
    CONSTRAINT fk_messages_author FOREIGN KEY (author_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_messages_note FOREIGN KEY (observation_id) REFERENCES observation_notes (id) ON DELETE CASCADE
);
CREATE INDEX ix_messages_child ON child_messages (child_id, id);

-- 사람마다 이 아이의 이야기를 어디까지 읽었는지(안 읽은 이야기 수를 세는 데 쓴다).
CREATE TABLE child_message_reads (
    child_id     BIGINT      NOT NULL,
    user_id      BIGINT      NOT NULL,
    last_read_id BIGINT      NOT NULL,
    updated_at   DATETIME(6) NOT NULL,
    PRIMARY KEY (child_id, user_id),
    CONSTRAINT fk_message_reads_child FOREIGN KEY (child_id) REFERENCES children (id) ON DELETE CASCADE,
    CONSTRAINT fk_message_reads_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);
