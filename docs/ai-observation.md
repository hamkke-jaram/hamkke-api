# AI 관찰 기록 명세 v1 (VL 분석)

2026-11-13 결정. 창업 해커톤 방향에 맞춰 **다루기 어려운 순간이 잦은 아이를 가정과 기관이 함께 돌보도록** 범위를 넓힌다.
이 문서는 세 저장소(hamkke-api, hamkke-ai, hamkke-web)가 지켜야 할 약속이다. 기존 명세(SPEC.md)와 다른 부분은 이 문서가 우선한다.

## 1. 무엇이 바뀌나

| 항목 | 지금까지 | 이제부터 |
|---|---|---|
| 영상 | 기기 밖으로 나가지 않음 | **영상 원본과 소리는 여전히 기기 밖으로 나가지 않는다.** 보호자가 'AI 관찰 기록'에 동의한 아이에 한해 **장면 사진 최대 6장**을 AI 서버로 보낸다. |
| 분석 | 기기 안 지표 3개 | 지표 3개(기기 안, 그대로) + **VL 모델이 쓴 관찰 기록**(AI 서버) |
| 카메라 | 폰 촬영 영상 | 폰 촬영 영상 + **실시간 카메라**(폰·태블릿·웹캠) |
| 기관 | 어린이집 교사가 목표·메모 작성 | 기관 선생님(어린이집·유치원·센터 등)이 **보호자가 허락한 아이**를 기관에서 촬영해 관찰 기록을 남길 수 있음 |
| 조언 | 전문가 카드만 | 그대로. **VL은 본 것을 적기만 하고 조언 문장을 만들지 않는다.** 조언은 카드에서 고른다. |

바뀌지 않는 것: 의료기기가 아님(주의 문구 고정), 금지 표현 규칙, 실명·주민번호 미수집, 기본 비공개, 안전 기능 무료, 기존 CCTV 미연동, hamkke-api는 파일·이미지를 받지 않음(415/413 그대로).

## 2. 원칙 → 구현 규칙

1. **동의 없이는 한 장도 보내지 않는다.** 아이별 동의 `CHILD_IMAGE_AI_ANALYSIS`(법정대리인)가 있어야 분석 토큰이 발급된다. 동의 화면은 무엇이(장면 사진 최대 6장, 소리 없음) 어디로(AI 서버) 가고 언제 지워지는지(분석 직후) 적는다.
2. **AI 서버는 사진을 저장하지 않는다.** 메모리에서만 다루고 디스크·로그에 남기지 않는다. 응답 뒤 즉시 버린다. 로그에는 사진도 관찰 문장도 남기지 않는다(요청 id, 장수, 걸린 시간, 결과 코드만).
3. **hamkke-api는 글자만 받는다.** 관찰 기록은 AI 서버가 서명한 영수증(JWS)으로만 저장된다. 사용자가 문장을 직접 써서 넣을 수 없다.
4. **VL은 관찰만 한다.** 정해진 태그 목록에서 고르고, 본 것을 짧게 적는다. 아이를 평가·분류하는 말, 원인 추정, 조언, 금지 표현이 나오면 그 문장은 버린다.
5. **기관 촬영은 보호자가 켠 경우에만.** 공유 설정 `institutionCapture`가 켜져 있고 AI 동의가 있을 때만 연결된 선생님이 토큰을 받을 수 있다. 화면에 다른 아이가 함께 찍히지 않게 안내한다.
6. **동의 철회 = 삭제.** AI 동의를 철회하면 그 아이의 AI 관찰 기록이 모두 지워진다. 아이 삭제·탈퇴 시에도 지워진다.
7. **기능이 꺼져 있어도 앱은 동작한다.** AI 서버가 설정되지 않았거나 응답하지 않으면 지표 3개 흐름은 그대로 되고, 관찰 기록만 건너뛴다.

## 3. 흐름

