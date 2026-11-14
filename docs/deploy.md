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

아래는 비워 두면 기본값을 쓴다. 뜻과 형식은 hamkke-ai `docs/observation.md` 2장이 기준이고, compose(`ai.environment`)는 이 이름을 모두 넘긴다(hamkke-ai 테스트와 CI가 확인한다).

| 변수 | 기본값 | 설명 |
|---|---|---|
| `VL_MAX_WAITING` | 2 | 차례를 기다릴 수 있는 요청 수. 넘치면 429 `BUSY` |
| `VL_MAX_WAIT_MS` | `VL_TIMEOUT_MS`의 절반 | 차례를 기다리는 시간의 상한. 넘으면 429 `BUSY`(토큰은 되돌린다) |
| `VL_KEEP_ALIVE` | compose `30m` | 모델을 메모리에 두는 시간. Ollama 기본(5분)이면 한동안 쓰지 않은 뒤 첫 분석이 모델을 다시 올리느라 느리다 |
| `VL_WARMUP` | compose `true` | ai 서버가 뜬 뒤 모델을 미리 올려 둔다(기다리지 않음, 로그 `warmup_done`/`warmup_failed`) |
| `VL_NUM_CTX`, `VL_MAX_OUTPUT_TOKENS` | 8192, 480 | 문맥 길이, 한 번에 만드는 토큰 수 상한 |
| `VL_FALLBACK_MODEL`, `VL_FALLBACK_URL` | 없음, `OLLAMA_URL` | 첫 모델을 쓸 수 없을 때 대신 쓰는 모델과 그 Ollama 주소. 영수증·저장 기록의 모델 이름에는 실제로 쓴 모델이 남는다 |
| `OLLAMA_AUTH_TOKEN`, `OLLAMA_AUTH_HEADER` | 없음, `Authorization` | Ollama를 다른 기기에 두고 TLS 프록시로 감쌌을 때 그 프록시의 토큰. 기본은 `Authorization: Bearer <토큰>`. 로그에 남지 않는다 |
| `OLLAMA_ALLOW_INSECURE` | `false` | `OLLAMA_URL`이 **바깥 주소를 http로** 가리키면 ai 서버가 기동하지 않는다(장면 사진이 평문으로 지나가므로). 꼭 그래야 할 때만 `true` |
| `METRICS_TOKEN` | 없음 | ai 서버 지표(`/ai/v1/metrics`)를 읽을 때 필요한 Bearer 토큰(8장) |

- **VL 모델은 GPU가 있는 기기에서 돌려야 한다.** GPU 없는 EC2(t3 계열)에서는 한 번 분석에 몇 분씩 걸려 쓸 수 없다. GPU 인스턴스에 Ollama를 띄우거나, 모델 서버를 다른 기기에 두고 `OLLAMA_URL`로 가리킨다. 다른 기기에 둘 때는 그 사이 구간도 사설망·TLS로 보호한다(장면 사진이 지나간다). 사설망이 아니면 GPU 기기의 Ollama는 `127.0.0.1`에만 열고 앞에 TLS 프록시(토큰 확인)를 둔 뒤 `OLLAMA_URL=https://…`, `OLLAMA_AUTH_TOKEN=…`으로 가리킨다. 프록시 설정 예는 hamkke-ai `docs/observation.md` 2.1절.
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

### 3.1 자동 갱신 (`deploy/renew-cert.sh`)

Let's Encrypt 인증서는 90일짜리다. 갱신은 `deploy/renew-cert.sh`가 한다. 스크립트는 `.env`에서 `SERVER_NAME`·`CERT_DIR` 두 줄만 꺼내 읽는다(`.env`를 셸에서 실행하지 않는다).

1. `certbot renew --webroot -w deploy/certbot-www`: nginx가 `/.well-known/acme-challenge/`를 `certbot-www`에서 내주므로 80 포트를 비우지 않아도 된다(처음에 `--standalone`으로 받았어도 이 실행부터 webroot로 갱신된다). 만료 30일 전이 아니면 아무것도 바꾸지 않는다.
2. 실제로 갱신됐을 때만 `--deploy-hook`이 불려 새 인증서를 `certs/`에 복사하고(`privkey.pem`은 권한 600), `nginx -t`로 확인한 뒤 `nginx -s reload`로 연결을 끊지 않고 다시 읽힌다(재시작 없음).

