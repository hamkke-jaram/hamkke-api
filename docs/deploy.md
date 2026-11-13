# 배포 가이드 (AWS EC2 + Docker Compose)

## 1. 서버 준비

- EC2: 서울 리전(ap-northeast-2), t3.small 이상, Ubuntu 24.04, 디스크 30GB
- 보안 그룹: 80, 443만 열기(SSH 22는 팀 IP만). MySQL 3306은 열지 않는다.
- Docker Engine + Compose 플러그인 설치
- 도메인 A 레코드 → EC2 탄력적 IP

## 2. 설정

```bash
mkdir -p ~/hamkke && cd ~/hamkke   # 아래 절차는 이 경로(~/hamkke)를 기준으로 한다
git clone https://github.com/hamkke-jaram/hamkke-api.git
git clone https://github.com/hamkke-jaram/hamkke-web.git
git clone https://github.com/hamkke-jaram/hamkke-ai.git   # AI 관찰 기록을 켤 때만 필요(2.1절)
cd hamkke-api/deploy
cp .env.example .env
openssl rand -base64 48   # → JWT_SECRET에 붙여넣기
vi .env                   # SERVER_NAME, DB 비밀번호 채우기
```

### 2.1 AI 관찰 기록 켜기 (선택)

기본값은 **꺼짐**이다. 꺼져 있으면 `ai` 서비스는 뜨지 않고, api는 AI 관련 요청에만 503 `AI_UNAVAILABLE`로 답하며, 지표 3개 흐름은 그대로 동작한다(`/api/v1/policies`의 `aiObservationEnabled`가 `false`라 웹이 그 기능만 숨긴다). 약속 전체는 [ai-observation.md](ai-observation.md).

켜려면 `.env`에 아래 세 값을 **함께** 채운다.

```bash
openssl rand -base64 48   # → AI_SHARED_SECRET 에 붙여넣기 (JWT_SECRET 과 따로 한 번 더 만든다. 같은 값이면 api가 기동하지 않는다)
```

| 변수 | 쓰는 곳 | 설명 |
|---|---|---|
| `AI_ENABLED` | api | `true`로 두면 AI 관찰 기록 API가 열린다. 이때 `AI_SHARED_SECRET`이 없거나 32바이트 미만이거나 `JWT_SECRET`과 같으면 api가 기동하지 않는다 |
| `AI_SHARED_SECRET` | api·ai 공통 | 분석 토큰과 영수증에 서명하는 키. 두 서비스가 같은 값을 써야 한다(compose가 같은 값을 넘긴다) |
| `COMPOSE_PROFILES` | compose | `ai`를 넣으면 `ai` 서비스(hamkke-ai `server/`)가 함께 뜬다 |
| `OLLAMA_URL` | ai | VL 모델을 돌리는 Ollama 주소. 기본값은 이 서버에서 도는 Ollama(`http://host.docker.internal:11434`) |
| `VL_MODEL` | ai | VL 모델 이름(기본 `qwen3.5:4b`). Ollama에 미리 받아 둔다: `ollama pull qwen3.5:4b` |
| `VL_MAX_CONCURRENCY` | ai | 동시에 분석하는 수(기본 1). 넘치면 429 `BUSY` |
| `VL_TIMEOUT_MS` | ai | 모델 제한 시간(기본 180000). nginx의 `/ai/` 읽기 제한 시간(200초)보다 짧게 둔다 |