```
[웹] 영상 파일 또는 실시간 카메라
  ├─ (기존) 기기 안 분석 → 지표 3개 → POST /api/v1/children/{id}/interaction-records
  └─ (AI 동의가 있을 때)
       1) 장면 사진 뽑기: 최대 6장, 긴 변 512px JPEG, 소리 없음
       2) POST /api/v1/children/{id}/ai-sessions        → { token, expiresAt, maxFrames }
       3) POST /ai/v1/analyze  (Bearer token, 사진)      → { observation, receipt, ... }   ← hamkke-ai 서버
       4) POST /api/v1/children/{id}/ai-observations { receipt }  → 가정 기록은 교사 검토 대기로 저장
```

웹은 AI 서버를 같은 출처의 `/ai/` 경로로 부른다(nginx·vite 프록시). `/ai/`만 본문 2MB까지 허용하고 `/api/`는 64KB 그대로다.

## 4. 토큰과 영수증 (hamkke-api ↔ hamkke-ai)

둘 다 HS256 JWT. 공유 비밀 `AI_SHARED_SECRET`(32바이트 이상, 사용자 로그인 JWT 비밀과 다른 값).

**분석 토큰** (api가 발급, ai가 검증) — 유효 10분, 한 번의 분석용. api는 `app.ai.session-ttl`이 10분을 넘으면 기동하지 않고, ai는 수명(`exp - iat`)이 10분(반올림 여유 1분)을 넘는 토큰을 받지 않는다.

| 클레임 | 값 |
|---|---|
| `iss` | `hamkke-api` |
| `aud` | `hamkke-ai` |
| `sub` | 사용자 id (문자열) |
| `cid` | 아이 id (숫자) |
| `src` | `HOME`(보호자) 또는 `INSTITUTION`(선생님) |
| `jti` | UUID |
| `iat`, `exp` | 발급·만료 |

**영수증** (ai가 발급, api가 검증) — 유효 15분

| 클레임 | 값 |
|---|---|
| `iss` | `hamkke-ai` |
| `aud` | `hamkke-api` |
| `sub`, `cid`, `src`, `jti` | 분석 토큰의 값을 그대로 |
| `obs` | 관찰 기록 객체(6장) |
| `mv` | 모델 버전 (예: `qwen3.5:4b`) |
| `pv` | 프롬프트 버전 (예: `obs-v1`) |
| `fc` | 분석한 사진 장수 |
| `iat`, `exp` | 발급·만료 |

api는 영수증의 서명·`aud`·만료, `cid`가 경로의 아이와 같은지, `sub`가 요청한 사용자와 같은지, `jti`가 처음 쓰이는지(DB unique)를 확인한다.

**영수증 하나는 한 번만 저장된다 — 그 영수증으로 만든 기록을 지운 뒤에도.** api는 저장에 쓴 `jti`를 관찰 기록과 따로, 그 영수증이 더는 통하지 않는 시각(`exp` + 시계 차이 허용 60초)까지 남겨 둔다(`ai_used_receipts`, V6). 그래서 보호자가 기록을 지웠거나 AI 동의를 철회해 기록이 지워진 뒤에도, 아직 유효한 같은 영수증으로 다시 저장할 수 없다(409 `DUPLICATE_REQUEST`). 이 표에는 `jti`와 만료 시각만 있고 아이·사용자를 가리키는 값은 없으므로, 철회·삭제 뒤에 그 아이에 대한 것은 남지 않는다. 만료된 행은 매일 04:47 작업이 지운다. 거절된 저장(예: 촬영 시각이 미래)은 영수증을 쓴 것으로 치지 않으므로 같은 영수증으로 다시 보낼 수 있다.

## 5. AI 서버 API (hamkke-ai `server/`)

`POST /ai/v1/analyze` — `Authorization: Bearer <분석 토큰>`, `Content-Type: application/json`

```json
{ "frames": [ { "t": 12.5, "jpeg": "<base64>" } ], "durationSec": 95 }
```

