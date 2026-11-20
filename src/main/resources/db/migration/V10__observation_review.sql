-- Historical and institution observations retain their original display.
ALTER TABLE ai_observations ADD COLUMN review_status VARCHAR(20) NULL;
ALTER TABLE ai_observations ADD COLUMN reviewed_by BIGINT NULL;
ALTER TABLE ai_observations ADD COLUMN review_payload TEXT NULL;
ALTER TABLE ai_observations ADD COLUMN review_request_hash VARCHAR(64) NULL;
ALTER TABLE ai_observations ADD CONSTRAINT fk_ai_observation_reviewer
    FOREIGN KEY (reviewed_by) REFERENCES users(id) ON DELETE SET NULL;
ALTER TABLE interaction_records ADD COLUMN awaiting_ai_review BOOLEAN NOT NULL DEFAULT FALSE;
