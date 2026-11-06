-- 함께자람 스키마 v1. MySQL 8 (utf8mb4) 기준이며, 테스트용 H2(MODE=MySQL)에서도 그대로 돈다.
-- 원본 영상·음성을 담는 컬럼/테이블은 없다.

CREATE TABLE users (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    email         VARCHAR(254) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    display_name  VARCHAR(30)  NOT NULL,
    role          VARCHAR(20)  NOT NULL,
    created_at    DATETIME(6)  NOT NULL,
    CONSTRAINT uq_users_email UNIQUE (email)
);

CREATE TABLE refresh_tokens (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id    BIGINT      NOT NULL,
    token_hash CHAR(64)    NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    revoked_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT uq_refresh_tokens_hash UNIQUE (token_hash),
    CONSTRAINT fk_refresh_tokens_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);
CREATE INDEX ix_refresh_tokens_user ON refresh_tokens (user_id);

CREATE TABLE children (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    parent_id        BIGINT      NOT NULL,
    nickname         VARCHAR(20) NOT NULL,
    birth_year_month CHAR(7)     NOT NULL,
    created_at       DATETIME(6) NOT NULL,
    CONSTRAINT fk_children_parent FOREIGN KEY (parent_id) REFERENCES users (id) ON DELETE CASCADE
);
CREATE INDEX ix_children_parent ON children (parent_id);

-- 동의·철회 이력. 탈퇴·아동 삭제 후에도 이력은 남고 연결 키만 비워진다.
CREATE TABLE consents (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id    BIGINT      NULL,
    child_id   BIGINT      NULL,
    type       VARCHAR(40) NOT NULL,
    version    VARCHAR(30) NOT NULL,
    agreed_at  DATETIME(6) NOT NULL,
    revoked_at DATETIME(6) NULL,
    CONSTRAINT fk_consents_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT fk_consents_child FOREIGN KEY (child_id) REFERENCES children (id) ON DELETE SET NULL
);
CREATE INDEX ix_consents_user ON consents (user_id);
CREATE INDEX ix_consents_child ON consents (child_id, type);

CREATE TABLE share_settings (
    child_id         BIGINT      NOT NULL PRIMARY KEY,
    joint_play       BOOLEAN     NOT NULL DEFAULT FALSE,
    response_latency BOOLEAN     NOT NULL DEFAULT FALSE,
    distress         BOOLEAN     NOT NULL DEFAULT FALSE,
    coaching_cards   BOOLEAN     NOT NULL DEFAULT FALSE,
    updated_at       DATETIME(6) NOT NULL,
    CONSTRAINT fk_share_settings_child FOREIGN KEY (child_id) REFERENCES children (id) ON DELETE CASCADE
);

CREATE TABLE interaction_records (
    id                   BIGINT AUTO_INCREMENT PRIMARY KEY,
    child_id             BIGINT       NOT NULL,
    client_record_id     CHAR(36)     NOT NULL,
    recorded_at          DATETIME(6)  NOT NULL,
    analyzed_seconds     DOUBLE       NOT NULL,
    joint_play_seconds   DOUBLE       NOT NULL,
    response_latency_sec DOUBLE       NULL,
    child_bid_count      INT          NOT NULL,
    responded_bid_count  INT          NOT NULL,
    distress_count       INT          NOT NULL,
    model_version        VARCHAR(100) NOT NULL,
    spec_version         VARCHAR(20)  NOT NULL,
    change_trend         VARCHAR(20)  NOT NULL,
    joint_play_change    VARCHAR(10)  NOT NULL,
    response_change      VARCHAR(10)  NOT NULL,
    calm_change          VARCHAR(10)  NOT NULL,
    created_at           DATETIME(6)  NOT NULL,
    CONSTRAINT uq_records_client UNIQUE (child_id, client_record_id),
    CONSTRAINT fk_records_child FOREIGN KEY (child_id) REFERENCES children (id) ON DELETE CASCADE
);
CREATE INDEX ix_records_child_time ON interaction_records (child_id, recorded_at);