- 사진 1~6장, 한 장 디코딩 크기 300KB 이하, 본문 전체 2MB 이하, JPEG만(매직 바이트 확인).
- 같은 `jti`는 한 번만 처리한다(메모리에서 만료 시각까지 기억).
- 동시 처리 수 제한(기본 1), 대기열 초과 시 429. 모델 제한 시간(기본 180초) 초과 시 504.

응답 200

```json
{
  "observation": { ... 6장 ... },
  "receipt": "<JWS>",
  "modelVersion": "qwen3.5:4b",
  "promptVersion": "obs-v1",
  "analyzedFrames": 6
}
```

오류: `{ "code": "...", "message": "한국어 문장" }` — 400 `INVALID_FRAMES`, 401 `INVALID_TOKEN`, 409 `SESSION_ALREADY_USED`, 413 `PAYLOAD_TOO_LARGE`, 429 `BUSY`, 502 `MODEL_UNAVAILABLE`, 504 `MODEL_TIMEOUT`.

`GET /ai/v1/health` → `{ "status": "ok", "model": "...", "modelReady": true|false }` (인증 없음, 사진·문장 없음)
- 기본은 언제나 200(서버가 살아 있는지). `?strict=1`이면 모델을 쓸 수 없을 때 503 `{ "status": "unavailable", ... }`(가동 감시·촬영 전 확인용).
- 대신 쓰는 모델(`VL_FALLBACK_MODEL`)을 정했으면 `fallbackModel`이 붙고, `modelReady`는 둘 가운데 하나라도 쓸 수 있으면 `true`.

`GET /ai/v1/metrics` → Prometheus 글 형식의 운영 지표(요청 수·결과 코드·처리 시간·대기열·모델 준비 상태). 사진·문장·토큰·jti·식별 값은 없다. nginx가 밖에서 막고(404), `METRICS_TOKEN`을 정하면 Bearer 토큰이 있어야 한다. 지표 목록은 hamkke-ai `docs/observation.md` 3장.

모델 제공자는 갈아 끼울 수 있게 인터페이스로 둔다. v1 구현은 Ollama(`OLLAMA_URL`, `VL_MODEL`)다. 첫 모델을 쓸 수 없을 때(연결 실패·모델 없음 등)만 대신 쓰는 모델로 넘어갈 수 있고, 그때 응답 `modelVersion`과 영수증 `mv`에는 **실제로 기록을 쓴 모델 이름**이 들어간다(api는 그 값을 그대로 저장한다).

## 6. 관찰 기록 객체 (`obs`)

```json
{
  "summary": "어른과 아이가 블록을 사이에 두고 마주 앉아 있어요. 아이가 블록을 밀어내자 어른이 잠시 기다린 뒤 말을 걸어요.",
  "childStates": ["PLAYING_TOGETHER", "REFUSING"],
  "adultResponses": ["WAITING", "TALKING"],
  "moments": [ { "t": 12.5, "note": "아이가 블록을 밀어내요." } ],
  "peopleCount": 2
}
```

| 필드 | 규칙 |
|---|---|
| `summary` | 한국어 해요체, 1~3문장, 300자 이하. 본 것만. 없으면 `null` |
| `childStates` | 아래 목록에서 0~3개 |
| `adultResponses` | 아래 목록에서 0~3개 |
| `moments` | 0~4개. `t`는 보낸 사진의 시각(초), `note`는 120자 이하 |
| `peopleCount` | 0~3 (3은 '3명 이상') |
| `childLine` | (있을 수도 없을 수도) 선생님용 아이 한 줄. '아이가/아이는…'으로 시작하는 해요체 한 문장, 40자 이하, 숫자 없음. 검사에 걸리면 그 줄만 뺀다. 아무도 없으면 없다 |
| `parentLine` | (있을 수도 없을 수도) 선생님용 어른 한 줄. '어른이/어른은…'으로 시작, 규칙은 `childLine`과 같고 어른을 평가하지 않는다. 사람이 둘 미만이면 없다 |

