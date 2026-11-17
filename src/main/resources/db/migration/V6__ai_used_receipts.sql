-- V6: 쓴 영수증 기억 (AIO-07, docs/ai-observation.md 4장). V1~V5는 체크섬 때문에 고치지 않는다.
-- MySQL 8과 테스트용 H2(MODE=MySQL)에서 모두 돈다.
--
-- 지금까지는 같은 영수증을 두 번 저장하지 못하게 하는 것이 ai_observations.session_jti 의 UNIQUE 뿐이었다.
-- 그래서 관찰 기록을 지우면(보호자의 삭제, AI 동의 철회) 그 행과 함께 jti 도 사라져,
-- 영수증 유효 시간(15분) 안에는 같은 영수증으로 다시 저장할 수 있었다.
-- 저장에 쓴 영수증의 jti 를 관찰 기록과 따로, 그 영수증이 더는 통하지 않는 시각까지 남긴다.
--
-- 이 표에는 아이·사용자를 가리키는 값을 두지 않는다. jti(분석 토큰이 받은 무작위 UUID)와 만료 시각뿐이다.
-- 그래서 관찰 기록을 지우거나 동의를 철회한 뒤에도 이 표에는 그 아이에 대한 것이 남지 않고,
-- 다른 표를 가리키는 외래 키도 없다. 만료된 행은 매일 04:47 작업(UsedReceipts.purgeExpired)이 지운다.
CREATE TABLE ai_used_receipts (
    jti        CHAR(36)    NOT NULL PRIMARY KEY,
    expires_at DATETIME(6) NOT NULL
);
CREATE INDEX ix_ai_used_receipts_expires ON ai_used_receipts (expires_at);
