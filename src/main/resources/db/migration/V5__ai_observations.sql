-- V5: AI 관찰 기록 (docs/ai-observation.md). V1~V4는 체크섬 때문에 고치지 않는다.
-- MySQL 8과 테스트용 H2(MODE=MySQL)에서 모두 돈다.
-- 사진을 담는 컬럼은 없다. 서버에는 AI 서버가 서명한 글자(요약·태그·시각별 메모)만 들어온다.

-- (a) 공유 설정 두 항목. 기본값은 꺼짐.
--     ai_observations     : 가정에서 만든 AI 관찰 기록을 연결된 선생님에게 보여 준다.
--     institution_capture : 연결된 선생님이 기관에서 촬영·분석할 수 있다(AI 동의가 함께 있어야 한다).
ALTER TABLE share_settings ADD COLUMN ai_observations BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE share_settings ADD COLUMN institution_capture BOOLEAN NOT NULL DEFAULT FALSE;

-- (b) AI 관찰 기록.
--     태그 목록과 시각별 메모는 JSON 글자로 담는다(두 DB에서 같은 식으로 다루려고 JSON 컬럼 타입을 쓰지 않는다).
--     session_jti는 분석 토큰의 jti다. 같은 영수증으로 두 번 저장할 수 없다.
--     created_by는 만든 사람(가정 기록은 보호자, 기관 기록은 선생님). 계정이 지워져도 아이의 기록은 남는다.
CREATE TABLE ai_observations (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    child_id        BIGINT        NOT NULL,
    created_by      BIGINT        NULL,
    source          VARCHAR(20)   NOT NULL,
    recorded_at     DATETIME(6)   NOT NULL,
    summary         VARCHAR(600)  NULL,
    child_states    VARCHAR(200)  NOT NULL,
    adult_responses VARCHAR(200)  NOT NULL,
    moments         VARCHAR(4000) NOT NULL,
    people_count    INT           NOT NULL,
    model_version   VARCHAR(100)  NOT NULL,
    prompt_version  VARCHAR(40)   NOT NULL,
    frame_count     INT           NOT NULL,
    session_jti     CHAR(36)      NOT NULL,
    created_at      DATETIME(6)   NOT NULL,
    CONSTRAINT uq_ai_observations_jti UNIQUE (session_jti),
    CONSTRAINT fk_ai_observations_child FOREIGN KEY (child_id) REFERENCES children (id) ON DELETE CASCADE,
    CONSTRAINT fk_ai_observations_user FOREIGN KEY (created_by) REFERENCES users (id) ON DELETE SET NULL
);
CREATE INDEX ix_ai_observations_child_time ON ai_observations (child_id, recorded_at);
CREATE INDEX ix_ai_observations_user ON ai_observations (created_by);

-- (c) 카드 전달 이력에 AI 관찰 기록 연결. record_id(지표 기록)는 V1부터 NULL을 허용한다.
--     관찰 기록이 지워지면(동의 철회 포함) 그 기록으로 전달된 카드 이력도 함께 지운다.
ALTER TABLE card_deliveries ADD COLUMN ai_observation_id BIGINT NULL;
ALTER TABLE card_deliveries ADD CONSTRAINT fk_deliveries_ai_observation
    FOREIGN KEY (ai_observation_id) REFERENCES ai_observations (id) ON DELETE CASCADE;
CREATE INDEX ix_deliveries_ai_observation ON card_deliveries (ai_observation_id);

-- (d) '힘든 순간 함께하기'(HARD_MOMENT) 카드 초안 6장. 팀이 직접 쓴 문장이며 공공자료 원문을 옮기지 않았다.
--     근거 태그는 V2와 같이 NCAEP 2020의 근거기반 실제 분류를 "인용"만 한다. 검수자(reviewer)는 전문가 검수 후 채운다.
--     AI는 이 카드를 고르기만 하고 문장을 만들지 않는다.
INSERT INTO coaching_cards (id, code, title, body, situation, metric_focus, evidence_tag, source_title, source_url, author, reviewer, active) VALUES
(17, 'HM-A-01', '곁에 머물며 마음에 이름 붙여 주기',
 '아이가 속상해하는 순간에도 곁에 함께 계셨어요. 그것만으로도 아이에게는 큰 힘이 돼요. 다음에 아이가 울거나 힘들어하면 아이 눈높이로 몸을 낮추고 "속상했구나", "더 놀고 싶었구나"처럼 아이 마음을 짧은 말로 대신 말해 주세요. 달래려고 서두르지 않아도 괜찮아요. 곁에서 들려주는 낮고 차분한 목소리가 아이에게 닿아요.',
 'ANY', 'HARD_MOMENT', '근거기반 실제: 부모 실행 중재(Parent-Implemented Intervention)의 원리 참고',
 'NCAEP (2020). Evidence-Based Practices for Children, Youth, and Young Adults with Autism', 'https://ncaep.fpg.unc.edu/',
 '함께자람 콘텐츠팀(초안)', NULL, TRUE),

