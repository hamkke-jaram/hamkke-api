# 배포 가이드 (AWS EC2 + Docker Compose)

## 1. 서버 준비

- EC2: 서울 리전(ap-northeast-2), t3.small 이상, Ubuntu 24.04, 디스크 30GB
- 보안 그룹: 80, 443만 열기(SSH 22는 팀 IP만). MySQL 3306은 열지 않는다.
- Docker Engine + Compose 플러그인 설치
- 도메인 A 레코드 → EC2 탄력적 IP

## 2. 설정

```bash
mkdir hamkke && cd hamkke
git clone https://github.com/hamkke-jaram/hamkke-api.git
git clone https://github.com/hamkke-jaram/hamkke-web.git
cd hamkke-api/deploy
cp .env.example .env
openssl rand -base64 48   # → JWT_SECRET에 붙여넣기
vi .env                   # SERVER_NAME, DB 비밀번호 채우기
```

## 3. TLS 인증서 (Let's Encrypt)

카메라 촬영과 Web Crypto는 HTTPS에서만 동작한다.

```bash
sudo apt install -y certbot
sudo certbot certonly --standalone -d $SERVER_NAME   # 최초 1회(80 포트가 비어 있을 때)
mkdir -p certs && sudo cp /etc/letsencrypt/live/$SERVER_NAME/{fullchain,privkey}.pem certs/
```

갱신은 `certbot renew --webroot -w ./certbot-www` 후 인증서를 다시 복사하고 `docker compose restart web`.

## 4. 실행

```bash
docker compose up -d --build
docker compose ps                     # api가 healthy인지 확인
docker compose logs -f api            # Flyway가 V1~V3를 적용했는지 확인
```

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

DB 스키마 변경은 `backend/src/main/resources/db/migration/V{n}__*.sql`을 새로 추가한다(기존 파일 수정 금지).

## 7. 출시 전 점검

- [ ] `https://도메인/api/v1/policies` 200
- [ ] 실제 폰(안드로이드 Chrome, iOS Safari)에서 촬영 → 분석 → 카드까지 한 번
- [ ] 브라우저 개발자 도구 네트워크 탭에 영상 업로드 요청이 없는지 확인
- [ ] `node scripts/check-expressions.mjs` 통과
- [ ] SPEC 11장 "출시 전 확인 목록"(법률 검토·카드 검수·기관 연락처 확인)
