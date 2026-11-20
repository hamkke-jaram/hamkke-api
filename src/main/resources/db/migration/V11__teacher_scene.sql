-- 선생님용 장면 요약: AI가 쓴 아이 한 줄·어른 한 줄(각 40자, 검사를 지나간 것만)과
-- 보호자가 고른 "이 기록은 선생님에게 보내지 않기". 예전 기록은 두 줄이 비어 있어 태그만 보인다.
ALTER TABLE ai_observations ADD COLUMN child_line VARCHAR(40) NULL;
ALTER TABLE ai_observations ADD COLUMN parent_line VARCHAR(40) NULL;
ALTER TABLE ai_observations ADD COLUMN hidden_from_teacher BOOLEAN NOT NULL DEFAULT FALSE;