선생님용 두 줄(`childLine`, `parentLine`)은 2026-11-20에 더했다(프롬프트 `obs-v6.4-lines`). 사진을 다시 확인하는 단계가 요약이나 태그를 바꾸면 AI 서버가 두 줄을 뺀다(처음 쓴 줄이 고친 결과와 어긋날 수 있다). 모양이 어긋나면(문자열이 아님, 40자 초과) hamkke-api가 영수증 전체를 받지 않고, 문장 검사에 걸리면 그 줄만 버린다. 두 줄이 없는 예전 영수증도 그대로 받는다.

선생님에게는 원본 대신 줄인 형태(`GET /children/{id}/home-scenes`: 날짜, 태그, 두 줄)만 간다. 보호자가 'AI 관찰 기록' 공유를 켜고 AI 동의가 살아 있을 때만 열리며, 보호자가 기록마다 "선생님에게 보내지 않기"(`PUT /children/{id}/ai-observations/{oid}/teacher-visibility`)를 고르면 그 기록은 선생님 화면 어디에도(장면 목록, 관찰 목록, 검토함) 나오지 않는다.

**아이 모습 태그** (`childStates`)

| 값 | 화면 표시 |
|---|---|
| `PLAYING_TOGETHER` | 어른과 함께 놀아요 |
| `PLAYING_ALONE` | 혼자 놀아요 |
| `FOCUSED` | 한 가지에 푹 빠져 있어요 |
| `MOVING_AROUND` | 자리를 자주 옮겨요 |
| `SEEKING_ADULT` | 어른에게 다가가요 |
| `UPSET_CRYING` | 울거나 속상해해요 |
| `REFUSING` | 싫다고 표현해요 |
| `THROWING_OR_HITTING` | 물건을 던지거나 쳐요 |
| `CALM` | 차분해요 |

**어른 반응 태그** (`adultResponses`)

| 값 | 화면 표시 |
|---|---|
| `JOINING_PLAY` | 놀이에 함께해요 |
| `TALKING` | 말을 걸어 줘요 |
| `WAITING` | 기다려 줘요 |
| `COMFORTING` | 안아 주거나 달래 줘요 |
| `REDIRECTING` | 다른 놀이로 이끌어요 |
| `SETTING_LIMIT` | 안 되는 것을 알려 줘요 |

**문장 검사** (AI 서버가 하고, api가 저장 전에 한 번 더 한다)

- 걸린 `summary`·`note`는 고쳐 쓰지 않고 그 글 전체를 버린다(`summary`는 `null`, 해당 `moment`는 제거). 태그는 남는다.
- **먼저 글자를 고르게 맞춘다.** 순서는 ① NFKC(전각 숫자·영문 → 반각, 풀어 쓴 한글 자모 → 완성형, 한글 채움 문자 → 한 글자로 모음) → ② 보이지 않는 글자를 **지운다**(형식 문자 전체: 폭 없는 공백·이음표, BOM, 부드러운 하이픈, 방향 표시 등. 그리고 결합 문자 이음표, 한글 채움 문자, 이형 선택자). 공백으로 바꾸면 낱말 사이에 끼워 넣어 검사를 비켜 갈 수 있어서 지운다. ①에서 한 글자로 모인 채움 문자가 ②에서 지워지므로 이 순서여야 한다.
  - 검사는 맞춘 글로 하고, **저장하는 글도 맞춘 글이다.** AI 서버는 맞춘 글을 영수증에 넣고, api는 받은 글을 같은 방식으로 한 번 더 맞춘다(이미 맞춘 글은 바뀌지 않는다). 검사한 글과 화면에 보이는 글이 달라지지 않게 하려는 것이다.
  - 위 표의 길이 제한은 맞춘 글로 잰다.