```bash
cd ~/hamkke/hamkke-api/deploy
chmod +x renew-cert.sh backup.sh restore.sh
sudo ./renew-cert.sh --dry-run   # 연습 서버로 갱신 절차만 확인(처음 한 번)
./renew-cert.sh check            # certs/fullchain.pem 이 14일 안에 만료되면 0 이 아닌 값으로 끝난다
```

root의 crontab에 넣는다(`sudo crontab -e`). 하루 두 번 시도하고, 매일 아침 만료가 가까운지 확인한다.

```cron
17 3,15 * * * /home/ubuntu/hamkke/hamkke-api/deploy/renew-cert.sh >> /var/log/hamkke-renew-cert.log 2>&1
30 9 * * *    /home/ubuntu/hamkke/hamkke-api/deploy/renew-cert.sh check 14 >> /var/log/hamkke-renew-cert.log 2>&1 || logger -t hamkke "TLS 인증서가 14일 안에 만료돼요"
```

브라우저 쪽에서 확인: `echo | openssl s_client -connect $SERVER_NAME:443 -servername $SERVER_NAME 2>/dev/null | openssl x509 -noout -enddate`. 가동 감시 서비스를 쓰면 인증서 만료 알림(14일 전)도 켠다.

## 4. 실행

```bash
docker compose up -d --build
docker compose ps                     # api가 healthy인지 확인 (AI를 켰으면 ai도)
docker compose logs -f api            # Flyway가 V1~V5를 적용했는지 확인
```

AI 관찰 기록을 켰다면 `curl -s https://도메인/ai/v1/health` 가 `{"status":"ok","model":"...","modelReady":true}` 인지 본다. `modelReady`가 `false`면 `OLLAMA_URL`과 모델 내려받기(`ollama pull`)를 확인한다. `docker compose logs ai`에 `warmup_done`이 보이면 모델이 메모리에 올라간 것이다.

- `ai` 컨테이너의 `HEALTHCHECK`(`docker compose ps`의 healthy)는 **서버가 살아 있는지만** 본다. 모델이 준비되지 않아도 healthy다(다시 켜도 나아지지 않기 때문).
- 모델까지 확인하려면 바깥 가동 감시에 `https://도메인/ai/v1/health?strict=1`을 등록한다. 모델을 쓸 수 없으면 503을 돌려준다(본문·인증 없음, 사진·문장 없음).

### 4.1 프록시와 클라이언트 주소

(분석 토큰 발급 제한은 IP가 아니라 사용자 기준이라 아래 내용과 무관하다.) 로그인·가입·초대 코드 입력의 IP 기준 요청 제한은 API가 보는 클라이언트 주소에 기대고, API(`server.forward-headers-strategy: framework`)는 그 주소를 nginx가 넘긴 `X-Forwarded-For`의 **첫 번째 값**에서 읽는다. 그래서 nginx는 `X-Forwarded-For`를 클라이언트 주소 하나로 **덮어써야** 한다. hamkke-web의 `nginx.conf`가 이렇게 설정돼 있으니, 바꿀 때 이 줄을 지키자.

```nginx
proxy_set_header X-Forwarded-For $remote_addr;   # 덮어쓰기: 클라이언트가 보낸 값은 버린다
```

`$proxy_add_x_forwarded_for`(덧붙이기)를 쓰면 클라이언트가 직접 보낸 `X-Forwarded-For` 값이 맨 앞에 남아, 요청마다 주소를 바꿔 요청 제한을 피할 수 있다. nginx 앞에 로드 밸런서를 더 두는 구성이면 `real_ip` 모듈로 신뢰할 프록시 주소를 정한 뒤 `$remote_addr`를 넘긴다. API 컨테이너 포트(8080)는 외부에 열지 않는다(`expose`만).

## 5. 백업 (매일, `deploy/backup.sh`)

백업 파일에도 아동 정보가 있다. 그래서 **암호화해서 올리고 30일 뒤 지운다.** AI 관찰 기록(글자)도 백업에 들어가므로, 보호자가 동의를 철회해 지운 기록이 백업에는 최대 30일 남는다는 점을 개인정보 처리방침에 적는다(사진은 어디에도 저장되지 않으므로 백업에도 없다).

`deploy/backup.sh`가 하는 일:

