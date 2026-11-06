# 함께자람 개발 명세서 (MVP · 0단계)

- 문서 버전: v1.0 (2026-11-06)
- 근거 문서: 「함께자람 서비스 개발 문서」(2026-11-04)
- 함께 보는 문서: [API 명세](api.md) · [지표 정의서](https://github.com/hamkke-jaram/hamkke-ai/blob/main/docs/metrics.md) · [배포 가이드](deploy.md)

이 문서는 서비스 개발 문서의 범위를 **구현 가능한 수준의 요구사항·구조·규칙**으로 옮긴 것이다. 개발 문서에서 "미정"으로 남은 항목은 이 명세에서 결정하고 근거를 남겼다(1.3절). 팀 회의에서 결정이 바뀌면 이 문서를 먼저 고친다.

---

## 1. 범위

### 1.1 목표

MVP는 아래 흐름이 **끊김 없이 실제로 동작**하는 것을 목표로 한다.

```
부모 폰 촬영(또는 앨범 선택) → 기기 안 분석 → 지표 3개(JSON) 서버 전송
→ 서버가 변화 추이 계산 → 코칭 카드 1장(출처 포함) 전달
→ 부모가 공유 항목 선택 + 초대 코드 발급
→ 교사가 코드로 연결 → 목표 입력 · 관찰 메모 · 공유된 지표 요약 조회
```

### 1.2 포함 / 제외

| 구분 | 포함 (MVP) | 제외 (이후 단계) |
|---|---|---|
| 계정 | 이메일 가입·로그인, 역할(부모/교사), 약관·개인정보 동의 기록, 토큰 갱신, 로그아웃, **회원 탈퇴(즉시 삭제)** | 소셜 로그인, 비밀번호 찾기 메일 |
| 아동 | 별칭·생년월 등록, 법정대리인 동의 기록, 수정, **삭제(=동의 철회, 관련 데이터 즉시 삭제)** | 다자녀 공동 보호자 |
| 기록 | 영상 1개 → 지표 3개 기기 내 계산, 서버 저장, 주간 요약 | 홈캠(RTSP) 자동 분석, 정밀 기록(눈맞춤 등) |
| 코칭 | 시드 카드 16장, 규칙 기반 1장 선택, 출처·근거 태그 표시, 전달 이력, "해봤어요" 표시 | RAG 선택, 단계별 커리큘럼 |
| 마음 돌봄 | 상담·기관 연결 안내 화면(정적 목록, 비로그인 접근 가능) | 주간 마음 체크(척도 이용 조건 확인 후) |
| 연계 | 항목별 공유 설정, 초대 코드, 교사 목표·관찰 메모, 공유 지표 요약, 연결 해제 | 목표→가정 5분 활동 자동 변환 |
| 결제 | 없음 | 2단계 |

### 1.3 미정 사항에 대한 이 명세의 결정

| 미정 사항 | 결정 | 근거 |
|---|---|---|
| 앱 형태 | **모바일 웹(PWA)** | 공모전 카테고리가 "모바일 웹서비스". 설치 없이 시연 가능. MediaPipe Tasks(WASM)로 브라우저 안 분석 가능 |
| 온디바이스 모델 | 자세: MediaPipe Pose Landmarker(lite, 최대 2인) / 음성: Web Audio + 자체 음성 활동·음높이 분석(모델 파일 없음) | 브라우저에서 동작, 모델 5MB 내외, 서버 전송 없음 |
| 지표 3개 | ① 함께 노는 시간 ② 부모 반응 시간(중앙값) ③ 울음·감정 폭발 횟수 | 개발 문서 가안 채택. 세부 정의는 [지표 정의서](https://github.com/hamkke-jaram/hamkke-ai/blob/main/docs/metrics.md) |
| 카드 선택 담당 | **백엔드 규칙 기반**. `CardSelector` 인터페이스로 분리해 이후 AI 서비스로 교체 가능 | AI 담당 미확정. 규칙 기반이 설명 가능하고 장애 지점이 적음 |
| 교사-아동 연결 | **부모가 발급한 초대 코드**(8자리, 72시간, 1회용) | 개발 문서 가안 채택 |
| 백엔드 스택 | Java 21, Spring Boot 3.5, Spring Data JPA, Spring Security + JWT, MySQL 8, Flyway, Docker Compose | 2026-11-04 확정 사항 |
| 프런트 스택 | React 18 + TypeScript + Vite, PWA | 모바일 웹 생태계 표준, 정적 배포 |

---

## 2. 반드시 지킬 원칙 → 구현 규칙

| 원칙 | 구현 규칙 | 검증 방법 |
|---|---|---|
| 원본 영상·음성은 기기 밖으로 나가지 않음 | 백엔드에 multipart/파일 업로드 엔드포인트 없음. `spring.servlet.multipart.enabled=false`. 요청 본문 64KB 제한. 프런트는 `File`을 `ObjectURL`/`ArrayBuffer`로만 읽고 `fetch`에 넣지 않음. 분석 후 ObjectURL 해제 | 백엔드 테스트(multipart 요청 거부), 코드 리뷰 |
| 의료기기 경계 | 진단·선별·치료 기능 없음. 분석 결과를 의료진에 보내는 기능 없음 | 표현 검수 스크립트 |
| 표현 규칙 | 코드·필드명·enum·에러 메시지·화면 문구에 금지 표현 사용 금지 (예: `riskScore` ✕ → `changeTrend` ○) | `scripts/check-expressions.mjs` (CI에서 실행) |
| 주의 문구 | 모든 부모·교사 화면 하단과 결과 화면에 고정 문구 표시 | 화면 점검 |
| 아동 개인정보 | 법정대리인 동의 없이 아동 저장 불가(서버에서 거부). 실명·주민번호 필드 없음(별칭·생년월만) | 백엔드 테스트 |
| 공유 권한 | 기본값 전부 비공개. 교사는 연결 + 공유 켜진 항목만 조회 | 백엔드 테스트 |
| 안전 기능 무료 | 상담·기관 안내 API는 인증 없이도 제공 | 백엔드 테스트 |
| 코칭 카드 | AI가 문장을 만들지 않음. DB의 카드만 전달. 출처 없는 카드는 저장 불가(NOT NULL + 검증) | 스키마 제약 |
| 위기 대응 | 상담을 대신하지 않음. 109·1577-0199 등으로 연결만 함. 비밀보장·기관 개입 여부를 단정하는 문구 금지 | 화면 점검 |
| 화면 설계 | 실시간 수치 대신 주간 요약, 칭찬 중심 문구, 비교·순위·점수 표시 없음 | 화면 점검 |

### 2.1 금지 표현 목록 (코드 상수 `ExpressionRules`)

`진단, 판별, 선별, 위험도, 치료, 증상, 예방, 경계선 지능, 정확도` 및 영문 `diagnos*, screening, risk, treatment, symptom, therapy, accuracy`.
예외(고유명사): `자살예방상담전화`, `K-DST 발달선별검사`(외부 링크 이름) — 검수 스크립트 허용 목록에 둔다.

### 2.2 주의 문구 (고정)

> 본 서비스는 의료기기가 아니며 발달 상태를 판단하지 않습니다. 걱정되는 점은 전문의와 상담하세요.

---

## 3. 시스템 구조

```
┌──────────── 부모 폰 (브라우저) ────────────┐        ┌──────── 서버 (EC2, Docker Compose) ────────┐
│ 촬영/선택 → <video> 디코딩                  │        │ nginx (정적 파일 + /api 프록시, HTTPS)       │
│   ├ Pose Landmarker(WASM) → 함께 노는 시간  │ 지표    │   └ Spring Boot API                         │
│   └ Web Audio(Worker) → 반응 시간, 울음 횟수│ JSON ─▶│       ├ 인증·동의·아동·지표·카드·공유·교사     │
│ 원본 영상·음성은 이 상자 밖으로 나가지 않음 │ ◀─ 카드 │       └ CardSelector(규칙 기반)             │
└─────────────────────────────────────────────┘        │   MySQL 8 (계정·동의·아동·지표·카드·메모)     │
                                                       └──────────────────────────────────────────────┘
                     교사 브라우저 ── 목표·메모 ──▶ API ── 공유된 항목만 ──▶ 교사 브라우저
```

| 데이터 | 위치 | 서버 저장 |
|---|---|---|
| 원본 영상·음성 | 부모 폰 메모리(분석 중에만) | 하지 않음 |
| 상호작용 지표(수치) | 폰에서 계산 → 서버 | 함 |
| 계정·동의·아동 기본 정보 | 서버 | 함(동의 후에만) |
| 코칭 카드·출처, 전달 이력 | 서버 | 함 |
| 교사 목표·관찰 메모 | 서버 | 함 |

### 3.1 저장소 구조 (GitHub 조직 `hamkke-jaram`)

| 저장소 | 담당 파트 | 내용 |
|---|---|---|
| `hamkke-api` | 백엔드 | Spring Boot API, 공통 문서(docs/), 배포 구성(deploy/) |
| `hamkke-web` | 프런트 | React + Vite 모바일 웹(부모 앱·교사 화면), nginx 이미지 |
| `hamkke-ai` | AI | 기기 내 분석 패키지 `@hamkke-jaram/analysis`, 지표 정의서, 로컬 평가 페이지 |

hamkke-web은 hamkke-ai를 태그 단위 git 의존성(`github:hamkke-jaram/hamkke-ai#vX.Y.Z`)으로 가져온다. AI 서버는 없다(분석은 부모 폰에서, 평가는 팀원 PC에서).

---

## 4. 기능 요구사항 (ID)

우선순위: **M** = MVP 필수, **S** = MVP 포함(시연 품질), **L** = 이후.

### 4.1 계정·동의 (ACC)

| ID | 요구사항 | 우선 |
|---|---|---|
| ACC-01 | 이메일·비밀번호(8~64자, 영문+숫자 포함)·이름(표시명)·역할로 가입한다. 이메일은 소문자로 정규화하고 중복을 거부한다 | M |
| ACC-02 | 가입 시 `TERMS_OF_SERVICE`, `PRIVACY_POLICY` 동의(현재 버전)가 없으면 거부한다. 동의는 유형·버전·시각과 함께 기록한다 | M |
| ACC-03 | 로그인 시 액세스 토큰(JWT, 30분)과 리프레시 토큰(불투명 난수, 14일)을 발급한다. 리프레시 토큰은 SHA-256 해시로만 저장한다 | M |
| ACC-04 | 리프레시는 회전 방식(사용한 토큰 폐기 후 새로 발급). 폐기된 토큰 재사용 시 해당 사용자의 모든 리프레시 토큰을 폐기한다 | M |
| ACC-05 | 로그인 실패 5회/15분 시 해당 이메일의 로그인을 15분 제한한다(IP 기준 분당 요청 제한도 둔다) | S |
| ACC-06 | 회원 탈퇴 시 비밀번호를 확인하고, 부모는 본인 아동과 그 아동의 모든 데이터, 교사는 본인이 쓴 목표·메모·연결을 즉시 삭제한다 | M |
| ACC-07 | 내 동의 이력을 조회할 수 있다 | S |

### 4.2 아동 (CHD)

| ID | 요구사항 | 우선 |
|---|---|---|
| CHD-01 | 부모만 아동을 등록한다. 입력은 별칭(1~20자)·생년월(YYYY-MM, 0~6세 범위) 뿐이다 | M |
| CHD-02 | 등록 요청에 법정대리인 동의(`CHILD_DATA_GUARDIAN`, 현재 버전, agreed=true)가 없으면 저장하지 않는다(422 `GUARDIAN_CONSENT_REQUIRED`) | M |
| CHD-03 | 아동 등록과 동시에 공유 설정(전부 꺼짐)을 만든다 | M |
| CHD-04 | 아동 삭제 = 법정대리인 동의 철회. 지표·카드 이력·공유 설정·초대 코드·교사 연결·목표·메모를 함께 삭제하고 동의 기록에 철회 시각을 남긴다 | M |
| CHD-05 | 부모 1명당 아동 최대 5명 | S |

### 4.3 상호작용 기록 (REC)

| ID | 요구사항 | 우선 |
|---|---|---|
| REC-01 | 부모는 본인 아동에 대해 지표 JSON을 저장한다. 파일·바이너리는 받지 않는다 | M |
| REC-02 | 값 검증: 분석 길이 10~1800초, 함께 노는 시간 0~분석 길이, 반응 시간 0~10초 또는 null, 아이 발화 수 ≥ 반응된 발화 수, 울음 횟수 0~200, `specVersion`은 지원 목록(`metrics-v1`)만 | M |
| REC-03 | 같은 `clientRecordId`(UUID)로 다시 보내면 새로 만들지 않고 기존 결과를 돌려준다(재전송 안전) | M |
| REC-04 | 저장 직후 변화 추이(`changeTrend`)를 계산하고 코칭 카드를 선택해 응답에 포함한다 | M |
| REC-05 | 주간 요약: 최근 N주(기본 8, 최대 26)를 ISO 주 단위로 묶어 지표 평균과 기록 수를 준다 | M |
| REC-06 | 부모는 본인이 저장한 기록을 삭제할 수 있다 | S |

### 4.4 코칭 카드 (CRD)

| ID | 요구사항 | 우선 |
|---|---|---|
| CRD-01 | 카드는 제목·본문·적용 상황·관련 지표·근거 태그·출처(제목, 링크 선택)·작성자·검수자(선택)를 가진다. 출처 제목은 필수 | M |
| CRD-02 | 시드 카드는 마이그레이션으로 넣는다(16장) | M |
| CRD-03 | 카드 선택은 `CardSelector` 인터페이스로 분리하고, 기본 구현은 규칙 기반(5장)이다 | M |
| CRD-04 | 전체 추이가 `NEEDS_ATTENTION`이면 카드 대신 상담·기관 안내를 응답한다 | M |
| CRD-05 | 카드 전달 이력을 저장하고, 같은 아동에게 최근 전달한 카드는 후보 중 가장 뒤로 미룬다 | M |
| CRD-06 | 부모는 전달받은 카드에 "해봤어요"를 표시할 수 있다 | S |
| CRD-07 | 검수자가 없는 카드는 화면에 "전문가 검수 진행 중"으로 표시한다 | M |

### 4.5 공유·연계 (SHR / TCH)

| ID | 요구사항 | 우선 |
|---|---|---|
| SHR-01 | 공유 항목: 함께 노는 시간, 반응 시간, 울음 횟수, 받은 코칭 카드. 기본값은 모두 꺼짐 | M |
| SHR-02 | 부모는 초대 코드(혼동 문자 제외 8자리, 72시간, 1회용)를 발급한다. 아동당 유효 코드 최대 3개 | M |
| SHR-03 | 부모는 연결된 교사 목록을 보고 연결을 해제할 수 있다 | M |
| TCH-01 | 교사는 초대 코드로 아동과 연결한다. 만료·사용됨·없는 코드는 같은 에러로 응답한다(코드 추측 방지) | M |
| TCH-02 | 교사는 연결된 아동 목록(별칭·만 나이 개월)을 본다 | M |
| TCH-03 | 교사는 아동 목표를 만들고 고치고(상태: 진행 중/이뤘어요/보관) 지운다. 본인이 쓴 목표만 수정·삭제 | M |
| TCH-04 | 교사는 관찰 메모(1~1000자, 관찰일)를 남기고 본인 메모를 지운다 | M |
| TCH-05 | 교사는 공유가 켜진 지표만 주간 요약으로 본다. 꺼진 항목은 응답에서 아예 빠진다(null도 아님) | M |
| TCH-06 | 부모는 본인 아동의 목표와 관찰 메모를 읽을 수 있다 | M |
| TCH-07 | 교사 연결이 해제되면 그 교사는 목표·메모·요약 모두 접근할 수 없다(작성한 기록은 남는다) | M |

### 4.6 안내 (SUP)

| ID | 요구사항 | 우선 |
|---|---|---|
| SUP-01 | 상담·기관 연결 안내 목록(이름, 설명, 전화, 링크, 운영 시간)을 인증 없이 제공한다 | M |
| SUP-02 | 정책 버전·주의 문구를 인증 없이 제공한다 | M |

---

## 5. 데이터 모델

모든 시각은 UTC `DATETIME(6)` 저장, API는 ISO-8601(`Z`)로 주고받는다.

| 테이블 | 주요 컬럼 | 비고 |
|---|---|---|
| `users` | id, email(UQ), password_hash, display_name, role(PARENT/TEACHER), created_at | |
| `consents` | id, user_id, child_id(NULL 가능), type, version, agreed_at, revoked_at | 아동 삭제 후에도 동의·철회 이력 보존(child_id는 NULL로) |
| `refresh_tokens` | id, user_id, token_hash(UQ), expires_at, revoked_at, created_at | |
| `children` | id, parent_id, nickname, birth_year_month(CHAR 7), created_at | 실명·주민번호 없음 |
| `share_settings` | child_id(PK), joint_play, response_latency, distress, coaching_cards, updated_at | 기본 false |
| `interaction_records` | id, child_id, client_record_id(UQ with child), recorded_at, analyzed_seconds, joint_play_seconds, response_latency_sec, child_bid_count, responded_bid_count, distress_count, model_version, spec_version, change_trend, created_at | |
| `coaching_cards` | id, code(UQ), title, body, situation, metric_focus, evidence_tag, source_title, source_url, author, reviewer, active | |
| `card_deliveries` | id, child_id, card_id, record_id, delivered_at, tried_at | |
| `invite_codes` | id, child_id, code(UQ), created_at, expires_at, used_at, used_by | |
| `teacher_links` | id, teacher_id, child_id, linked_at (UQ teacher+child) | 해제 시 행 삭제 |
| `child_goals` | id, child_id, teacher_id, title, description, status, created_at, updated_at | |
| `observation_notes` | id, child_id, teacher_id, content, observed_on, created_at | |
| `support_resources` | id, name, description, phone, url, hours, category, sort_order | |

열거형 값(표현 규칙 준수):

- `ChangeTrend`: `FIRST_RECORD`, `GROWING`, `STEADY`, `NEEDS_ATTENTION`
- `CardSituation`: `FIRST_RECORD`, `GROWING`, `STEADY`, `ANY`
- `MetricFocus`: `JOINT_PLAY`, `RESPONSE`, `CALM`, `GENERAL`
- `GoalStatus`: `IN_PROGRESS`, `ACHIEVED`, `ARCHIVED`

---

## 6. 변화 추이와 카드 선택 규칙 (CardSelector v1)

### 6.1 지표별 정규화

| 지표 | 비교 값 | 좋아지는 방향 | 변화 기준(임계) |
|---|---|---|---|
| 함께 노는 시간 | `joint_play_seconds / analyzed_seconds` (비율) | ↑ | ±0.05 (5%p) |
| 반응 시간 | `response_latency_sec` (null이면 비교 제외) | ↓ | ±0.3초 |
| 울음·감정 폭발 | 10분당 횟수 = `distress_count / analyzed_seconds × 600` | ↓ | ±1.0회 |

### 6.2 추이 계산

1. 기준값 = 이번 기록 **이전** 최근 3개 기록의 평균(지표별로 값이 있는 것만).
2. 이전 기록이 없으면 `FIRST_RECORD`.
3. 지표별 결과: 임계보다 좋아지면 `UP`, 나빠지면 `DOWN`, 그 외 `FLAT`, 비교 불가면 `NA`.
4. 전체 추이:
   - `DOWN`이 2개 이상이고 이전 기록이 2개 이상이면 `NEEDS_ATTENTION` (기록 1~2개만으로 성급히 안내하지 않음)
   - `UP`이 1개 이상이고 `DOWN`이 없으면 `GROWING`
   - 그 외 `STEADY`

### 6.3 카드 선택

1. `NEEDS_ATTENTION` → 카드 없음. 응답 `coaching.guidance`에 정상화 메시지 + 상담·기관 목록.
2. 관련 지표 결정:
   - `GROWING`: 가장 크게 좋아진 지표(다음 목표 카드)
   - `STEADY`: `DOWN` 지표가 있으면 그것, 없으면 기준 대비 개선 여지가 가장 큰 지표(함께 노는 비율이 0.5 미만이면 `JOINT_PLAY` 우선)
   - `FIRST_RECORD`: `GENERAL`
3. 후보 = `active` 카드 중 (situation = 추이 또는 `ANY`) AND (metric_focus = 관련 지표 또는 `GENERAL`). 관련 지표가 정확히 맞는 카드를 먼저.
4. 이 아동이 최근 받은 카드 3장은 다른 후보가 있으면 제외한다. 남은 후보를 (관련 지표 정확히 일치 → 상황 정확히 일치 → **전달한 적 없거나 가장 오래전에 전달** → id) 순으로 정렬해 1장.
5. 후보가 없으면 `GENERAL`·`ANY` 카드로 대체. 그래도 없으면 카드 없이 응답(서비스는 계속 동작).

### 6.4 인터페이스 (AI 교체 대비)

```java
public interface CardSelector {
    Optional<Long> select(CardSelectionContext ctx); // 카드 ID만 돌려준다. 문장을 만들지 않는다.
}
```

외부 AI 선택 서비스를 붙일 때도 입력은 지표 요약·추이, 출력은 카드 ID뿐이며, 실패·지연(1초 초과) 시 규칙 기반으로 대체한다.

---

## 7. 보안·개인정보

| 항목 | 규칙 |
|---|---|
| 전송 | 운영은 HTTPS만(nginx TLS 종단, HSTS). 카메라·`getUserMedia` 사용에도 HTTPS 필요 |
| 인증 | `Authorization: Bearer <JWT>`. HS256, 서명 키 32바이트 이상, 환경변수로만 주입(기본값으로 기동 불가) |
| 비밀번호 | BCrypt(cost 10) |
| 권한 | 역할(`PARENT`/`TEACHER`) + 리소스 소유 검사. 남의 아동은 404로 응답(존재 여부 노출 방지) |
| 입력 | Bean Validation, 본문 64KB 제한, multipart 비활성 |
| 헤더 | CSP(self + 필요 최소), X-Content-Type-Options, Referrer-Policy, Permissions-Policy(camera=self, microphone=self) |
| 로그 | 이메일·토큰·메모 본문을 로그에 남기지 않음. 요청 ID만 |
| 삭제 | 탈퇴·아동 삭제는 즉시 물리 삭제(동의·철회 이력만 법적 근거용으로 보존) |
| CORS | 운영은 같은 출처(nginx 프록시)라 비활성. 개발은 허용 출처 목록 |

---

## 8. 비기능 요구사항

| 항목 | 목표 |
|---|---|
| 분석 시간 | 1분 영상을 중급 안드로이드폰에서 60초 이내(자세 2fps 샘플링) |
| 지원 환경 | Android Chrome 최신 2개, iOS Safari 16.4+ |
| API 응답 | p95 300ms 이하(지표 저장+카드 선택 포함) |
| 가용성 | 단일 EC2, 컨테이너 자동 재시작, 헬스체크 `/actuator/health` |
| 백업 | MySQL 일 1회 덤프(deploy 가이드) |
| 접근성 | 버튼 최소 44px, 대비 4.5:1, 한국어 |

---

## 9. 에러 형식

```json
{ "code": "GUARDIAN_CONSENT_REQUIRED", "message": "법정대리인 동의가 필요해요.", "fieldErrors": [] }
```

| HTTP | code 예 |
|---|---|
| 400 | `VALIDATION_FAILED`, `UNSUPPORTED_SPEC_VERSION`, `MALFORMED_REQUEST` |
| 401 | `UNAUTHORIZED`, `INVALID_CREDENTIALS`, `INVALID_REFRESH_TOKEN` |
| 403 | `FORBIDDEN_ROLE` |
| 404 | `NOT_FOUND` |
| 409 | `EMAIL_TAKEN`, `ALREADY_LINKED` |
| 413 | `PAYLOAD_TOO_LARGE` |
| 415 | `UNSUPPORTED_MEDIA_TYPE` (파일 업로드 시도 포함) |
| 422 | `CONSENT_REQUIRED`, `GUARDIAN_CONSENT_REQUIRED`, `INVITE_CODE_INVALID`, `CHILD_LIMIT_REACHED`, `INVITE_LIMIT_REACHED` |
| 429 | `TOO_MANY_ATTEMPTS` |

---

## 10. 테스트 전략

- 백엔드: 서비스 규칙 단위 테스트(추이·카드 선택), MockMvc 통합 테스트(H2 MySQL 모드 + Flyway)로 전체 흐름·권한·동의 거부·파일 업로드 거부 검증.
- 프런트: 분석 로직 순수 함수 단위 테스트(Vitest, 합성 신호), 타입 검사, 빌드.
- 공통: 표현 검수 스크립트, 실제 브라우저 E2E(데모 계정으로 전체 흐름).
- 정확도 평가: 팀원 연출 영상으로 사람 수기 측정값과 비교(별도 기록, 앱·발표에는 "정확도 ○○%"를 표시하지 않는다).

## 11. 출시 전 확인 목록 (코드 밖)

- [ ] 약관·개인정보 처리방침 본문 법률 검토 (현재 버전 `2026-11-v1`은 초안)
- [ ] 상호작용 지표의 민감정보 해당 여부 법률 자문
- [ ] 코칭 카드 전문가 검수(검수자 필드 채우기)
- [ ] 상담·기관 안내의 전화번호·링크 최신 여부 확인
- [ ] 운영 도메인·TLS 인증서, JWT 서명 키, DB 비밀번호 발급