- **VL 모델은 GPU가 있는 기기에서 돌려야 한다.** GPU 없는 EC2(t3 계열)에서는 한 번 분석에 몇 분씩 걸려 쓸 수 없다. GPU 인스턴스에 Ollama를 띄우거나, 모델 서버를 다른 기기에 두고 `OLLAMA_URL`로 가리킨다. 다른 기기에 둘 때는 그 사이 구간도 사설망·TLS로 보호한다(장면 사진이 지나간다).
- `ai` 서비스는 바깥으로 포트를 열지 않는다. 웹(nginx)의 `/ai/` 프록시로만 닿는다: 본문 2MB까지, 읽기 제한 시간 200초(`/api/`는 64KB 그대로). 설정은 hamkke-web의 `nginx.conf`에 있다.
- `ai` 컨테이너는 루트 파일 시스템을 읽기 전용으로 띄운다(`read_only: true`, `/tmp`는 메모리). 사진이 디스크에 닿지 않게 하려는 것이니 볼륨을 붙이지 않는다. 로그에는 사진도 관찰 문장도 남지 않는다(요청 id, 장수, 걸린 시간, 결과 코드만).
- 끄려면 `AI_ENABLED=false`, `COMPOSE_PROFILES=`로 되돌리고 `docker compose up -d`. 이미 저장된 AI 관찰 기록은 남아 있고 조회·삭제·동의 철회는 계속 된다.
- 켜기 전에 [ai-observation.md 10장](ai-observation.md#10-아직-확인이-필요한-것-코드로-해결되지-않음)의 확인 항목(법률 검토 등)을 먼저 끝낸다.

## 3. TLS 인증서 (Let's Encrypt)

카메라 촬영과 Web Crypto는 HTTPS에서만 동작한다.

아래 명령은 셸 변수 `$SERVER_NAME`을 쓴다. 이 값은 `deploy/.env`에만 있고 셸에는 없으므로, **먼저 `.env`에서 `SERVER_NAME` 한 줄만 꺼내 온다**(2장에서 만든 `~/hamkke/hamkke-api/deploy`에서 실행). `.env`를 통째로 셸에서 실행(`. ./.env`)하지 않는다. 그러면 DB 비밀번호·`JWT_SECRET`까지 셸 환경변수로 퍼지고, 값에 `$`·`&`·`;`·공백·따옴표가 있으면 깨지거나 명령으로 해석될 수 있다. 값이 비어 있으면 certbot을 실행하지 말고 `.env`부터 채운다.

```bash
cd ~/hamkke/hamkke-api/deploy
export SERVER_NAME=$(grep -E '^SERVER_NAME=' .env | cut -d= -f2-)   # SERVER_NAME 한 줄만 꺼내기
echo "SERVER_NAME=$SERVER_NAME"       # 도메인이 맞는지 확인
[ -n "$SERVER_NAME" ] || echo "SERVER_NAME이 비어 있어요. deploy/.env를 채운 뒤 다시 불러오세요"

sudo apt install -y certbot
sudo certbot certonly --standalone -d $SERVER_NAME   # 최초 1회(80 포트가 비어 있을 때)
mkdir -p certs && sudo cp /etc/letsencrypt/live/$SERVER_NAME/{fullchain,privkey}.pem certs/
```

갱신은 `certbot renew --webroot -w ./certbot-www` 후 인증서를 다시 복사하고 `docker compose restart web`. 새 셸에서 복사할 때도 위의 `export SERVER_NAME=...` 줄을 먼저 실행한다(cron에 넣을 때도 같다).

## 4. 실행

```bash
docker compose up -d --build
docker compose ps                     # api가 healthy인지 확인 (AI를 켰으면 ai도)
docker compose logs -f api            # Flyway가 V1~V5를 적용했는지 확인
```

AI 관찰 기록을 켰다면 `curl -s https://도메인/ai/v1/health` 가 `{"status":"ok","model":"...","modelReady":true}` 인지 본다. `modelReady`가 `false`면 `OLLAMA_URL`과 모델 내려받기(`ollama pull`)를 확인한다.

### 4.1 프록시와 클라이언트 주소

(분석 토큰 발급 제한은 IP가 아니라 사용자 기준이라 아래 내용과 무관하다.) 로그인·가입·초대 코드 입력의 IP 기준 요청 제한은 API가 보는 클라이언트 주소에 기대고, API(`server.forward-headers-strategy: framework`)는 그 주소를 nginx가 넘긴 `X-Forwarded-For`의 **첫 번째 값**에서 읽는다. 그래서 nginx는 `X-Forwarded-For`를 클라이언트 주소 하나로 **덮어써야** 한다. hamkke-web의 `nginx.conf`가 이렇게 설정돼 있으니, 바꿀 때 이 줄을 지키자.

```nginx
proxy_set_header X-Forwarded-For $remote_addr;   # 덮어쓰기: 클라이언트가 보낸 값은 버린다
```

`$proxy_add_x_forwarded_for`(덧붙이기)를 쓰면 클라이언트가 직접 보낸 `X-Forwarded-For` 값이 맨 앞에 남아, 요청마다 주소를 바꿔 요청 제한을 피할 수 있다. nginx 앞에 로드 밸런서를 더 두는 구성이면 `real_ip` 모듈로 신뢰할 프록시 주소를 정한 뒤 `$remote_addr`를 넘긴다. API 컨테이너 포트(8080)는 외부에 열지 않는다(`expose`만).

## 5. 백업 (매일)

```bash
# crontab -e
0 4 * * * cd /home/ubuntu/hamkke/hamkke-api/deploy && docker compose exec -T mysql sh -c 'mysqldump -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" --single-transaction "$MYSQL_DATABASE"' | gzip > /home/ubuntu/backup/hamkke-$(date +\%F).sql.gz
```

백업 파일에도 아동 정보가 있으므로 S3 업로드 시 서버 측 암호화를 켜고 30일 뒤 지운다. AI 관찰 기록(글자)도 백업에 들어가므로, 보호자가 동의를 철회해 지운 기록이 백업에는 최대 30일 남는다는 점을 개인정보 처리방침에 적는다(사진은 어디에도 저장되지 않으므로 백업에도 없다).

## 6. 업데이트

```bash
git -C .. pull && git -C ../../hamkke-web pull && docker compose up -d --build
# AI 관찰 기록을 켠 서버라면 hamkke-ai도 함께: git -C ../../hamkke-ai pull
```

DB 스키마 변경은 `src/main/resources/db/migration/V{n}__*.sql`(hamkke-api 저장소 기준)을 새로 추가한다. 이미 적용된 파일(V1~V5)은 Flyway 체크섬이 깨지므로 고치지 않는다. V5(AI 관찰 기록 테이블, 공유 설정 두 항목, '힘든 순간 함께하기' 카드 6장)는 AI 기능을 켜지 않아도 적용된다.

## 7. 출시 전 점검

- [ ] `https://도메인/api/v1/policies` 200 (`aiObservationEnabled`가 의도한 값인지)
- [ ] AI 관찰 기록을 켰다면: `https://도메인/ai/v1/health`의 `modelReady: true`, 동의 → 장면 사진 → 관찰 기록 → 카드까지 한 번, 동의를 끄면 기록이 지워지는지, 네트워크 탭에서 사진이 `/ai/v1/analyze`로만 가고 `/api/`로는 글자만 가는지
- [ ] 실제 폰(안드로이드 Chrome, iOS Safari)에서 촬영 → 분석 → 카드까지 한 번
- [ ] 브라우저 개발자 도구 네트워크 탭에 영상·소리 업로드 요청이 없는지 확인(AI 관찰 기록에 동의하지 않은 아이는 사진 요청도 없어야 한다)
- [ ] `node scripts/check-expressions.mjs` 통과
- [ ] SPEC 11장 "출시 전 확인 목록"(법률 검토·카드 검수·기관 연락처 확인)
