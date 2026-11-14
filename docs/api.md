# API 명세 v1

- 기본 경로: `/api/v1`, 본문 `application/json; charset=utf-8`, 최대 64KB
- 인증: `Authorization: Bearer <accessToken>` (🔓 = 인증 불필요)
- 권한 표기: **P** 부모, **T** 교사, **P(own)** 본인 아동의 부모, **T(linked)** 연결된 교사
- 에러 형식·코드: [SPEC.md 9장](SPEC.md#9-에러-형식) · 요구사항 ID: [requirements.md](requirements.md)
- 이 서버는 파일·이미지를 받지 않는다(multipart·미디어·이미지 본문 415). AI 관찰 기록의 장면 사진은 웹이 AI 서버(`/ai/v1/analyze`, [ai-observation.md 5장](ai-observation.md))로 직접 보내고, 이 서버에는 글자(영수증)만 온다.
- 목록 조회의 `limit`·`weeks`는 범위를 벗어나면 가장 가까운 허용값으로 맞춘다(에러 아님).
- **목록 커서(더 보기)**: 기록·카드 이력·관찰 메모·AI 관찰 기록 목록(아래 표에 `beforeId?`로 표시)은 `limit`과 `beforeId`를 받는다. `beforeId`를 주면 **그 id의 항목보다 오래된 항목만** 같은 정렬(최신순, 같은 시각은 id 큰 순)로 준다. 다음 쪽은 받은 목록의 마지막 `id`를 `beforeId`로 보내고, 받은 개수가 `limit`보다 적으면 끝이다. 응답 모양(배열)은 그대로이고, 매개변수를 주지 않으면 예전과 같다. `beforeId`가 그 목록에 없는 항목(지워졌거나 다른 아이의 항목, 볼 수 없는 항목)이면 400 `VALIDATION_FAILED` → 처음 쪽부터 다시 불러온다. 권한 검사가 먼저라 남의 아이 목록은 커서와 상관없이 404다. 화면의 '더 보기'는 `limit` 50 이하를 쓴다(기록·메모 목록은 예전처럼 100까지 받는다).
- 실행 중인 서버에서 `/swagger-ui.html`로 같은 내용을 볼 수 있다(운영에서는 비활성).

## 1. 공개

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET 🔓 | `/policies` | 현재 동의 문서 버전, 주의 문구, AI 관찰 기록 사용 가능 여부 |
| GET 🔓 | `/support-resources` | 상담·기관 연결 안내 목록(`sort_order` 순, 1시간 캐시) |

```json
// GET /policies
{ "termsVersion": "2026-11-v1", "privacyVersion": "2026-11-v1", "guardianVersion": "2026-11-v1",
  "aiAnalysisVersion": "2026-11-v1", "aiObservationEnabled": true,
  "disclaimer": "본 서비스는 의료기기가 아니며 발달 상태를 판단하지 않습니다. 걱정되는 점은 전문의와 상담하세요." }
// aiAnalysisVersion: 'AI 관찰 기록' 동의 문서 버전(동의를 보낼 때 그대로 돌려준다)
// aiObservationEnabled: AI 서버가 설정돼 있는지. false면 화면은 AI 관찰 기록만 숨기고 나머지는 그대로 쓴다
// GET /support-resources  — 전화번호는 한 항목에 하나(119와 112도 각각 한 항목)
[ { "id": 1, "name": "긴급 상황 119 (구급·화재)", "description": "...", "phone": "119", "url": null,
    "hours": "24시간", "category": "EMERGENCY" },
  { "id": 8, "name": "긴급 상황 112 (경찰)", "description": "...", "phone": "112", "url": null,
    "hours": "24시간", "category": "EMERGENCY" } ]
```

## 2. 인증

| 메서드 | 경로 | 권한 | 설명 |
|---|---|---|---|
| POST 🔓 | `/auth/signup` | | 가입 + 토큰 발급 |
| POST 🔓 | `/auth/login` | | 로그인 |
| POST 🔓 | `/auth/refresh` | | 리프레시 토큰 회전 |
| POST 🔓 | `/auth/logout` | | 리프레시 토큰 폐기 |
| GET | `/me` | P, T | 내 정보 `{ id, email, displayName, role, pendingConsents }` — `pendingConsents`는 아래 '재동의' 참고 |
| PATCH | `/me` | P, T | 표시명 수정 `{ displayName }`(앞뒤 공백을 지운 뒤 1~30자, 공백만은 안 됨) → 200 `GET /me`와 같은 본문 (ACC-08) |
| PUT | `/me/password` | P, T | 비밀번호 변경 `{ currentPassword, newPassword }` → 200 토큰 묶음(아래) (ACC-09) |
| DELETE | `/me` | P, T | 탈퇴(즉시 삭제). 본문 `{ "password": "..." }` → 204. 비밀번호가 다르면 401 `INVALID_CREDENTIALS` |
| GET | `/me/consents` | P, T | 내 동의 이력 `[ { type, version, childId, agreedAt, revokedAt } ]` (최신순) |
| POST | `/me/consents` | P, T | 바뀐 문서에 다시 동의 `{ type, version }` → 204 (ACC-10) |
| GET | `/me/export` | P, T | 내 데이터 내려받기 → 200 JSON 파일(아래) (ACC-11) |

```json
// POST /auth/signup
{ "email": "parent@example.com", "password": "abcd1234", "displayName": "하늘맘", "role": "PARENT",
  "consents": [ { "type": "TERMS_OF_SERVICE", "version": "2026-11-v1" },
                { "type": "PRIVACY_POLICY",   "version": "2026-11-v1" } ] }
// 201
{ "accessToken": "eyJ...", "accessTokenExpiresIn": 1800, "refreshToken": "q9X...", 
  "user": { "id": 1, "email": "parent@example.com", "displayName": "하늘맘", "role": "PARENT",
            "pendingConsents": [] } }
```

`/auth/login` 요청 `{ email, password }`, `/auth/refresh`·`/auth/logout` 요청 `{ refreshToken }` — 응답은 signup과 같은 토큰 묶음(logout은 204). 토큰 묶음의 `user`에도 `pendingConsents`가 담긴다.

### 로그인 제한 (ACC-05)

같은 이메일로 15분 안에 5번 틀리면, **5번째로 틀린 시각부터 15분 동안** 로그인(과 비밀번호 변경)이 429 `TOO_MANY_ATTEMPTS`다. 잠금이 풀리면 처음부터 다시 센다. 로그인에 성공하면 그때까지의 실패는 지운다. 인증 API(`/auth/*`, `PUT /me/password`)에는 IP 기준 분당 요청 제한도 있다.

### 비밀번호 변경

```json
// PUT /me/password
{ "currentPassword": "abcd1234", "newPassword": "newpass99" }
// 200 — signup과 같은 토큰 묶음(새 액세스·리프레시 토큰)
```

- 새 비밀번호 규칙은 가입과 같다(8~64자, 영문+숫자). 지금 비밀번호와 같으면 400 `VALIDATION_FAILED`.
- 가입·변경 모두, 64자 안이어도 UTF-8로 72바이트를 넘으면(한글이 많을 때, BCrypt 상한) 400 `VALIDATION_FAILED`.
- 지금 비밀번호가 틀리면 401 `INVALID_CREDENTIALS`(로그인 실패로 함께 세어 5번이면 15분 잠금, 429). IP 기준 요청 제한도 적용된다.
- 바꾸면 이 사용자의 **다른 리프레시 토큰은 모두 폐기**된다(DB에서 지운다. 다른 기기가 예전 토큰으로 `/auth/refresh`를 보내면 401 `INVALID_REFRESH_TOKEN`만 나고, 재사용 탐지로 새 토큰까지 폐기되지는 않는다). 다른 기기의 액세스 토큰은 만료(최대 30분)까지만 쓰이고 그 뒤에는 다시 로그인해야 한다. 응답으로 받은 새 토큰으로 바꿔 끼운다.

### 재동의 (ACC-10)

`pendingConsents`: `TERMS_OF_SERVICE`, `PRIVACY_POLICY` 중 **가장 최근에 한(철회하지 않은) 동의의 버전이 `GET /policies`의 현재 버전(`termsVersion`·`privacyVersion`)과 다른 항목**(동의 기록이 없거나 철회했어도 담긴다). 평소에는 빈 배열이다. 값이 있으면 화면은 바뀐 문서를 보여 주고 다시 동의를 받는다.

```json
// POST /me/consents
{ "type": "TERMS_OF_SERVICE", "version": "2026-11-v1" }
// 204
```

- `type`은 `TERMS_OF_SERVICE`·`PRIVACY_POLICY`만 받는다(아이별 동의는 아이 화면의 API). 그 밖의 값이나 현재 버전이 아닌 `version`은 400 `VALIDATION_FAILED`.
- 이미 현재 버전에 동의해 있으면 새로 남기지 않는다(같은 요청을 여러 번 보내도 204). 예전 버전의 동의 기록은 그대로 이력에 남는다.

### 내 데이터 내려받기 (ACC-11)

`GET /me/export` → 200 `application/json`, `Content-Disposition: attachment; filename="hamkke-jaram-export.json"`, `Cache-Control: no-store`. 사용자당 **시간당 3회**(넘으면 429 `TOO_MANY_ATTEMPTS`). 내용은 로그에 남기지 않는다.

- 담는 것: 계정(비밀번호 해시 없음), 동의 이력, 그리고
  - 부모(`children`): 아이마다 별칭·생년월, 공유 설정, 연결된 선생님(표시명·연결 시각만), 지표 기록, 카드 전달 이력, 목표, 관찰 메모, AI 관찰 기록(글자만)
  - 교사(`teacher`): 지금 연결된 아이(id·별칭·연결 시각), 본인이 쓴 목표·관찰 메모(연결이 끊긴 아이의 것도 남아 있으면 아이 id와 함께)
- 담지 않는 것: 비밀번호 해시, 액세스·리프레시 토큰, 분석 토큰·영수증(`jti` 포함), 다른 사람의 이메일. 영상·음성·사진은 원래 서버에 없다.

```json
// GET /me/export (부모)
{ "format": "hamkke-jaram-export-v1", "exportedAt": "2026-11-14T03:00:00Z", "notice": "...",
  "account": { "id": 1, "email": "parent@example.com", "displayName": "하늘맘", "role": "PARENT", "createdAt": "..." },
  "consents": [ { "type": "TERMS_OF_SERVICE", "version": "2026-11-v1", "childId": null, "agreedAt": "...", "revokedAt": null } ],
  "children": [ { "id": 7, "nickname": "하늘", "birthYearMonth": "2023-05", "createdAt": "...",
                  "shareSettings": { "jointPlay": false, "...": "..." },
                  "linkedTeachers": [ { "displayName": "김선생", "linkedAt": "..." } ],
                  "interactionRecords": [ /* 4장 기록 응답과 같은 모양 */ ],
                  "cardDeliveries": [ { "id": 3, "deliveredAt": "...", "triedAt": null, "recordId": 10,
                                        "aiObservationId": null, "cardId": 2, "cardTitle": "..." } ],
                  "goals": [ /* 8장 목표 */ ], "observationNotes": [ /* 8장 관찰 메모 */ ],
                  "aiObservations": [ { "id": 4, "source": "HOME", "recordedAt": "...", "summary": "...",
                                        "childStates": [], "adultResponses": [], "moments": [], "peopleCount": 2,
                                        "modelVersion": "...", "promptVersion": "...", "frameCount": 6,
                                        "authorName": null, "createdAt": "..." } ] } ] }
// GET /me/export (교사) — "children" 대신
  "teacher": { "links": [ { "childId": 7, "childNickname": "하늘", "linkedAt": "..." } ],
               "goals": [ { "id": 3, "childId": 7, "title": "...", "description": "...", "status": "IN_PROGRESS",
                            "createdAt": "...", "updatedAt": "..." } ],
               "observationNotes": [ { "id": 5, "childId": 7, "content": "...", "observedOn": "2026-11-05", "createdAt": "..." } ] }
```

## 3. 아동 (부모)

| 메서드 | 경로 | 권한 | 설명 |
|---|---|---|---|
| POST | `/children` | P | 등록(법정대리인 동의 필수) |
| GET | `/children` | P | 내 아동 목록 |
| GET | `/children/{id}` | P(own) | 상세 |
| PATCH | `/children/{id}` | P(own) | 별칭·생년월 수정 `{ nickname?, birthYearMonth? }` — 보낸 항목만 바뀐다 |
| DELETE | `/children/{id}` | P(own) | 삭제 = 동의 철회, 관련 데이터(AI 관찰 기록 포함) 삭제 → 204 |

```json
// POST /children
{ "nickname": "하늘", "birthYearMonth": "2023-05",
  "guardianConsent": { "agreed": true, "version": "2026-11-v1" } }
// 201
{ "id": 7, "nickname": "하늘", "birthYearMonth": "2023-05", "ageMonths": 41, "createdAt": "2026-11-06T09:00:00Z" }

// PATCH /children/7  (생략한 항목은 그대로. 응답은 위와 같은 모양)
{ "nickname": "바다" }
```

PATCH 검증: `nickname`은 1~20자이며 공백만으로 된 값(`"   "`)은 400 `VALIDATION_FAILED`, 앞뒤 공백은 지우고 저장한다. `birthYearMonth`는 `YYYY-MM`, 만 0~6세 범위.

## 4. 상호작용 기록

| 메서드 | 경로 | 권한 | 설명 |
|---|---|---|---|
| POST | `/children/{id}/interaction-records` | P(own) | 지표 저장 + 추이 + 카드 → 201(새 기록) / 200(재전송). 같은 `clientRecordId` 두 요청이 거의 동시에 와도 늦은 쪽은 409가 아니라 먼저 저장된 결과를 200으로 받는다(REC-03) |
| GET | `/children/{id}/interaction-records?limit=20&beforeId?` | P(own) | 최근 기록 목록(촬영 시각 최신순, 같은 시각은 id 큰 순, `limit` 1~100). 항목은 아래 `record`와 같은 모양. `beforeId`는 맨 위 "목록 커서" |
| DELETE | `/children/{id}/interaction-records/{recordId}` | P(own) | 기록 삭제 → 204 |
| GET | `/children/{id}/weekly-summary?weeks=8` | P(own) | 주간 요약(`weeks` 1~26) |

요청 본문은 [지표 정의서 3장](https://github.com/hamkke-jaram/hamkke-ai/blob/main/docs/metrics.md#3-전송-json). 응답:

```json
// 201 (같은 clientRecordId 재전송이면 200, 같은 본문)
{
  "record": { "id": 31, "recordedAt": "2026-11-06T09:30:00Z", "analyzedSeconds": 182.5,
              "jointPlaySeconds": 121.0, "jointPlayRatio": 0.66, "responseLatencySec": 1.42,
              "childBidCount": 14, "respondedBidCount": 11, "distressEpisodeCount": 1,
              "distressPerTenMinutes": 3.3, "changeTrend": "GROWING",
              "modelVersion": "pose_landmarker_lite+audio-heuristic-1", "specVersion": "metrics-v1",
              "createdAt": "2026-11-06T09:31:02Z" },
  "metricChanges": { "jointPlay": "UP", "response": "FLAT", "calm": "NA" },
  "coaching": {
    "delivery": { "id": 90, "deliveredAt": "...", "triedAt": null,
                  "card": { "id": 3, "title": "...", "body": "...", "situation": "GROWING", "metricFocus": "JOINT_PLAY",
                            "evidenceTag": "...", "sourceTitle": "...", "sourceUrl": "...", "author": "...", "reviewer": null } },
    "guidance": null
  }
}
```

`changeTrend = NEEDS_ATTENTION`이면 `coaching.delivery = null`, `coaching.guidance = { "message": "...", "resources": [ ... ] }`.
그 밖의 추이에서는 카드 선택기가 실패해도 대체 규칙([SPEC 6.4](SPEC.md#64-인터페이스와-장애-시-대체-규칙))으로 카드 1장이 담긴다. `delivery`와 `guidance`가 모두 `null`인 경우는 활성 카드가 한 장도 없을 때뿐이다.

```json
// GET /children/{id}/weekly-summary?weeks=8
{ "weeks": [ { "weekStart": "2026-11-02", "recordCount": 2, "jointPlayRatio": 0.61,
               "jointPlaySeconds": 110.0, "responseLatencySec": 1.5, "distressPerTenMinutes": 2.1 } ] }
```

## 5. 코칭 카드

| 메서드 | 경로 | 권한 | 설명 |
|---|---|---|---|
| GET | `/children/{id}/card-deliveries?limit=20&beforeId?` | P(own), T(linked, 카드 공유 켜짐) | 받은 카드 이력(전달 시각 최신순, `limit` 1~50, `beforeId`는 맨 위 "목록 커서"). 항목은 위 `coaching.delivery`와 같은 모양. AI 관찰 기록(가정)으로 받은 카드도 여기에 함께 나온다 |
| POST | `/children/{id}/card-deliveries/{deliveryId}/tried` | P(own) | "해봤어요" 표시 → 갱신된 전달 항목(이미 표시했으면 처음 시각 유지) |

## 6. 공유 설정·초대

| 메서드 | 경로 | 권한 | 설명 |
|---|---|---|---|
| GET | `/children/{id}/share-settings` | P(own) | 공유 설정 `{ jointPlay, responseLatency, distress, coachingCards, aiObservations, institutionCapture, updatedAt }` |
| PUT | `/children/{id}/share-settings` | P(own) | `{ jointPlay, responseLatency, distress, coachingCards, aiObservations?, institutionCapture? }` → 공유 설정. 앞의 네 값은 필수. `aiObservations`(가정의 AI 관찰 기록을 선생님에게 보임)·`institutionCapture`(연결된 선생님이 기관에서 촬영·분석할 수 있음)는 생략하면 지금 값을 그대로 둔다 |
| POST | `/children/{id}/invite-codes` | P(own) | 초대 코드 발급 → 201 `{ code, expiresAt }`(72시간). 유효 코드가 3개면 422 `INVITE_LIMIT_REACHED`(쓰지 않을 코드를 취소하면 다시 발급할 수 있다) |
| GET | `/children/{id}/invite-codes` | P(own) | 아직 쓸 수 있는(쓰지 않았고 만료 전) 코드만 최신 발급순 `[ { code, expiresAt, createdAt } ]`. 사용·만료·취소된 코드는 나오지 않는다 |
| DELETE | `/children/{id}/invite-codes/{code}` | P(own) | 코드 취소 → 204. 이후 그 코드로 연결하면 422 `INVITE_CODE_INVALID`. 이 아이의 쓸 수 있는 코드가 아니면(사용·만료·취소됨, 다른 아이의 코드) 404. 공백·`-`·대소문자는 무시 |
| GET | `/children/{id}/teachers` | P(own) | 연결된 교사 `[ { teacherId, displayName, linkedAt } ]` |
| DELETE | `/children/{id}/teachers/{teacherId}` | P(own) | 연결 해제 → 204 |

초대 코드 정리: 만료된 지 7일, 또는 사용된 지 7일이 지난 코드 행은 매일 04:27(서버 시간대)에 지운다(누가 썼는지도 함께 사라진다). 취소한 코드는 그 자리에서 지운다. 연결 자체(`teacher_links`)는 코드가 지워져도 그대로다.

## 7. 교사

| 메서드 | 경로 | 권한 | 설명 |
|---|---|---|---|
| POST | `/teacher/links` | T | `{ code }` → 201 연결된 아동 `{ id, nickname, ageMonths, linkedAt }`. 공백·`-`·대소문자는 무시. 만료·사용됨·없는 코드는 모두 422 `INVITE_CODE_INVALID`, IP 기준 요청 제한 |
| GET | `/teacher/children` | T | 연결된 아동 목록 `[ { id, nickname, ageMonths, linkedAt } ]` |
| DELETE | `/teacher/links/{childId}` | T(linked) | 선생님이 스스로 연결 끊기 → 204. 이후 그 아이의 모든 경로는 404. 이미 쓴 목표·관찰 메모는 남는다(TCH-07). 연결되지 않은 아이면 404, 부모 계정이면 403 `FORBIDDEN_ROLE` |
| GET | `/teacher/children/{id}/shared-summary?weeks=8` | T(linked) | 공유된 항목만 담긴 주간 요약(`weeks` 1~26) |

```json
// GET /teacher/children/7/shared-summary  (jointPlay만 공유된 경우)
{ "child": { "id": 7, "nickname": "하늘", "ageMonths": 41 },
  "sharedItems": ["JOINT_PLAY"],
  "weeks": [ { "weekStart": "2026-11-02", "recordCount": 2, "jointPlayRatio": 0.61, "jointPlaySeconds": 110.0 } ] }
```

`sharedItems` 값: `JOINT_PLAY`, `RESPONSE_LATENCY`, `DISTRESS`, `COACHING_CARDS`, `AI_OBSERVATIONS`(가정의 AI 관찰 기록 공유가 켜짐), `INSTITUTION_CAPTURE`(기관 촬영 허용이 켜져 있고 **유효한 AI 동의가 있을 때만** 담긴다 → 이 값이 있으면 선생님 화면에서 촬영을 열 수 있다).

## 8. 목표·관찰 메모

| 메서드 | 경로 | 권한 | 설명 |
|---|---|---|---|
| GET | `/children/{id}/goals` | P(own), T(linked) | 목표 목록 |
| POST | `/children/{id}/goals` | T(linked) | `{ title(1~60), description(0~500) }` → 201 |
| PATCH | `/children/{id}/goals/{goalId}` | T(작성자) | `{ title?, description?, status? }` — `status`: `IN_PROGRESS`, `ACHIEVED`, `ARCHIVED`. 보낸 항목만 바뀐다. `title`을 보냈다면 공백만으로 둘 수 없다(400 `VALIDATION_FAILED`) |
| DELETE | `/children/{id}/goals/{goalId}` | T(작성자) | → 204 |
| GET | `/children/{id}/observations?limit=50&beforeId?` | P(own), T(linked) | 메모 목록(관찰일 최신순, 같은 날은 id 큰 순, `limit` 1~100, `beforeId`는 맨 위 "목록 커서") |
| POST | `/children/{id}/observations` | T(linked) | `{ content(1~1000), observedOn: "2026-11-06" }` → 201 |
| DELETE | `/children/{id}/observations/{noteId}` | T(작성자) | → 204 |

목표·메모 응답에는 작성 교사 표시명(`teacherName`)이 포함된다. (`/observations`는 교사가 직접 쓰는 관찰 메모다. AI가 적은 기록은 9장의 `/ai-observations`.)

```json
// 목표
{ "id": 3, "title": "친구 이름 부르기", "description": "...", "status": "IN_PROGRESS", "teacherId": 12,
  "teacherName": "김선생", "createdAt": "...", "updatedAt": "..." }
// 관찰 메모
{ "id": 5, "content": "블록 놀이에 10분 넘게 집중했어요.", "observedOn": "2026-11-05", "teacherId": 12,
  "teacherName": "김선생", "createdAt": "..." }
```

## 9. AI 관찰 기록

약속 전체(토큰·영수증 클레임, 관찰 기록 객체, AI 서버 API)는 **[ai-observation.md](ai-observation.md)**. 흐름:

```
1) POST /children/{id}/ai-sessions            → 분석 토큰
2) POST /ai/v1/analyze (AI 서버, Bearer 분석 토큰, 장면 사진 ≤6장)  → { observation, receipt, ... }
3) POST /children/{id}/ai-observations { receipt }  → 저장 + (가정 기록이면) 카드 1장
```

| 메서드 | 경로 | 권한 | 설명 |
|---|---|---|---|
| GET | `/children/{id}/ai-consent` | P(own) | 아이별 AI 관찰 기록 동의 상태 `{ agreed, version, agreedAt }` |
| PUT | `/children/{id}/ai-consent` | P(own) | `{ agreed: true, version }` → 동의 기록 → `{ agreed, version, agreedAt }`. `version`이 `/policies`의 `aiAnalysisVersion`과 다르면 422 `AI_CONSENT_VERSION_MISMATCH`, `agreed`가 true가 아니면 400. 이미 동의했으면 처음 시각 그대로. AI 기능이 꺼져 있으면 503 `AI_UNAVAILABLE` |
| DELETE | `/children/{id}/ai-consent` | P(own) | 철회 → 204. **그 아이의 AI 관찰 기록(가정·기관)과 그 기록으로 받은 카드 이력을 모두 지우고**, 공유 설정의 `aiObservations`·`institutionCapture`를 끈다. 여러 번 불러도 204 |
| POST | `/children/{id}/ai-sessions` | P(own), T(linked) | 분석 토큰 발급 → 201 `{ token, expiresAt, maxFrames }`. 본문 없음. 아래 "분석 토큰" 참고 |
| POST | `/children/{id}/ai-observations` | P(own), T(linked) | `{ receipt, recordedAt? }` → 201 `{ observation, coaching }`. 아래 "관찰 기록 저장" 참고 |
| GET | `/children/{id}/ai-observations?limit=20&beforeId?` | P(own), T(linked) | 목록(기록 시각 최신순, `limit` 1~50, `beforeId`는 맨 위 "목록 커서". 기준 기록도 그 사람이 볼 수 있는 기록이어야 한다). P는 전부. T는 `INSTITUTION` 기록은 항상, `HOME` 기록은 `aiObservations` 공유가 켜진 경우만 |
| DELETE | `/children/{id}/ai-observations/{observationId}` | P(own), T(만든 사람) | → 204. P는 전부, T는 자기가 만든 기록만(남의 기록은 404). 그 기록으로 받은 카드 이력도 함께 지워진다 |

```json
// GET /children/7/ai-consent
{ "agreed": true, "version": "2026-11-v1", "agreedAt": "2026-11-13T02:10:00Z" }
// 동의한 적이 없거나 철회했으면
{ "agreed": false, "version": null, "agreedAt": null }
```

`agreed`는 **지금 유효한 동의**가 있는지다: 철회되지 않았고 현재 동의 문서 버전에 대한 것. 동의 문서 버전이 바뀌면 예전 동의는 `agreed: false`, `version: "<예전 버전>"`으로 보이고, 다시 동의하기 전까지 분석 토큰이 발급되지 않는다.

### 분석 토큰

| 요청한 사람 | 조건 | 조건이 안 맞으면 |
|---|---|---|
| P(own) | 유효한 AI 동의 | 422 `AI_CONSENT_REQUIRED` |
| T(linked) | 유효한 AI 동의 + 공유 설정 `institutionCapture` | 404 `NOT_FOUND`(존재를 드러내지 않음) |

- 사용자당 시간당 20회까지(넘으면 429 `TOO_MANY_ATTEMPTS`). AI 기능이 꺼져 있으면 503 `AI_UNAVAILABLE`.
- 토큰은 HS256 JWT이며 유효 10분, 한 번의 분석용이다. 사용자 로그인 토큰과 다른 비밀로 서명한다. 사진·문장은 담기지 않는다.

```json
// POST /children/7/ai-sessions → 201
{ "token": "eyJhbGciOiJIUzI1NiJ9...", "expiresAt": "2026-11-13T02:20:00Z", "maxFrames": 6 }
// 토큰 본문(클레임)
{ "iss": "hamkke-api", "aud": "hamkke-ai", "sub": "11", "cid": 7, "src": "HOME",
  "jti": "0f8fad5b-d9cb-469f-a165-70867728950e", "iat": 1794535800, "exp": 1794536400 }
```

`aud`는 문자열 하나로 싣는다. `src`는 보호자면 `HOME`, 선생님이면 `INSTITUTION`.

### 관찰 기록 저장

요청 `{ "receipt": "<AI 서버가 돌려준 JWS>", "recordedAt": "2026-11-13T02:05:00Z" }` — `recordedAt`(장면을 찍은 시각)은 생략하면 지금 시각이고, 10분 넘게 미래면 400 `VALIDATION_FAILED`. 사용자가 관찰 문장을 직접 보낼 수는 없다.

서버가 확인하는 것(하나라도 어긋나면 400 `AI_RECEIPT_INVALID`, 아무것도 저장하지 않음):

1. 서명(공유 비밀, HS256만), 발급자 `hamkke-ai`, 대상 `hamkke-api`, 만료(`exp`·`iat` 필수, 유효 시간 15분 이하, 서버 시계 차이 60초 허용)
2. `cid` = 경로의 아이, `sub` = 요청한 사용자, `src` = 요청한 사람의 역할(보호자 `HOME`, 선생님 `INSTITUTION`), `jti`는 UUID
3. `obs`([ai-observation.md 6장](ai-observation.md#6-관찰-기록-객체-obs)): 태그는 목록의 값만 0~3개, `summary` 300자 이하, `moments` 0~4개(`note` 120자 이하, `t` 0~86400초), `peopleCount` 0~3. `mv`(100자 이하)·`pv`(40자 이하)·`fc`(1~6)
4. 문장 검사를 한 번 더 한다: 걸린 `summary`는 `null`로, 걸린 `moment`는 빼고 저장한다(이 경우는 거절이 아니다). 그 뒤 남길 것이 하나도 없으면 거절한다

그 밖의 응답: 같은 `jti`를 다시 쓰면 409 `DUPLICATE_REQUEST`. 저장 시점에 분석 토큰 발급 조건(동의·기관 촬영 허용)을 다시 확인하므로, 그 사이 동의가 철회됐으면 P는 422 `AI_CONSENT_REQUIRED`, T는 404. AI 기능이 꺼져 있으면 503 `AI_UNAVAILABLE`.

```json
// POST /children/7/ai-observations → 201 (가정 기록)
{
  "observation": {
    "id": 12, "source": "HOME", "recordedAt": "2026-11-13T02:05:00Z",
    "summary": "어른과 아이가 블록을 사이에 두고 마주 앉아 있어요. 아이가 블록을 밀어내자 어른이 잠시 기다린 뒤 말을 걸어요.",
    "childStates": ["PLAYING_TOGETHER", "REFUSING"],
    "adultResponses": ["WAITING", "TALKING"],
    "moments": [ { "t": 12.5, "note": "아이가 블록을 밀어내요." } ],
    "peopleCount": 2,
    "modelVersion": "qwen3.5:4b", "promptVersion": "obs-v1", "frameCount": 6,
    "authorName": null, "mine": true, "createdAt": "2026-11-13T02:06:10.120Z"
  },
  "coaching": {
    "delivery": { "id": 91, "deliveredAt": "...", "triedAt": null,
                  "card": { "id": 17, "title": "...", "body": "...", "situation": "ANY", "metricFocus": "HARD_MOMENT",
                            "evidenceTag": "...", "sourceTitle": "...", "sourceUrl": "...", "author": "...", "reviewer": null } },
    "guidance": null
  }
}
```

- `summary`는 없을 수 있다(`null`). `authorName`은 기관 기록을 만든 선생님의 표시명이고 가정 기록에서는 `null`이다(선생님에게 보호자 이름을 싣지 않는다). `mine`은 지금 요청한 사람이 만든 기록인지(선생님 화면의 삭제 버튼에 쓴다).
- 가정 기록(`HOME`)은 `coaching.delivery`에 카드 1장이 담긴다. 초점은 아이 모습 태그로 정한다([SPEC 6.5](SPEC.md#65-ai-관찰-기록가정의-카드)). `guidance`는 항상 `null`이다. 활성 카드가 한 장도 없을 때만 `delivery`도 `null`.
- 기관 기록(`INSTITUTION`)은 `"coaching": null`.
- 목록(`GET`)의 항목은 위 `observation`과 같은 모양의 배열이다.