1. `mysqldump --single-transaction --no-tablespaces`(잠그지 않는 일관된 덤프, 앱 계정 권한으로 충분) → `gzip -9`. DB 비밀번호는 명령줄에 쓰지 않고 컨테이너 안에서 `MYSQL_PWD`로 넘긴다.
2. (선택) `BACKUP_PASSPHRASE_FILE`이 있으면 gpg(AES256)로 한 번 더 암호화한다(`.sql.gz.gpg`).
3. `BACKUP_DIR`(기본 `~/backup`, 권한 700)에 `hamkke-<UTC 시각>.sql.gz[.gpg]`와 `.sha256`을 둔다.
4. (선택) `BACKUP_S3_URI`가 있으면 `aws s3 cp --sse AES256`(또는 `BACKUP_S3_SSE=aws:kms`)으로 올린다.
5. 이 서버에서는 `BACKUP_KEEP_DAYS`(기본 7)일이 지난 백업을 지운다.

`.env`에 채울 값(스크립트는 이 이름들만 한 줄씩 꺼내 읽는다):

| 변수 | 기본값 | 설명 |
|---|---|---|
| `BACKUP_DIR` | `~/backup` | 이 서버에 두는 폴더 |
| `BACKUP_KEEP_DAYS` | 7 | 이 서버에 두는 일수 |
| `BACKUP_S3_URI` | 없음 | 예: `s3://hamkke-backup/mysql`. 비우면 올리지 않는다 |
| `BACKUP_S3_SSE`, `BACKUP_S3_KMS_KEY_ID` | `AES256`, 없음 | S3 서버 측 암호화 방식(`AES256`·`aws:kms`)과 KMS 키 |
| `BACKUP_PASSPHRASE_FILE` | 없음 | gpg 암호 파일 경로(권한 600). 이 파일의 사본은 서버 밖 안전한 곳(팀 비밀번호 관리자)에 둔다. 잃으면 백업을 풀 수 없다 |

S3 준비(한 번): 버킷은 공개 접근 차단, 기본 암호화 켜기, 아래 수명 주기 규칙으로 30일 뒤 지운다. EC2에는 그 버킷의 `mysql/` 아래에 `s3:PutObject`·`s3:GetObject`만 허용한 IAM 역할을 붙인다(액세스 키를 서버에 두지 않는다).

```bash
aws s3api put-public-access-block --bucket hamkke-backup \
  --public-access-block-configuration BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true
aws s3api put-bucket-encryption --bucket hamkke-backup \
  --server-side-encryption-configuration '{"Rules":[{"ApplyServerSideEncryptionByDefault":{"SSEAlgorithm":"AES256"}}]}'
aws s3api put-bucket-lifecycle-configuration --bucket hamkke-backup --lifecycle-configuration file://s3-lifecycle.json
```

`deploy/s3-lifecycle.json`은 `mysql/` 아래 객체를 30일 뒤 지우고, 버전 관리를 켰다면 이전 버전은 1일 뒤, 끝나지 않은 업로드는 1일 뒤 지운다.

cron(`crontab -e`, ubuntu 사용자). 로그 파일을 그 폴더에 쓰므로 폴더를 먼저 만든다(`mkdir -p ~/backup && chmod 700 ~/backup`, 없으면 cron 이 스크립트를 실행하지 못한다):

```cron
0 4 * * * /home/ubuntu/hamkke/hamkke-api/deploy/backup.sh >> /home/ubuntu/backup/backup.log 2>&1
```

로그에는 파일 이름·크기·지운 파일만 남는다.

### 5.1 복원 연습 (한 달에 한 번, `deploy/restore.sh --drill`)

복원해 본 적 없는 백업은 백업이 아니다. 운영 DB를 건드리지 않는 연습 모드로 확인한다.

```bash
cd ~/hamkke/hamkke-api/deploy
./restore.sh --drill ~/backup/hamkke-20261114T040000Z.sql.gz
./restore.sh --drill s3://hamkke-backup/mysql/hamkke-20261114T040000Z.sql.gz.gpg   # S3에서 받아서
```

`.sha256`을 확인하고, 임시 DB `hamkke_restore_drill`에 넣은 뒤 표마다 행 수와 Flyway 마지막 버전만 보이고 임시 DB를 지운다(내용은 화면에 내보내지 않는다). 걸린 시간과 결과를 운영 기록에 적는다.

### 5.2 실제 복원

```bash
./restore.sh ~/backup/hamkke-20261114T040000Z.sql.gz
```

운영 DB 이름을 직접 입력해야 진행된다. api를 멈추고 복원한 뒤 다시 켠다. 백업 시점 이후의 기록은 사라지므로, 먼저 지금 상태를 `./backup.sh`로 한 번 더 백업해 둔다.