(18, 'HM-A-02', '바꾸기 전에 두 가지 중에 고르게 해 주기',
 '아이의 하루를 곁에서 살피고 기록까지 남기셨어요. 잘하고 계세요. 다음에는 놀이를 마치거나 자리를 옮기기 조금 전에 아이가 고를 수 있는 두 가지를 보여 주세요. "걸어서 갈까, 안겨서 갈까?", "파란 컵으로 마실까, 노란 컵으로 마실까?"처럼요. 스스로 고른 것이 하나 있으면 아이는 다음 일로 넘어가기가 한결 수월해요.',
 'ANY', 'HARD_MOMENT', '근거기반 실제: 선행사건 기반 중재(Antecedent-Based Intervention)의 원리 참고',
 'NCAEP (2020). Evidence-Based Practices for Children, Youth, and Young Adults with Autism', 'https://ncaep.fpg.unc.edu/',
 '함께자람 콘텐츠팀(초안)', NULL, TRUE),

(19, 'HM-A-03', '다음에 할 일을 그림으로 보여 주기',
 '오늘도 아이와 함께하는 시간을 내 주셨어요. 그 시간이 아이에게는 든든한 바탕이에요. 다음에 할 일을 알려 줄 때 그림이나 사진을 더해 보세요. "놀이 → 손 씻기 → 간식"처럼 두세 장을 순서대로 붙여 두고, 하나가 끝나면 함께 손가락으로 다음 그림을 짚어 주세요. 눈으로 볼 수 있으면 아이가 다음 일을 받아들이기 쉬워져요.',
 'ANY', 'HARD_MOMENT', '근거기반 실제: 시각적 지원(Visual Supports)의 원리 참고',
 'AFIRM Modules, FPG Child Development Institute', 'https://afirm.fpg.unc.edu/',
 '함께자람 콘텐츠팀(초안)', NULL, TRUE),

(20, 'HM-A-04', '차분한 순간을 먼저 알아봐 주기',
 '힘든 순간에도 아이 곁을 지켜 주셨어요. 이번에는 힘든 순간이 아닌 때를 눈여겨봐 주세요. 아이가 차분히 놀고 있거나, 기다리고 있거나, 말이나 손짓으로 알려 줄 때 바로 다가가 "기다려 줬네, 고마워", "말로 알려 줘서 알았어"처럼 본 그대로 말해 주세요. 알아봐 주는 순간이 쌓일수록 아이는 그 모습을 더 자주 보여 줘요.',
 'ANY', 'HARD_MOMENT', '근거기반 실제: 강화(Reinforcement)의 원리 참고',
 'NCAEP (2020). Evidence-Based Practices for Children, Youth, and Young Adults with Autism', 'https://ncaep.fpg.unc.edu/',
 '함께자람 콘텐츠팀(초안)', NULL, TRUE),

(21, 'HM-A-05', '짧게 알려 주고, 할 수 있는 것을 보여 주기',
 '안 되는 것을 알려 주는 일은 누구에게나 쉽지 않아요. 그래도 아이 곁에서 함께해 주고 계세요. 다음에는 낮은 목소리로 한 문장만 말해 주세요. 그리고 바로 이어서 해도 되는 것을 알려 주세요. "던지는 건 안 돼. 공은 바구니에 쏙 넣자"처럼요. 긴 설명보다 대신 할 수 있는 행동 하나를 직접 보여 주는 편이 아이에게 더 잘 전해져요.',
 'ANY', 'HARD_MOMENT', '근거기반 실제: 차별 강화(Differential Reinforcement)의 원리 참고',
 'NCAEP (2020). Evidence-Based Practices for Children, Youth, and Young Adults with Autism', 'https://ncaep.fpg.unc.edu/',
 '함께자람 콘텐츠팀(초안)', NULL, TRUE),

(22, 'HM-A-06', '대신 쓸 수 있는 말과 손짓 보여 주기',
 '아이가 싫다고 표현하는 순간에도 그 마음을 알아주려고 곁에 계셨어요. 잘하고 계세요. 아이가 다음에 쓸 수 있는 말이나 손짓을 부모님이 먼저 보여 주세요. 고개를 저으며 "싫어", 손바닥을 내밀며 "그만", 손을 모으며 "도와줘"처럼 짧고 쉬운 것이면 돼요. 아이가 비슷하게라도 따라 하면 바로 들어주세요. 말과 손짓이 통한다는 걸 알면 아이는 그 방법을 더 자주 써요.',
 'ANY', 'HARD_MOMENT', '근거기반 실제: 기능적 의사소통 훈련(Functional Communication Training)의 원리 참고',
 'AFIRM Modules, FPG Child Development Institute', 'https://afirm.fpg.unc.edu/',
 '함께자람 콘텐츠팀(초안)', NULL, TRUE);
