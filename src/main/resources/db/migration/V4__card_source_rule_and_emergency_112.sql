-- V4: 코칭 카드 출처 규칙 강화 + 긴급 연락처(112) 분리. V1~V3은 체크섬 때문에 고치지 않는다.
-- MySQL 8(8.0.19+)과 테스트용 H2(MODE=MySQL)에서 모두 돈다.

-- (a) 출처 제목이 스페이스만으로 된 카드는 저장하지 않는다(기존 규칙은 빈 문자열만 막았다).
--     TRIM은 스페이스만 지우므로 탭·줄바꿈만 있는 제목은 거르지 못한다(SPEC 2·5장에 명시).
--     REGEXP는 H2(Java 정규식)와 MySQL 8(ICU)의 문자 클래스·역슬래시 처리 방식이 달라 같은 식으로 두 DB를 맞추기 어렵다.
ALTER TABLE coaching_cards DROP CONSTRAINT ck_cards_source;
ALTER TABLE coaching_cards ADD CONSTRAINT ck_cards_source_title CHECK (CHAR_LENGTH(TRIM(source_title)) > 0);

-- (b) 긴급 연락처: 한 번 눌러 바로 걸 수 있도록 119와 112를 각각 둔다.
UPDATE support_resources
SET name        = '긴급 상황 119 (구급·화재)',
    description = '아이나 가족이 다쳤거나 몸이 많이 아픈 급한 상황이면 바로 119에 연락하세요.'
WHERE id = 1;

INSERT INTO support_resources (id, name, description, phone, url, hours, category, sort_order) VALUES
(8, '긴급 상황 112 (경찰)',
 '아이나 가족의 안전이 걱정되는 급한 상황이면 바로 112에 연락하세요.',
 '112', NULL, '24시간', 'EMERGENCY', 15);