- 금지 표현(각 저장소 `scripts/check-expressions.mjs`와 같은 목록)이 들어간 글은 버린다.
- 평가·분류·원인 추정·조언처럼 읽히는 말도 버린다: `문제`, `장애`, `이상`, `정상`, `비정상`, `지연`, `부족`, `산만`, `공격`, `과잉`, `결핍`, `의심`, `해야`, `하세요`, `바랍니다`, `권장`.
- 위 낱말만으로는 지나가는 글을 아래 갈래의 식(어미·형태)으로 버린다. 사진 몇 장으로 알 수 없는 것을 말하거나 아이를 가려내는 말로 읽히는 글이다. 2026-11에 넓혔고, **버리는 쪽으로만** 넓혔다. 맞는 관찰 문장이 일부 버려지는 것을 받아들였다.

  | 갈래 | 걸리는 예 |
  |---|---|
  | 조언·부탁 | '안아 주세요', '달래 주면 좋겠어요', 어른 높임말('앉아 계세요') |
  | 속마음·까닭 짐작 | '~ 때문이에요', '~ 것 같아요', '졸린가 봐요', '졸려 보여요', '화가 난 듯', '심심해서 돌아다녀요', '피곤한지 눈을 비벼요', '나가고 싶어 해요' |
  | 발달·또래 견주기, 아이에게 이름 붙이기 | '발달이 느려 보여요', '또래보다 말이 늦어요', 'ADHD처럼 보여요', '눈을 맞추지 않아요', '조용한 편이에요', '고집이 세요' |
  | 평가하는 말 | '버릇없이', '집중을 못 해요', '떼를 쓰며', '말을 안 듣고', '휴대폰만 보고', '잘 쌓아요', '혼내요' |
  | 사람을 가리키는 말 | '엄마가', '선생님이', '친구와', '남자아이', '두세 살쯤', '돌쯤', '3살', '18개월', 생김새를 평가하는 말 |
  | 사진 속 글자 읽기 | '민준이라고 적혀 있어요', '이름표', 따옴표로 옮긴 말 |

  - 다른 낱말 속에 든 글자는 되도록 건드리지 않게 좁혔다: '인형', '딸기', '양말', '통과해요', '평균대', '아기 인형', '돌을 쌓아요', '보여 줘요'는 지나간다.
  - 그래도 버려지는 맞는 문장이 있다. 예: '반듯하게 앉아 있어요', '손이 닿지 못해', '상자보다 큰 인형', '꼬마 자동차', '블록을 높게 쌓아 보여요'.
- **두 검사는 같은 폭이다.** 식은 hamkke-ai `server/src/wording.ts`와 hamkke-api `ObservationSentenceFilter`가 같게 유지한다(두 번째 검사도 첫 검사와 같은 폭이어야, 다른 서버가 서명한 영수증이 와도 걸러진다).
  - **공용 예문 파일**: 걸려야 하는 문장과 지나가야 하는 문장을 모은 hamkke-ai `server/test/wording-cases.json`이 원본이고, hamkke-api는 `src/test/resources/wording-cases.json`에 바이트 단위로 같은 사본을 둔다. 두 저장소의 테스트가 이 파일의 모든 문장에 기대한 결과가 나오는지 확인한다(hamkke-ai `server/test/wording.test.ts`, hamkke-api `ObservationWordingCasesTest`). api 쪽은 사본의 SHA-256도 확인하고, 두 저장소가 나란히 있으면 원본과 직접 견준다.
  - 식을 고칠 때는 원본 예문 파일, 사본과 그 해시, 두 저장소의 식을 함께 고친다. 파일은 LF·BOM 없음이고, 보이지 않는 글자와 풀어 쓴 자모는 JSON 이스케이프로 적는다.
- 사람 이름·나이·외모 평가를 쓰지 않게 프롬프트에서 막는다(표지 없는 이름은 식으로 잡지 못한다).
- 태그 목록에 없는 값은 버린다. 모델 출력이 JSON이 아니거나 모두 버려지면 한 번 다시 시도하고, 그래도 안 되면 태그 없이 `summary: null`로 응답하지 않고 502 `MODEL_UNAVAILABLE`로 응답한다.

## 7. hamkke-api 변경

