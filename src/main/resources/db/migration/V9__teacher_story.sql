-- 선생님 이야기(관찰 메모)의 제목과 첨부한 코칭 카드(이 아이가 받은 카드 전달 기록). 둘 다 선택.
-- 카드 전달 기록이 지워지면 첨부만 빠진다(이야기는 남는다).
ALTER TABLE observation_notes ADD COLUMN title VARCHAR(40) NULL;
ALTER TABLE observation_notes ADD COLUMN card_delivery_id BIGINT NULL;
ALTER TABLE observation_notes ADD CONSTRAINT fk_notes_delivery
    FOREIGN KEY (card_delivery_id) REFERENCES card_deliveries (id) ON DELETE SET NULL;

-- 선생님 홈 머리에 보이는 기관·반 이름(선택, 선생님만 쓴다).
ALTER TABLE users ADD COLUMN institution VARCHAR(40) NULL;
ALTER TABLE users ADD COLUMN class_name VARCHAR(30) NULL;
