# 함께자람 개발 명세서 (MVP · 0단계)

- 문서 버전: v1.2 (2026-11-13) — 'AI 관찰 기록'(VL 분석) 반영: 원칙 표(2장), 구조(3장), 데이터 모델(5장), 카드 고르기(6.5), 보안(7장), 에러 코드(9장)
- 근거 문서: 「함께자람 서비스 개발 문서」(2026-11-04)
- 함께 보는 문서: [기능 요구사항(ID)](requirements.md) · [API 명세](api.md) · [AI 관찰 기록 명세](ai-observation.md) · [지표 정의서](https://github.com/hamkke-jaram/hamkke-ai/blob/main/docs/metrics.md) · [배포 가이드](deploy.md)

이 문서는 서비스 개발 문서의 범위를 **구현 가능한 수준의 요구사항·구조·규칙**으로 옮긴 것이다. 개발 문서에서 "미정"으로 남은 항목은 이 명세에서 결정하고 근거를 남겼다(1.3절). 팀 회의에서 결정이 바뀌면 이 문서를 먼저 고친다.

### 결정 기록

- **2026-11-13** — 'AI 관찰 기록'(VL 분석)을 넣는다. 보호자가 동의한 아이에 한해 장면 사진 최대 6장을 AI 서버로 보내고, VL 모델이 본 것을 적은 글자만 저장한다. 영상 원본과 소리는 여전히 기기 밖으로 나가지 않고, hamkke-api는 여전히 파일·이미지를 받지 않는다. 기관 선생님은 보호자가 켠 경우에만 기관에서 촬영할 수 있다. 세 저장소가 지킬 약속은 **[ai-observation.md](ai-observation.md)** 에 있고, 이 문서와 다른 부분은 그 문서가 우선한다.

---

## 1. 범위

### 1.1 목표

MVP는 아래 흐름이 **끊김 없이 실제로 동작**하는 것을 목표로 한다.

```
부모 폰 촬영(또는 앨범 선택) → 기기 안 분석 → 지표 3개(JSON) 서버 전송
→ 서버가 변화 추이 계산 → 코칭 카드 1장(출처 포함) 전달
→ 부모가 공유 항목 선택 + 초대 코드 발급
→ 교사가 코드로 연결 → 목표 입력 · 관찰 메모 · 공유된 지표 요약 조회

(AI 관찰 기록 동의가 있는 아이) 같은 영상이나 실시간 카메라에서 장면 사진 최대 6장
→ AI 서버(VL 모델)가 본 것을 적음 → 서명된 영수증 → 서버가 글자만 저장 → (가정 기록이면) 카드 1장
```

### 1.2 포함 / 제외

| 구분 | 포함 (MVP) | 제외 (이후 단계) |
|---|---|---|
| 계정 | 이메일 가입·로그인, 역할(부모/교사), 약관·개인정보 동의 기록, 토큰 갱신, 로그아웃, **회원 탈퇴(즉시 삭제)** | 소셜 로그인, 비밀번호 찾기 메일 |
| 아동 | 별칭·생년월 등록, 법정대리인 동의 기록, 수정, **삭제(=동의 철회, 관련 데이터 즉시 삭제)** | 다자녀 공동 보호자 |
| 기록 | 영상 1개 → 지표 3개 기기 내 계산, 서버 저장, 주간 요약 | 홈캠(RTSP) 자동 분석, 정밀 기록(눈맞춤 등) |
| AI 관찰 기록 | 아이별 동의, 장면 사진 최대 6장 → VL 모델이 쓴 관찰 기록(요약·태그·시각별 메모), 가정·기관 기록, 동의 철회 시 삭제 ([ai-observation.md](ai-observation.md)) | 실시간 감지·알림, VL이 쓰는 조언 문장 |
| 코칭 | 시드 카드 22장(지표용 16장 + '힘든 순간 함께하기' 6장), 규칙 기반 1장 선택, 출처·근거 태그 표시, 전달 이력, "해봤어요" 표시 | RAG 선택, 단계별 커리큘럼 |
| 마음 돌봄 | 상담·기관 연결 안내 화면(정적 목록, 비로그인 접근 가능) | 주간 마음 체크(척도 이용 조건 확인 후) |
| 연계 | 항목별 공유 설정, 초대 코드, 교사 목표·관찰 메모, 공유 지표 요약, 연결 해제, 보호자가 켠 경우의 기관 촬영 | 목표→가정 5분 활동 자동 변환 |
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
| 영상 원본·소리는 기기 밖으로 나가지 않음 | 백엔드에 multipart/파일 업로드 엔드포인트 없음. `spring.servlet.multipart.enabled=false`. 요청 본문 64KB 제한(이미지·미디어 본문 415, 초과 413 — AI 관찰 기록이 들어와도 그대로). 프런트는 `File`을 `ObjectURL`/`ArrayBuffer`로만 읽고 `fetch`에 넣지 않음. 분석 후 ObjectURL 해제. **예외는 하나**: 보호자가 'AI 관찰 기록'에 동의한 아이의 장면 사진 최대 6장(소리 없음)이 AI 서버로 간다(아래 줄) | 백엔드 테스트(multipart·이미지 요청 거부), 코드 리뷰 |
| 장면 사진은 동의한 아이에 한해 AI 서버로만 | 아이별 동의 `CHILD_IMAGE_AI_ANALYSIS`가 없으면 분석 토큰을 발급하지 않는다(= 사진이 한 장도 나가지 않는다). AI 서버는 사진을 메모리에서만 다루고 응답 뒤 버린다(디스크·로그에 남기지 않음). hamkke-api는 사진을 받지 않고 AI 서버가 서명한 영수증(글자)만 받는다. 동의 철회 = 그 아이의 AI 관찰 기록 전부 삭제 ([ai-observation.md](ai-observation.md) 2장) | 백엔드 테스트(동의 없는 토큰 발급 거부, 영수증 검증, 철회 시 삭제) |
| 의료기기 경계 | 진단·선별·치료 기능 없음. 분석 결과를 의료진에 보내는 기능 없음 | 표현 검수 스크립트 |
| 표현 규칙 | 코드·필드명·enum·에러 메시지·화면 문구에 금지 표현 사용 금지 (예: `riskScore` ✕ → `changeTrend` ○) | 저장소마다 있는 `scripts/check-expressions.mjs` (CI에서 실행) |
| 주의 문구 | 모든 부모·교사 화면 하단과 결과 화면에 고정 문구 표시 | 화면 점검 |
| 아동 개인정보 | 법정대리인 동의 없이 아동 저장 불가(서버에서 거부). 실명·주민번호 필드 없음(별칭·생년월만) | 백엔드 테스트 |
| 공유 권한 | 기본값 전부 비공개. 교사는 연결 + 공유 켜진 항목만 조회. 기관 촬영은 보호자가 `institutionCapture`를 켜고 AI 동의가 있을 때만(없으면 404) | 백엔드 테스트 |
| 안전 기능 무료 | 상담·기관 안내 API는 인증 없이도 제공 | 백엔드 테스트 |
| 코칭 카드 | AI가 조언 문장을 만들지 않음(VL 모델은 본 것을 적기만 하고, 평가·원인 추정·조언처럼 읽히는 문장은 저장 전에 버린다). DB의 카드만 전달. 출처 없는 카드는 저장 불가(NOT NULL + 빈 문자열·스페이스만 있는 출처 제목을 막는 CHECK, V4. `TRIM`은 스페이스만 지우므로 탭·줄바꿈만 있는 제목은 이 CHECK로 걸러지지 않음 → 카드 등록 검수에서 확인) | 스키마 제약, 백엔드 테스트 |
| 위기 대응 | 상담을 대신하지 않음. 109·1577-0199 등으로 연결만 함. 비밀보장·기관 개입 여부를 단정하는 문구 금지 | 화면 점검 |
| 화면 설계 | 실시간 수치 대신 주간 요약, 칭찬 중심 문구, 비교·순위·점수 표시 없음 | 화면 점검 |

### 2.1 금지 표현 목록 (검수 스크립트 `scripts/check-expressions.mjs`)

금지 목록은 저장소마다 있는 검수 스크립트에 있다: `hamkke-api/scripts/check-expressions.mjs`, `hamkke-web/scripts/check-expressions.mjs`, `hamkke-ai/scripts/check-expressions.mjs`. 세 파일의 목록은 같게 유지한다.

예외로 **문장 검사 필터 한 파일**만 같은 목록을 코드로 가진다: VL 모델이 쓴 문장에 금지 표현이 있으면 그 문장을 버려야 하기 때문이다. hamkke-api에서는 `aiobs/ObservationSentenceFilter.java`이며, 검수 스크립트는 이 파일 하나만 검사에서 뺀다(스크립트에 까닭을 적어 두었다). 두 목록이 같은지는 테스트(`ObservationSentenceFilterTest`)가 확인한다.

`진단, 판별, 선별, 위험도, 위험 예측, 치료, 증상, 예방, 경계선 지능, 정확도, WHO 프로그램, 외부 프로그램 상표명(Hanen·ESDM·PACT·JASPER)` 및 영문 `diagnos*, screening, risk, treatment, symptom, therap*, accuracy, predict, prevent, borderline`.
예외(고유명사): `자살예방상담전화`, `K-DST 발달선별검사`(외부 링크 이름) — 검수 스크립트 허용 목록에 둔다. 바꿀 수 없는 웹 API 이름(예: 프런트의 `preventDefault()`)만 해당 저장소 허용 목록에 좁게 둔다.

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

AI 관찰 기록(동의한 아이만):
  브라우저 ─ ① 분석 토큰 요청 ─▶ API                (동의 확인, HS256 토큰 10분)
  브라우저 ─ ② 장면 사진 ≤6장 + 토큰 ─▶ nginx /ai/ ─▶ AI 서버(hamkke-ai server/) ─▶ VL 모델(Ollama)
           ◀─ 관찰 기록 + 영수증(JWS) ─              (사진은 메모리에서만, 응답 뒤 버림)
  브라우저 ─ ③ 영수증 ─▶ API                         (서명·대상·만료·아이·사용자·jti 확인 후 글자만 저장)
```

| 데이터 | 위치 | 서버 저장 |
|---|---|---|
| 원본 영상·음성 | 부모 폰 메모리(분석 중에만) | 하지 않음 |
| 장면 사진(최대 6장, 소리 없음) | 기기 → AI 서버 메모리(분석 중에만). 동의한 아이만 | 하지 않음(AI 서버도 hamkke-api도) |
| AI 관찰 기록(요약·태그·시각별 메모) | AI 서버가 만들어 서명 → 서버 | 함(동의가 있는 동안. 철회하면 삭제) |
| 상호작용 지표(수치) | 폰에서 계산 → 서버 | 함 |
| 계정·동의·아동 기본 정보 | 서버 | 함(동의 후에만) |
| 코칭 카드·출처, 전달 이력 | 서버 | 함 |
| 교사 목표·관찰 메모 | 서버 | 함 |

### 3.1 저장소 구조 (GitHub 조직 `hamkke-jaram`)

| 저장소 | 담당 파트 | 내용 |
|---|---|---|
| `hamkke-api` | 백엔드 | Spring Boot API, 공통 문서(docs/), 배포 구성(deploy/) |
| `hamkke-web` | 프런트 | React + Vite 모바일 웹(부모 앱·교사 화면), nginx 이미지 |
| `hamkke-ai` | AI | 기기 내 분석 패키지 `@hamkke-jaram/analysis`, 지표 정의서, 로컬 평가 페이지, **AI 관찰 기록 서버**(`server/`) |

hamkke-web은 hamkke-ai를 태그 단위 git 의존성(`github:hamkke-jaram/hamkke-ai#vX.Y.Z`)으로 가져온다. 지표 3개 분석은 지금처럼 기기 안에서 한다. AI 서버는 'AI 관찰 기록'에만 쓰이고, 설정되지 않았거나 응답하지 않아도 지표 3개 흐름은 그대로 동작한다(`app.ai.enabled`, `GET /policies`의 `aiObservationEnabled`).

---

## 4. 기능 요구사항 (ID)

ID별 백엔드 기능 요구사항(ACC·CHD·REC·CRD·SHR·TCH·SUP)은 **[requirements.md](requirements.md)** 로 옮겼다. 요구사항을 고칠 때는 그 문서를 고친다.

---

## 5. 데이터 모델

모든 시각은 UTC `DATETIME(6)` 저장, API는 ISO-8601(`Z`)로 주고받는다.

| 테이블 | 주요 컬럼 | 비고 |
|---|---|---|
| `users` | id, email(UQ), password_hash, display_name, role(PARENT/TEACHER), created_at | |
| `consents` | id, user_id, child_id(NULL 가능), type, version, agreed_at, revoked_at | 아동 삭제·탈퇴 후에도 동의·철회 이력 보존(child_id·user_id는 NULL로). 주인이 없어진 기록은 철회 시각부터 3년 뒤 파기(7장 '동의 기록 보관') |
| `refresh_tokens` | id, user_id, token_hash(UQ), expires_at, revoked_at, created_at | |
| `children` | id, parent_id, nickname, birth_year_month(CHAR 7), created_at | 실명·주민번호 없음 |
| `share_settings` | child_id(PK), joint_play, response_latency, distress, coaching_cards, ai_observations, institution_capture, updated_at | 기본 false. 뒤의 두 항목은 V5 |
| `interaction_records` | id, child_id, client_record_id(UQ with child), recorded_at, analyzed_seconds, joint_play_seconds, response_latency_sec, child_bid_count, responded_bid_count, distress_count, model_version, spec_version, change_trend, created_at | |
| `coaching_cards` | id, code(UQ), title, body, situation, metric_focus, evidence_tag, source_title, source_url, author, reviewer, active | `source_title`은 빈 문자열·스페이스만으로 둘 수 없음(CHECK `ck_cards_source_title`, 탭·줄바꿈은 거르지 않음) |
| `card_deliveries` | id, child_id, card_id, record_id, ai_observation_id, delivered_at, tried_at | 지표 기록의 카드는 record_id, AI 관찰 기록의 카드는 ai_observation_id(그 기록이 지워지면 함께 삭제) |
| `ai_observations` | id, child_id, created_by(NULL 가능), source(HOME/INSTITUTION), recorded_at, summary(NULL 가능), child_states, adult_responses, moments, people_count, model_version, prompt_version, frame_count, session_jti(UQ), created_at | **사진 컬럼 없음.** 태그 목록·시각별 메모는 JSON 글자(VARCHAR). 아동 삭제 시 CASCADE, 만든 계정 삭제 시 created_by만 NULL (V5) |
| `invite_codes` | id, child_id, code(UQ), created_at, expires_at, used_at, used_by | 부모가 취소하면 그 자리에서 행을 지운다. 만료된 지 7일·사용된 지 7일이 지난 행은 매일 04:27 작업(`ShareService.purgeStaleInviteCodes`)이 지운다(SHR-05) |
| `teacher_links` | id, teacher_id, child_id, linked_at (UQ teacher+child) | 해제 시 행 삭제 |
| `child_goals` | id, child_id, teacher_id, title, description, status, created_at, updated_at | |
| `observation_notes` | id, child_id, teacher_id, content, observed_on, created_at | |
| `support_resources` | id, name, description, phone, url, hours, category, sort_order | 전화 1개당 1행(119·112도 각각 한 행) |

열거형 값(표현 규칙 준수):

- `ChangeTrend`: `FIRST_RECORD`, `GROWING`, `STEADY`, `NEEDS_ATTENTION`
- `CardSituation`: `FIRST_RECORD`, `GROWING`, `STEADY`, `ANY`
- `MetricFocus`: `JOINT_PLAY`, `RESPONSE`, `CALM`, `GENERAL`, `HARD_MOMENT`(화면 표시 '힘든 순간 함께하기'. AI 관찰 기록의 카드에만 쓴다)
- `GoalStatus`: `IN_PROGRESS`, `ACHIEVED`, `ARCHIVED`
- `ConsentType`: `TERMS_OF_SERVICE`, `PRIVACY_POLICY`, `CHILD_DATA_GUARDIAN`, `CHILD_IMAGE_AI_ANALYSIS`(아이별, AI 관찰 기록)
- `ObservationSource`: `HOME`(보호자), `INSTITUTION`(선생님)
- `ChildState`(아이 모습 태그)·`AdultResponse`(어른 반응 태그): 값과 화면 표시는 [ai-observation.md 6장](ai-observation.md#6-관찰-기록-객체-obs)

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
5. 후보가 없으면 `GENERAL`·`ANY` 카드로 대체. 그래도 없으면 선택기는 빈 결과를 돌려주고, `CoachingService`가 6.4의 대체 규칙으로 활성 카드 1장을 고른다(활성 카드가 없을 때만 카드 없이 응답).

### 6.4 인터페이스와 장애 시 대체 규칙

```java
public interface CardSelector {
    Optional<Long> select(Context ctx); // 카드 ID만 돌려준다. 문장을 만들지 않는다.
    record Context(long childId, ChangeTrend trend, MetricFocus focus, MetricChanges changes,
                   List<CoachingCard> activeCards, Map<Long, Instant> lastDeliveredAt) {}
}
```

외부 AI 선택 서비스를 붙일 때도 입력은 지표 요약·추이, 출력은 카드 ID뿐이다. 선택 결과는 `CoachingService`가 아래 순서로 처리한다(CRD-08).

1. 기본 선택기가 예외를 던지거나, 활성 카드에 없는(모르는·비활성) ID를 주거나, 아무 카드도 고르지 않으면 경고 로그를 남기고 다음 단계로 간다.
2. 기본 선택기가 규칙 기반이 아니면 규칙 기반 선택기(6.3)로 다시 고른다.
3. 그래도 없으면 고정 규칙: 상황이 정확히 맞는 `GENERAL` 카드 → `ANY`·`GENERAL` 카드 → 활성 카드 중 id가 가장 작은 카드.
4. 활성 카드가 한 장도 없을 때만 카드 없이 응답한다. 어느 단계도 예외를 밖으로 던지지 않으므로 카드 선택 때문에 기록 저장이 실패하지 않는다.
5. `NEEDS_ATTENTION`은 선택기를 부르지 않고 상담·기관 안내를 응답한다(대체 규칙 대상 아님).

외부 선택 서비스의 지연은 그 클라이언트가 1초 제한 시간을 두고 넘으면 예외를 던지는 방식으로 1단계에 합류시킨다.

### 6.5 AI 관찰 기록(가정)의 카드

가정에서 만든 AI 관찰 기록(`source = HOME`)에는 카드 1장을 고른다. 기관 기록에는 카드가 없다. VL 모델은 조언을 쓰지 않고, 여기서도 사람이 쓴 카드에서 고르기만 한다.

1. 초점은 아이 모습 태그로 정한다(`ObservationFocusRule`): `UPSET_CRYING`·`REFUSING`·`THROWING_OR_HITTING` 중 하나라도 있으면 `HARD_MOMENT` → 없고 `PLAYING_ALONE`·`MOVING_AROUND`가 있으면 `JOINT_PLAY` → 없고 `SEEKING_ADULT`가 있으면 `RESPONSE` → 그 밖에는 `GENERAL`.
2. 그 초점으로 6.3의 3~5와 같은 규칙을 쓴다. 상황은 `STEADY`로 두므로 후보는 `ANY`·`STEADY` 카드이고, 최근 받은 3장은 건너뛴다.
3. 선택이 실패하면 6.4의 대체 규칙을 그대로 쓴다. 카드 선택 때문에 관찰 기록 저장이 실패하지 않는다.
4. `HARD_MOMENT` 카드는 지표 기록(6.3)에서는 후보가 되지 않는다(지표 쪽 초점에는 `HARD_MOMENT`가 없다).
5. 전달 이력은 `card_deliveries.ai_observation_id`로 그 기록에 이어진다. 기록을 지우거나 AI 동의를 철회하면 함께 지워진다.

---

## 7. 보안·개인정보

| 항목 | 규칙 |
|---|---|
| 전송 | 운영은 HTTPS만(nginx TLS 종단, HSTS). 카메라·`getUserMedia` 사용에도 HTTPS 필요 |
| 인증 | `Authorization: Bearer <JWT>`. HS256, 서명 키 32바이트 이상, 환경변수로만 주입(기본값으로 기동 불가) |
| 비밀번호 | BCrypt(cost 10). 변경(`PUT /me/password`)은 지금 비밀번호 확인 후, 그 사용자의 리프레시 토큰을 모두 폐기하고 새 토큰 묶음을 준다. 지금 비밀번호를 틀리면 로그인 실패로 함께 센다 |
| 로그인 제한 | 이메일당 15분 안에 5번 틀리면 5번째 실패 시각부터 15분 잠금(429), IP당 인증 요청 분당 제한 |
| 권한 | 역할(`PARENT`/`TEACHER`) + 리소스 소유 검사. 남의 아동은 404로 응답(존재 여부 노출 방지) |
| 입력 | Bean Validation, 본문 64KB 제한, multipart 비활성. 검증 오류 문구(`fieldErrors[].message`)는 요청 언어와 상관없이 칸 이름이 들어간 한국어 해요체(`common/FieldErrorMessages`, DTO에 직접 적은 문구가 있으면 그것) |
| AI 서버와의 신뢰 | 분석 토큰·영수증 모두 HS256 JWT, 공유 비밀 `AI_SHARED_SECRET`(32바이트 이상, `JWT_SECRET`과 다른 값. 켜져 있는데 없거나 짧거나 같으면 기동하지 않음). 발급자·대상이 서로 달라 분석 토큰을 영수증으로 쓸 수 없다. 영수증은 서명·알고리즘·대상·만료·아이·사용자·출처·`jti`(DB UNIQUE)를 확인하고, 관찰 기록 객체는 태그·길이·개수를 엄격히 검사한 뒤 문장 검사를 한 번 더 한다([ai-observation.md 4·6장](ai-observation.md)) |
| 요청 제한(AI) | 분석 토큰 발급은 사용자당 시간당 20회(429 `TOO_MANY_ATTEMPTS`) |
| 헤더 | CSP(self + 필요 최소), X-Content-Type-Options, Referrer-Policy, Permissions-Policy(camera=self, microphone=self) |
| 로그 | 이메일·토큰·메모 본문을 로그에 남기지 않음. 요청 ID만. AI 관찰 기록도 문장·영수증·분석 토큰을 남기지 않는다(아이 id, 출처, 사유 코드만) |
| 삭제 | 탈퇴·아동 삭제는 즉시 물리 삭제(동의·철회 이력만 법적 근거용으로 보존). AI 동의 철회도 그 아이의 AI 관찰 기록과 그 기록으로 전달된 카드 이력을 즉시 물리 삭제 |
| 동의 기록 보관 | 탈퇴로 계정이 지워진 동의 기록(user_id 없음)과 아이 삭제로 아이가 지워진 아이별 동의 기록(child_id 없음, `CHILD_DATA_GUARDIAN`·`CHILD_IMAGE_AI_ANALYSIS`)은 **철회 시각부터 3년**(`app.policy.consent-retention`, 기본 `1095d`) 보관한 뒤 매일 04:37 작업(`ConsentService.purgeOrphanedConsents`)이 물리 삭제한다. 계정이 남아 있는 사람의 기록은 지우지 않는다(내 동의 이력). 기간은 법률 검토 전 가안(11장) |
| 열람·내려받기 | `GET /me/export`로 본인 데이터를 JSON 파일로 내려받는다(사용자당 시간당 3회). 비밀번호 해시·토큰·영수증·다른 사람의 이메일은 담지 않고, 내용은 로그에 남기지 않는다 |
| 재동의 | 이용약관·개인정보 처리방침 버전(`app.policy.terms-version`·`privacy-version`)을 올리면 기존 사용자의 `GET /me`·토큰 응답 `pendingConsents`에 그 항목이 담기고, 화면이 `POST /me/consents`로 새 버전 동의를 받는다 |
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
| 400 | `VALIDATION_FAILED`, `UNSUPPORTED_SPEC_VERSION`, `MALFORMED_REQUEST`, `AI_RECEIPT_INVALID` (영수증의 서명·대상·만료·아이·사용자·출처·모양 중 하나라도 맞지 않음) |
| 401 | `UNAUTHORIZED`, `INVALID_CREDENTIALS`, `INVALID_REFRESH_TOKEN` |
| 403 | `FORBIDDEN_ROLE` |
| 404 | `NOT_FOUND` |
| 405 | `METHOD_NOT_ALLOWED` |
| 409 | `EMAIL_TAKEN`, `ALREADY_LINKED`, `DUPLICATE_REQUEST` (DB 제약 충돌. 예: 동시에 들어온 같은 요청, 이미 쓴 영수증의 `jti`) |
| 413 | `PAYLOAD_TOO_LARGE` |
| 415 | `UNSUPPORTED_MEDIA_TYPE` (파일 업로드 시도 포함) |
| 422 | `CONSENT_REQUIRED`, `GUARDIAN_CONSENT_REQUIRED`, `AI_CONSENT_REQUIRED`, `AI_CONSENT_VERSION_MISMATCH`, `INVITE_CODE_INVALID`, `CHILD_LIMIT_REACHED`, `INVITE_LIMIT_REACHED` |
| 429 | `TOO_MANY_ATTEMPTS` (분석 토큰 발급 제한 포함) |
| 500 | `INTERNAL_ERROR` |
| 503 | `AI_UNAVAILABLE` (AI 기능이 꺼져 있음. 지표 3개 흐름과는 무관) |

위 표는 `common/ErrorCode.java`의 모든 값을 담는다. 코드를 추가하면 이 표도 함께 고친다.

`VALIDATION_FAILED`의 `fieldErrors`는 `[ { "field": "title", "message": "목표 제목은 1~60자로 입력해 주세요." } ]`처럼 칸마다 한국어 문장을 준다. 브라우저 언어가 영어여도 같다(`common/FieldErrorMessages`). 입력값 자체는 문구에 넣지 않는다.

---

## 10. 테스트 전략

- 백엔드: 서비스 규칙 단위 테스트(추이·카드 선택), MockMvc 통합 테스트(H2 MySQL 모드 + Flyway)로 전체 흐름·권한·동의 거부·파일 업로드 거부 검증.
- AI 관찰 기록: 영수증 검증·관찰 기록 객체 검사·문장 검사·태그→초점 규칙 단위 테스트, 테스트가 공유 비밀로 직접 서명한 영수증으로 전체 흐름(동의 → 토큰 → 저장 → 카드, 기관 촬영 조건, 공유, 중복 `jti`, 위조·만료·불일치 영수증, 철회 시 삭제, 삭제 연쇄, 기능 꺼짐) 통합 테스트.
- 프런트: 분석 로직 순수 함수 단위 테스트(Vitest, 합성 신호), 타입 검사, 빌드.
- 공통: 표현 검수 스크립트, 실제 브라우저 E2E(데모 계정으로 전체 흐름).
- 정확도 평가: 팀원 연출 영상으로 사람 수기 측정값과 비교(별도 기록, 앱·발표에는 "정확도 ○○%"를 표시하지 않는다).

## 11. 출시 전 확인 목록 (코드 밖)

- [ ] 약관·개인정보 처리방침 본문 법률 검토 (현재 버전 `2026-11-v1`은 초안)
- [ ] 주인이 없어진 동의 기록의 보관 기간(가안: 철회 후 3년, `app.policy.consent-retention`) 법률 검토, 개인정보 처리방침 화면에 같은 기간 표기
- [ ] 상호작용 지표의 민감정보 해당 여부 법률 자문
- [ ] 코칭 카드 전문가 검수(검수자 필드 채우기)
- [ ] 상담·기관 안내의 전화번호·링크 최신 여부 확인
- [ ] 운영 도메인·TLS 인증서, JWT 서명 키, DB 비밀번호 발급
- [ ] AI 관찰 기록을 켜기 전: [ai-observation.md 10장](ai-observation.md#10-아직-확인이-필요한-것-코드로-해결되지-않음)의 확인 항목(아동 사진 처리의 법률 검토·처리 위탁 고지, 기관에서 함께 찍히는 사람의 동의, VL 문장 품질 확인), `AI_SHARED_SECRET` 발급, '힘든 순간 함께하기' 카드 6장 전문가 검수