**동의**
- `ConsentType.CHILD_IMAGE_AI_ANALYSIS` (아이별). 정책 버전 `app.policy.ai-analysis-version` → `GET /policies`에 `aiAnalysisVersion`, `aiObservationEnabled`(AI 서버 설정 여부) 추가.
- `GET  /children/{id}/ai-consent` → `{ agreed, version, agreedAt }` (P own)
- `PUT  /children/{id}/ai-consent` `{ agreed: true, version }` → 동의 기록 (P own). 버전이 다르면 422 `AI_CONSENT_VERSION_MISMATCH`.
- `DELETE /children/{id}/ai-consent` → 철회 + 그 아이의 AI 관찰 기록 전부 삭제 (P own)

**공유 설정** — `ShareSetting`에 두 항목 추가(기본 false)
- `aiObservations`: 가정에서 만든 관찰 기록을 선생님에게 보임
- `institutionCapture`: 연결된 선생님이 기관에서 촬영·분석할 수 있음

**분석 토큰**
- `POST /children/{id}/ai-sessions` → `{ token, expiresAt, maxFrames }`
  - P(own): AI 동의 필요, 없으면 422 `AI_CONSENT_REQUIRED`
  - T(linked): AI 동의 + `institutionCapture` 필요, 없으면 404(존재를 드러내지 않음)
  - 사용자당 시간당 20회 제한(429). AI 기능이 꺼져 있으면 503 `AI_UNAVAILABLE`.

**관찰 기록**
- `POST /children/{id}/ai-observations` `{ receipt, recordedAt? }` → `{ observation, coaching }`
  - 영수증 검증(4장) 후 저장. 새 가정 기록은 `PENDING`으로 저장하며, `coaching`은 `null`이다.
  - 중복 `jti`는 409 `DUPLICATE_REQUEST`. 그 영수증으로 만든 기록을 지운 뒤에 다시 보내도 같다(4장).
- `GET /children/{id}/ai-observations?limit=20` → 목록(최신순)
  - P(own): 전부. T(linked): `INSTITUTION` 기록은 항상, `HOME` 기록은 `aiObservations`가 켜진 경우만.
- `DELETE /children/{id}/ai-observations/{obsId}` — P(own) 전부, T는 자기가 만든 기록만.
- 저장 항목: 아이, 작성자, 출처(HOME/INSTITUTION), 기록 시각, summary, 태그 두 종류, moments, peopleCount, 모델·프롬프트 버전, 사진 장수, jti(unique). **사진은 없다.**
- 쓴 영수증(`ai_used_receipts`, V6): `jti`와 만료 시각만. 관찰 기록이 지워져도 이 행은 만료될 때까지 남는다(아이·사용자·문장은 없다).

**가정 기록 교사 검토**

