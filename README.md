# hamkke-api — 함께자람 백엔드

계정·동의·아동·지표·코칭 카드·공유·교사 연계·AI 관찰 기록 API. Java 21 · Spring Boot 3.5 · Spring Data JPA · Spring Security(JWT) · Flyway · MySQL 8.

> 본 서비스는 의료기기가 아니며 발달 상태를 판단하지 않습니다. 걱정되는 점은 전문의와 상담하세요.

## 문서

이 저장소가 팀 공통 문서를 가진다.

- [개발 명세서](docs/SPEC.md) — 범위, 원칙→구현 규칙, 데이터 모델, 카드 선택 규칙·장애 시 대체 규칙, 보안, 에러 코드
- [기능 요구사항(ID)](docs/requirements.md) — ACC·CHD·REC·CRD·SHR·TCH·SUP 요구사항 ID
- [API 명세](docs/api.md)
- [AI 관찰 기록 명세](docs/ai-observation.md) — 세 저장소가 지키는 약속: 동의, 분석 토큰·영수증, 관찰 기록 객체, AI 서버 API
- [배포 가이드](docs/deploy.md)
- 지표 정의서는 AI 저장소: [hamkke-ai/docs/metrics.md](https://github.com/hamkke-jaram/hamkke-ai/blob/main/docs/metrics.md)

| 저장소 | 역할 |
|---|---|
| [hamkke-api](https://github.com/hamkke-jaram/hamkke-api) | 백엔드 API, 공통 문서, 배포 구성 |
| [hamkke-web](https://github.com/hamkke-jaram/hamkke-web) | 부모 앱·교사 화면(모바일 웹) |
| [hamkke-ai](https://github.com/hamkke-jaram/hamkke-ai) | 기기 내 분석 패키지, 지표 정의서, 로컬 평가, AI 관찰 기록 서버(`server/`) |

## 실행

```bash
./gradlew bootRun     # local 프로필: H2 파일 DB, 도커 불필요 → http://localhost:8080/swagger-ui.html
./gradlew test        # 단위(추이·카드 선택·대체 규칙·초대 코드 만료·영수증 검증·문장 검사) + 통합(전체 흐름·권한·동의·업로드 거부·AI 관찰 기록)
node scripts/check-expressions.mjs   # 표현 규칙 검수(src/main·src/test·scripts)
```

### AI 관찰 기록 (로컬)

local 프로필에서는 켜져 있다(`app.ai.enabled=true`, 개발용 공유 비밀 기본값). hamkke-ai의 AI 서버를 같은 기기에서 띄우면(`npm run server`, 8090) 개발용 기본 비밀이 서로 같아 바로 이어진다. AI 서버가 없어도 api는 뜨고 지표 3개 흐름은 그대로 동작한다.

| 설정 | 환경변수 | local 기본 | prod 기본 |
|---|---|---|---|
| `app.ai.enabled` | `AI_ENABLED` | `true` | `false` |
| `app.ai.shared-secret` | `AI_SHARED_SECRET` | 개발용 값 | 없음(켜려면 필수, 32바이트 이상, `JWT_SECRET`과 다른 값) |
| `app.ai.max-frames` | — | 6 | 6 |
| `app.ai.session-ttl` | — | 10m | 10m |
| `app.policy.ai-analysis-version` | — | `2026-11-v1` | `2026-11-v1` |

## 지켜야 할 원칙 (요약)

- 원본 영상·음성은 받지 않는다 — 파일 업로드 API 없음(multipart·미디어 요청 415, 64KB 초과 413). 사진도 받지 않는다.
- AI 관찰 기록은 보호자가 동의한 아이만. 동의 없이는 분석 토큰을 내주지 않고, 관찰 기록은 AI 서버가 서명한 영수증(글자)으로만 저장하며, 동의를 철회하면 그 아이의 AI 관찰 기록을 모두 지운다. 기관 촬영은 보호자가 켠 경우에만.
- VL 모델이 쓴 문장은 저장 전에 한 번 더 거른다(금지 표현, 평가·조언처럼 읽히는 말). 조언은 사람이 쓴 카드에서만 고른다.
- 아동 정보는 법정대리인 동의 없이 저장하지 않는다(422).
- 가정 기록은 기본 비공개. 교사는 부모가 켠 항목만 주 단위로 본다.
- 상담·기관 안내는 로그인 없이 제공한다.
- 코칭 카드는 사람이 쓴 카드만 전달하고 출처가 필수다. 카드 선택이 실패해도 대체 규칙으로 카드를 전달하고 기록 저장은 막지 않는다.
- DB 스키마는 Flyway가 관리한다. 새 변경은 `src/main/resources/db/migration/V{n}__*.sql`로 추가하고 적용된 파일은 고치지 않는다.

## 배포

`deploy/docker-compose.yml` — nginx(hamkke-web) + api + mysql, 그리고 켰을 때만 뜨는 ai(hamkke-ai `server/`). hamkke-api·hamkke-web(·hamkke-ai)을 같은 상위 폴더에 클론한 뒤 [배포 가이드](docs/deploy.md)를 따른다. AI 관찰 기록은 기본값이 꺼짐이며 켜는 방법은 배포 가이드 2.1절에 있다.
