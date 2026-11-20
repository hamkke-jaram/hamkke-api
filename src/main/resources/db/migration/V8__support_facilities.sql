-- 지역 기관 찾기(SUP-03·04). 공공데이터를 정규화한 CSV로만 채운다(손으로 넣지 않는다, docs/deploy.md).
-- 아이·사용자 데이터와 연결하지 않는다: 다른 표를 가리키는 열이 없다.
-- 기관 평가·순위·리뷰를 담는 열은 두지 않는다.
-- status: PUBLIC_DATA(공공데이터 그대로), CONFIRMED(우리가 확인한 정보).
-- tags: 짧은 말 여러 개를 ';'로 이은 값(예: 유치원 과정;지적장애).
CREATE TABLE support_facilities (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    category    VARCHAR(40)  NOT NULL,
    name        VARCHAR(100) NOT NULL,
    sido        VARCHAR(20)  NOT NULL,
    sigungu     VARCHAR(30)  NULL,
    address     VARCHAR(200) NOT NULL,
    phone       VARCHAR(30)  NULL,
    homepage    VARCHAR(300) NULL,
    operator    VARCHAR(20)  NULL,
    tags        VARCHAR(300) NULL,
    source_name VARCHAR(100) NOT NULL,
    source_date DATE         NOT NULL,
    status      VARCHAR(20)  NOT NULL,
    created_at  DATETIME(6)  NOT NULL,
    updated_at  DATETIME(6)  NOT NULL,
    CONSTRAINT uq_facilities_key UNIQUE (category, name, address),
    CONSTRAINT ck_facilities_category CHECK (category IN ('SPECIAL_SCHOOL', 'SPECIAL_ED_SUPPORT_CENTER',
        'CHILDCARE_SUPPORT_CENTER', 'MENTAL_HEALTH_CENTER', 'DISABILITY_WELFARE_CENTER')),
    CONSTRAINT ck_facilities_operator CHECK (operator IS NULL OR operator IN ('PUBLIC', 'PRIVATE', 'NONPROFIT')),
    CONSTRAINT ck_facilities_status CHECK (status IN ('PUBLIC_DATA', 'CONFIRMED'))
);
CREATE INDEX ix_facilities_sido_category ON support_facilities (sido, category, name);
CREATE INDEX ix_facilities_name ON support_facilities (name);
