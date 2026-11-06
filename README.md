# hamkke-api — 함께자람 백엔드

계정·동의·아동·지표·코칭 카드·공유·교사 연계 API. Java 21 · Spring Boot 3.5 · Spring Data JPA · Spring Security(JWT) · Flyway · MySQL 8.

> 본 서비스는 의료기기가 아니며 발달 상태를 판단하지 않습니다. 걱정되는 점은 전문의와 상담하세요.

## 문서

이 저장소가 팀 공통 문서를 가진다.

- [개발 명세서](docs/SPEC.md) — 범위, 원칙→구현 규칙, 요구사항 ID, 데이터 모델, 카드 선택 규칙, 보안
- [API 명세](docs/api.md)
- [배포 가이드](docs/deploy.md)
- 지표 정의서는 AI 저장소: [hamkke-ai/docs/metrics.md](https://github.com/hamkke-jaram/hamkke-ai/blob/main/docs/metrics.md)

| 저장소 | 역할 |
|---|---|
| [hamkke-api](https://github.com/hamkke-jaram/hamkke-api) | 백엔드 API, 공통 문서, 배포 구성 |
| [hamkke-web](https://github.com/hamkke-jaram/hamkke-web) | 부모 앱·교사 화면(모바일 웹) |
| [hamkke-ai](https://github.com/hamkke-jaram/hamkke-ai) | 기기 내 분석 패키지, 지표 정의서, 로컬 평가 |

## 실행

```bash
./gradlew bootRun     # local 프로필: H2 파일 DB, 도커 불필요 → http://localhost:8080/swagger-ui.html
./gradlew test        # 단위 + 통합(전체 흐름·권한·동의·업로드 거부)
node scripts/check-expressions.mjs
```

## 지켜야 할 원칙 (요약)

- 원본 영상·음성은 받지 않는다 — 파일 업로드 API 없음(multipart·미디어 요청 415, 64KB 초과 413).
- 아동 정보는 법정대리인 동의 없이 저장하지 않는다(422).
- 가정 기록은 기본 비공개. 교사는 부모가 켠 항목만 주 단위로 본다.
- 상담·기관 안내는 로그인 없이 제공한다.
- 코칭 카드는 사람이 쓴 카드만 전달하고 출처가 필수다.

## 배포

`deploy/docker-compose.yml` — nginx(hamkke-web) + api + mysql. hamkke-api와 hamkke-web을 같은 상위 폴더에 클론한 뒤 [배포 가이드](docs/deploy.md)를 따른다.
