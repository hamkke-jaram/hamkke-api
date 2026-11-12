# API 명세 v1

- 기본 경로: `/api/v1`, 본문 `application/json; charset=utf-8`, 최대 64KB
- 인증: `Authorization: Bearer <accessToken>` (🔓 = 인증 불필요)
- 권한 표기: **P** 부모, **T** 교사, **P(own)** 본인 아동의 부모, **T(linked)** 연결된 교사
- 에러 형식·코드: [SPEC.md 9장](SPEC.md#9-에러-형식) · 요구사항 ID: [requirements.md](requirements.md)
- 목록 조회의 `limit`·`weeks`는 범위를 벗어나면 가장 가까운 허용값으로 맞춘다(에러 아님).
- 실행 중인 서버에서 `/swagger-ui.html`로 같은 내용을 볼 수 있다(운영에서는 비활성).

## 1. 공개

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET 🔓 | `/policies` | 현재 동의 문서 버전, 주의 문구 |
| GET 🔓 | `/support-resources` | 상담·기관 연결 안내 목록(`sort_order` 순, 1시간 캐시) |

```json
// GET /policies
{ "termsVersion": "2026-11-v1", "privacyVersion": "2026-11-v1", "guardianVersion": "2026-11-v1",
  "disclaimer": "본 서비스는 의료기기가 아니며 발달 상태를 판단하지 않습니다. 걱정되는 점은 전문의와 상담하세요." }
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
| GET | `/me` | P, T | 내 정보 `{ id, email, displayName, role }` |
| DELETE | `/me` | P, T | 탈퇴(즉시 삭제). 본문 `{ "password": "..." }` → 204. 비밀번호가 다르면 401 `INVALID_CREDENTIALS` |
| GET | `/me/consents` | P, T | 내 동의 이력 `[ { type, version, childId, agreedAt, revokedAt } ]` (최신순) |

```json
// POST /auth/signup
{ "email": "parent@example.com", "password": "abcd1234", "displayName": "하늘맘", "role": "PARENT",
  "consents": [ { "type": "TERMS_OF_SERVICE", "version": "2026-11-v1" },
                { "type": "PRIVACY_POLICY",   "version": "2026-11-v1" } ] }
// 201
{ "accessToken": "eyJ...", "accessTokenExpiresIn": 1800, "refreshToken": "q9X...", 
  "user": { "id": 1, "email": "parent@example.com", "displayName": "하늘맘", "role": "PARENT" } }
```

`/auth/login` 요청 `{ email, password }`, `/auth/refresh`·`/auth/logout` 요청 `{ refreshToken }` — 응답은 signup과 같은 토큰 묶음(logout은 204).

## 3. 아동 (부모)

| 메서드 | 경로 | 권한 | 설명 |
|---|---|---|---|
| POST | `/children` | P | 등록(법정대리인 동의 필수) |
| GET | `/children` | P | 내 아동 목록 |
| GET | `/children/{id}` | P(own) | 상세 |
| PATCH | `/children/{id}` | P(own) | 별칭·생년월 수정 `{ nickname?, birthYearMonth? }` — 보낸 항목만 바뀐다 |
| DELETE | `/children/{id}` | P(own) | 삭제 = 동의 철회, 관련 데이터 삭제 → 204 |

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
| POST | `/children/{id}/interaction-records` | P(own) | 지표 저장 + 추이 + 카드 → 201(새 기록) / 200(재전송) |
| GET | `/children/{id}/interaction-records?limit=20` | P(own) | 최근 기록 목록(촬영 시각 최신순, `limit` 1~100). 항목은 아래 `record`와 같은 모양 |
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
| GET | `/children/{id}/card-deliveries?limit=20` | P(own), T(linked, 카드 공유 켜짐) | 받은 카드 이력(최신순, `limit` 1~50). 항목은 위 `coaching.delivery`와 같은 모양 |
| POST | `/children/{id}/card-deliveries/{deliveryId}/tried` | P(own) | "해봤어요" 표시 → 갱신된 전달 항목(이미 표시했으면 처음 시각 유지) |

## 6. 공유 설정·초대

| 메서드 | 경로 | 권한 | 설명 |
|---|---|---|---|
| GET | `/children/{id}/share-settings` | P(own) | 공유 설정 `{ jointPlay, responseLatency, distress, coachingCards, updatedAt }` |
| PUT | `/children/{id}/share-settings` | P(own) | `{ jointPlay, responseLatency, distress, coachingCards }`(네 값 모두 필수) → 공유 설정 |
| POST | `/children/{id}/invite-codes` | P(own) | 초대 코드 발급 → 201 `{ code, expiresAt }`(72시간). 유효 코드가 3개면 422 `INVITE_LIMIT_REACHED` |
| GET | `/children/{id}/teachers` | P(own) | 연결된 교사 `[ { teacherId, displayName, linkedAt } ]` |
| DELETE | `/children/{id}/teachers/{teacherId}` | P(own) | 연결 해제 → 204 |

## 7. 교사

| 메서드 | 경로 | 권한 | 설명 |
|---|---|---|---|
| POST | `/teacher/links` | T | `{ code }` → 201 연결된 아동 `{ id, nickname, ageMonths, linkedAt }`. 공백·`-`·대소문자는 무시. 만료·사용됨·없는 코드는 모두 422 `INVITE_CODE_INVALID`, IP 기준 요청 제한 |
| GET | `/teacher/children` | T | 연결된 아동 목록 `[ { id, nickname, ageMonths, linkedAt } ]` |
| GET | `/teacher/children/{id}/shared-summary?weeks=8` | T(linked) | 공유된 항목만 담긴 주간 요약(`weeks` 1~26) |

```json
// GET /teacher/children/7/shared-summary  (jointPlay만 공유된 경우)
{ "child": { "id": 7, "nickname": "하늘", "ageMonths": 41 },
  "sharedItems": ["JOINT_PLAY"],
  "weeks": [ { "weekStart": "2026-11-02", "recordCount": 2, "jointPlayRatio": 0.61, "jointPlaySeconds": 110.0 } ] }
```

## 8. 목표·관찰 메모

| 메서드 | 경로 | 권한 | 설명 |
|---|---|---|---|
| GET | `/children/{id}/goals` | P(own), T(linked) | 목표 목록 |
| POST | `/children/{id}/goals` | T(linked) | `{ title(1~60), description(0~500) }` → 201 |
| PATCH | `/children/{id}/goals/{goalId}` | T(작성자) | `{ title?, description?, status? }` — `status`: `IN_PROGRESS`, `ACHIEVED`, `ARCHIVED` |
| DELETE | `/children/{id}/goals/{goalId}` | T(작성자) | → 204 |
| GET | `/children/{id}/observations?limit=50` | P(own), T(linked) | 메모 목록(최신순, `limit` 1~100) |
| POST | `/children/{id}/observations` | T(linked) | `{ content(1~1000), observedOn: "2026-11-06" }` → 201 |
| DELETE | `/children/{id}/observations/{noteId}` | T(작성자) | → 204 |

목표·메모 응답에는 작성 교사 표시명(`teacherName`)이 포함된다.

```json
// 목표
{ "id": 3, "title": "친구 이름 부르기", "description": "...", "status": "IN_PROGRESS", "teacherId": 12,
  "teacherName": "김선생", "createdAt": "...", "updatedAt": "..." }
// 관찰 메모
{ "id": 5, "content": "블록 놀이에 10분 넘게 집중했어요.", "observedOn": "2026-11-05", "teacherId": 12,
  "teacherName": "김선생", "createdAt": "..." }
```