연결된 선생님은 보호자가 AI 관찰 공유를 켠 경우 장면별 문장을 검토한다. PC AI는 검토한 상황에 맞는 자료를 찾아 행동 방식과 근거를 제안한다. 교사가 전달할 항목을 골라 게시하면 보호자 목록에 검토 결과가 표시된다. 적합한 근거가 없으면 추천 없이 요약만 전달할 수 있다. 원래 분석 영수증에는 이 추천이 들어가지 않는다. 요청·응답 형식은 [API 문서](api.md#관찰-기록-저장)에 있다.

## 8. hamkke-web 변경

- **동의 화면**: 아이 홈에서 'AI 관찰 기록 켜기' → 동의 내용(무엇이, 어디로, 언제 지워지는지, 언제든 끌 수 있고 끄면 기록이 지워짐) → 체크 후 켜기. 끄기는 확인 한 번.
- **촬영 영상**: 기록 화면에서 기기 안 분석이 끝난 뒤, AI 동의가 있으면 같은 영상에서 사진 6장을 뽑아 관찰 기록을 만든다. 실패해도 지표 기록은 남는다.
- **실시간 카메라**: `/p/children/:id/observe`, `/t/children/:id/observe`. 카메라 고르기(앞·뒤·연결된 카메라), 미리보기, 시작하면 정해진 간격으로 6장을 찍고(기본 60초 동안) 분석. 영상은 녹화·저장하지 않는다. 화면에 '지금 장면 사진을 찍고 있어요' 표시를 계속 보여 준다.
- **결과**: 새 가정 기록은 교사 검토 상태와 게시된 요약·추천 근거를 표시한다. 'AI가 화면을 보고 적은 기록이에요. 틀릴 수 있어요.' 문구를 붙인다.
- **목록**: 아이 홈과 선생님 화면에 'AI 관찰 기록' 목록(출처 표시: 가정 / 기관), 삭제.
- **공유 설정**: 두 항목 추가. **개인정보 안내·첫 화면·촬영 안내 문구**를 바뀐 사실대로 고친다('영상은 폰 밖으로 나가지 않아요'는 영상 원본에 대해서만 맞는 말이 됨).
- 가입 화면의 '어린이집 교사'는 '기관 선생님'으로 넓힌다.

## 9. 배포

- `deploy/docker-compose.yml`에 `ai` 서비스 추가(hamkke-ai `server/`), nginx `/ai/` 프록시(본문 2MB, 읽기 제한 시간 200초).
- 환경 변수: `AI_SHARED_SECRET`(api·ai 공통), `AI_ENABLED`, `OLLAMA_URL`, `VL_MODEL`, `VL_MAX_CONCURRENCY`, `VL_TIMEOUT_MS`. 그 밖의 ai 서버 변수(`VL_MAX_WAITING`, `VL_MAX_WAIT_MS`, `VL_KEEP_ALIVE`, `VL_NUM_CTX`, `VL_MAX_OUTPUT_TOKENS`, `VL_WARMUP`, `VL_FALLBACK_MODEL`, `VL_FALLBACK_URL`, `OLLAMA_AUTH_TOKEN`, `OLLAMA_AUTH_HEADER`, `OLLAMA_ALLOW_INSECURE`, `METRICS_TOKEN`)는 비워 두면 기본값을 쓰고, compose가 모두 넘긴다. 표는 [deploy.md 2.1절](deploy.md#21-ai-관찰-기록-켜기-선택)과 hamkke-ai `docs/observation.md` 2장.
- AI 서버와 모델 서버 사이에도 장면 사진이 지나간다. 모델 서버가 바깥 주소면 https(TLS 프록시 + `OLLAMA_AUTH_TOKEN`)로 잇는다. 바깥 주소를 평문 http로 가리키면 ai 서버가 기동하지 않는다(`OLLAMA_ALLOW_INSECURE=true`로만 풀린다).
- 컨테이너 `HEALTHCHECK`는 살아 있는지만 본다. 모델 준비 여부는 `/ai/v1/health?strict=1`이나 지표로 따로 감시한다. 모델은 뜰 때 미리 올리고(`VL_WARMUP`), `VL_KEEP_ALIVE=30m`으로 오래 둔다.
- VL 모델은 GPU가 있는 기기에서 돌려야 한다. GPU 없는 EC2에서는 매우 느리므로, 모델 서버를 따로 두거나 다른 제공자 구현을 추가한다(인터페이스는 준비됨).

## 10. 아직 확인이 필요한 것 (코드로 해결되지 않음)

- 아동 얼굴이 담긴 사진을 서버로 보내 분석하는 것의 법률 검토(개인정보보호법상 민감정보·아동 정보, 처리 위탁 고지).
- 기관에서 다른 아이·교직원이 함께 찍히는 경우의 동의. v1은 안내 문구로만 막는다.
- VL이 쓴 문장의 품질: 틀린 기록이 나올 수 있다. 연출 영상으로 사람이 본 것과 비교해 봐야 한다. 비교 도구는 준비됨(hamkke-ai `server/eval/`, `npm run eval:vl`: 사람 라벨과 태그 일치·놓침·더함 수, 문장 검사에 걸린 글 수). 연출 영상과 라벨을 만들어 실제로 돌려 보는 일이 남았다. 결과는 내부 개선용이고 퍼센트 수치로 알리지 않는다.
- 실시간 감지·알림은 만들지 않는다(가려내기처럼 보일 수 있음).
