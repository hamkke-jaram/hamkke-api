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
cd hamkke-api/deploy
cp .env.example .env
openssl rand -base64 48   # → JWT_SECRET에 붙여넣기
vi .env                   # SERVER_NAME, DB 비밀번호 채우기
```

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
docker compose ps                     # api가 healthy인지 확인
docker compose logs -f api            # Flyway가 V1~V4를 적용했는지 확인
```

### 4.1 프록시와 클라이언트 주소

로그인·가입·초대 코드 입력의 IP 기준 요청 제한은 API가 보는 클라이언트 주소에 기대고, API(`server.forward-headers-strategy: framework`)는 그 주소를 nginx가 넘긴 `X-Forwarded-For`의 **첫 번째 값**에서 읽는다. 그래서 nginx는 `X-Forwarded-For`를 클라이언트 주소 하나로 **덮어써야** 한다. hamkke-web의 `nginx.conf`가 이렇게 설정돼 있으니, 바꿀 때 이 줄을 지키자.

```nginx
proxy_set_header X-Forwarded-For $remote_addr;   # 덮어쓰기: 클라이언트가 보낸 값은 버린다
```

`$proxy_add_x_forwarded_for`(덧붙이기)를 쓰면 클라이언트가 직접 보낸 `X-Forwarded-For` 값이 맨 앞에 남아, 요청마다 주소를 바꿔 요청 제한을 피할 수 있다. nginx 앞에 로드 밸런서를 더 두는 구성이면 `real_ip` 모듈로 신뢰할 프록시 주소를 정한 뒤 `$remote_addr`를 넘긴다. API 컨테이너 포트(8080)는 외부에 열지 않는다(`expose`만).

## 5. 백업 (매일)

```bash
# crontab -e
0 4 * * * cd /home/ubuntu/hamkke/hamkke-api/deploy && docker compose exec -T mysql sh -c 'mysqldump -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" --single-transaction "$MYSQL_DATABASE"' | gzip > /home/ubuntu/backup/hamkke-$(date +\%F).sql.gz
```

백업 파일에도 아동 정보가 있으므로 S3 업로드 시 서버 측 암호화를 켜고 30일 뒤 지운다.

## 6. 업데이트

```bash
git -C .. pull && git -C ../../hamkke-web pull && docker compose up -d --build
```

DB 스키마 변경은 `src/main/resources/db/migration/V{n}__*.sql`(hamkke-api 저장소 기준)을 새로 추가한다. 이미 적용된 파일(V1~V4)은 Flyway 체크섬이 깨지므로 고치지 않는다.

## 7. 출시 전 점검

- [ ] `https://도메인/api/v1/policies` 200
- [ ] 실제 폰(안드로이드 Chrome, iOS Safari)에서 촬영 → 분석 → 카드까지 한 번
- [ ] 브라우저 개발자 도구 네트워크 탭에 영상 업로드 요청이 없는지 확인
- [ ] `node scripts/check-expressions.mjs` 통과
- [ ] SPEC 11장 "출시 전 확인 목록"(법률 검토·카드 검수·기관 연락처 확인)