CREATE TABLE coaching_cards (
    id           BIGINT        NOT NULL PRIMARY KEY,
    code         VARCHAR(40)   NOT NULL,
    title        VARCHAR(80)   NOT NULL,
    body         VARCHAR(2000) NOT NULL,
    situation    VARCHAR(20)   NOT NULL,
    metric_focus VARCHAR(20)   NOT NULL,
    evidence_tag VARCHAR(120)  NOT NULL,
    source_title VARCHAR(300)  NOT NULL,
    source_url   VARCHAR(500)  NULL,
    author       VARCHAR(60)   NOT NULL,
    reviewer     VARCHAR(60)   NULL,
    active       BOOLEAN       NOT NULL DEFAULT TRUE,
    CONSTRAINT uq_cards_code UNIQUE (code),
    CONSTRAINT ck_cards_source CHECK (CHAR_LENGTH(source_title) > 0)
);

CREATE TABLE card_deliveries (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    child_id     BIGINT      NOT NULL,
    card_id      BIGINT      NOT NULL,
    record_id    BIGINT      NULL,
    delivered_at DATETIME(6) NOT NULL,
    tried_at     DATETIME(6) NULL,
    CONSTRAINT fk_deliveries_child FOREIGN KEY (child_id) REFERENCES children (id) ON DELETE CASCADE,
    CONSTRAINT fk_deliveries_card FOREIGN KEY (card_id) REFERENCES coaching_cards (id),
    CONSTRAINT fk_deliveries_record FOREIGN KEY (record_id) REFERENCES interaction_records (id) ON DELETE SET NULL
);
CREATE INDEX ix_deliveries_child_time ON card_deliveries (child_id, delivered_at);
CREATE INDEX ix_deliveries_record ON card_deliveries (record_id);

CREATE TABLE invite_codes (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    child_id   BIGINT      NOT NULL,
    code       CHAR(8)     NOT NULL,
    created_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    used_at    DATETIME(6) NULL,
    used_by    BIGINT      NULL,
    CONSTRAINT uq_invite_codes_code UNIQUE (code),
    CONSTRAINT fk_invite_codes_child FOREIGN KEY (child_id) REFERENCES children (id) ON DELETE CASCADE,
    CONSTRAINT fk_invite_codes_user FOREIGN KEY (used_by) REFERENCES users (id) ON DELETE SET NULL
);
CREATE INDEX ix_invite_codes_child ON invite_codes (child_id);

CREATE TABLE teacher_links (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    teacher_id BIGINT      NOT NULL,
    child_id   BIGINT      NOT NULL,
    linked_at  DATETIME(6) NOT NULL,
    CONSTRAINT uq_teacher_links UNIQUE (teacher_id, child_id),
    CONSTRAINT fk_links_teacher FOREIGN KEY (teacher_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_links_child FOREIGN KEY (child_id) REFERENCES children (id) ON DELETE CASCADE
);
CREATE INDEX ix_links_child ON teacher_links (child_id);

CREATE TABLE child_goals (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    child_id    BIGINT       NOT NULL,
    teacher_id  BIGINT       NOT NULL,
    title       VARCHAR(60)  NOT NULL,
    description VARCHAR(500) NOT NULL,
    status      VARCHAR(20)  NOT NULL,
    created_at  DATETIME(6)  NOT NULL,
    updated_at  DATETIME(6)  NOT NULL,
    CONSTRAINT fk_goals_child FOREIGN KEY (child_id) REFERENCES children (id) ON DELETE CASCADE,
    CONSTRAINT fk_goals_teacher FOREIGN KEY (teacher_id) REFERENCES users (id) ON DELETE CASCADE
);
CREATE INDEX ix_goals_child ON child_goals (child_id);

CREATE TABLE observation_notes (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    child_id    BIGINT        NOT NULL,
    teacher_id  BIGINT        NOT NULL,
    content     VARCHAR(1000) NOT NULL,
    observed_on DATE          NOT NULL,
    created_at  DATETIME(6)   NOT NULL,
    CONSTRAINT fk_notes_child FOREIGN KEY (child_id) REFERENCES children (id) ON DELETE CASCADE,
    CONSTRAINT fk_notes_teacher FOREIGN KEY (teacher_id) REFERENCES users (id) ON DELETE CASCADE
);
CREATE INDEX ix_notes_child ON observation_notes (child_id, observed_on);

CREATE TABLE support_resources (
    id          BIGINT       NOT NULL PRIMARY KEY,
    name        VARCHAR(60)  NOT NULL,
    description VARCHAR(300) NOT NULL,
    phone       VARCHAR(30)  NULL,
    url         VARCHAR(300) NULL,
    hours       VARCHAR(60)  NULL,
    category    VARCHAR(20)  NOT NULL,
    sort_order  INT          NOT NULL
);