## 6. 업데이트

```bash
git -C .. pull && git -C ../../hamkke-web pull && docker compose up -d --build
# AI 관찰 기록을 켠 서버라면 hamkke-ai도 함께: git -C ../../hamkke-ai pull
```

DB 스키마 변경은 `src/main/resources/db/migration/V{n}__*.sql`(hamkke-api 저장소 기준)을 새로 추가한다. 이미 적용된 파일(V1~V5)은 Flyway 체크섬이 깨지므로 고치지 않는다. V5(AI 관찰 기록 테이블, 공유 설정 두 항목, '힘든 순간 함께하기' 카드 6장)는 AI 기능을 켜지 않아도 적용된다.

## 7. 출시 전 점검

- [ ] `sudo ./renew-cert.sh --dry-run` 통과, root crontab에 갱신·만료 확인 두 줄
- [ ] `./backup.sh` 한 번 실행 → `./restore.sh --drill`로 그 파일을 연습 복원(S3를 쓰면 S3 주소로도)

- [ ] `https://도메인/api/v1/policies` 200 (`aiObservationEnabled`가 의도한 값인지)
- [ ] AI 관찰 기록을 켰다면: `https://도메인/ai/v1/health`의 `modelReady: true`, 동의 → 장면 사진 → 관찰 기록 → 카드까지 한 번, 동의를 끄면 기록이 지워지는지, 네트워크 탭에서 사진이 `/ai/v1/analyze`로만 가고 `/api/`로는 글자만 가는지
- [ ] 실제 폰(안드로이드 Chrome, iOS Safari)에서 촬영 → 분석 → 카드까지 한 번
- [ ] 브라우저 개발자 도구 네트워크 탭에 영상·소리 업로드 요청이 없는지 확인(AI 관찰 기록에 동의하지 않은 아이는 사진 요청도 없어야 한다)
- [ ] `node scripts/check-expressions.mjs` 통과
- [ ] SPEC 11장 "출시 전 확인 목록"(법률 검토·카드 검수·기관 연락처 확인)

## 8. 로그와 지표

**로그 보관.** compose의 모든 서비스는 `json-file` 로그를 **10MB × 5개**까지만 둔다(`x-logging`, 서비스마다 최대 50MB). 넘으면 오래된 파일부터 지워지므로 디스크(30GB)가 로그로 차지 않는다. 더 오래 보관해야 하는 감사 기록은 로그가 아니라 DB에 둔다. 로그에는 사진·관찰 문장·토큰이 남지 않는다(ai 서버는 요청 id, jti 해시 앞부분, 장수, 걸린 시간, 결과 코드만). 설정을 바꾼 뒤에는 `docker compose up -d`로 컨테이너를 다시 만들어야 적용된다.

```bash
docker compose logs --since 1h ai            # 최근 1시간
docker inspect --format '{{.HostConfig.LogConfig}}' $(docker compose ps -q ai)   # 회전 설정 확인
```

**ai 서버 지표.** `GET /ai/v1/metrics`(Prometheus 글 형식): 경로·상태·결과 코드별 요청 수, 처리 시간 분포, 모델 호출 결과, 대기열 길이, 모델 준비 상태, 대신 쓰는 모델을 쓴 횟수. 사진·문장·토큰·jti·사용자 식별 값은 없다. 지표 이름 목록은 hamkke-ai `docs/observation.md` 3장.

- nginx가 `/ai/v1/metrics`를 밖에서 막는다(404). 같은 docker 망 안에서만 읽는다: `docker compose exec web wget -qO- --header "Authorization: Bearer $METRICS_TOKEN" http://ai:8090/ai/v1/metrics`(셸에 `METRICS_TOKEN`이 없으면 `.env`에서 그 한 줄만 꺼내 온다).
- `.env`에 `METRICS_TOKEN`(16자 이상, 예: `openssl rand -hex 24`)을 두면 토큰 없는 요청은 401이다. Prometheus를 같은 compose 망에 붙일 때는 `authorization: { credentials: <토큰> }`으로 읽는다.
- 볼 만한 것: `hamkke_ai_model_ready == 0`(모델 서버 확인), `hamkke_ai_requests_total{code="BUSY"}` 증가(동시 처리 수·GPU 확인), `hamkke_ai_requests_total{code="MODEL_TIMEOUT"}` 증가, `hamkke_ai_observations_total{fallback="true"}` 증가(첫 모델 확인).
