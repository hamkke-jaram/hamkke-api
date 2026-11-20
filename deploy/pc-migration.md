# 기존 회원과 기록을 보존하는 PC 이전

목표는 Spring API와 운영 MySQL을 Windows PC에서 실행하고, 기존 공개 주소와 HTTPS 인증서를 유지하는 것이다. 기존 PC의 운영 AI와 모델은 계속 사용한다. 아래 운영 명령은 전환 시간을 정하고 담당자가 실제 이전을 승인한 뒤 실행한다.

| 기능 | 현재 운영 | 전환 후 운영 |
| --- | --- | --- |
| 웹·TLS | EC2 nginx, 기존 공개 주소 | 같은 nginx, 주소, 인증서 및 갱신 작업 |
| 백엔드 | nginx → EC2 루프백 18080 → Spring | nginx → EC2 루프백 18980 → SSH → PC 루프백 18980 → Spring |
| AI | nginx → EC2 루프백 18090 → SSH → PC 루프백 18090 | 기존 AI 프로세스와 전용 터널 유지 |
| 업무 데이터 | EC2 Compose 내부 MySQL | PC의 전용 MySQL, 루프백 바인딩, 전용 데이터 디렉터리 |

### 복원 연습 기록

합성 데이터로 MySQL 8.4.13에 V7과 V8 자료를 복원했다. V7에서 테이블 18개·행 61개·외래 키 25개, V8에서 테이블 19개·행 62개·외래 키 25개가 원본과 일치했다. 복원 후 보호자와 교사의 로그인, 연결, 대화와 읽음 표시, AI 관찰 기록을 확인했다. 운영 DB와 API는 EC2에서 실행 중이며 이 절차는 추후 PC 이전을 준비할 때 사용한다.

## 전환 전에 준비할 것

1. **대상 인스턴스.** 전환 당일 EC2의 `mysqld --version`을 다시 확인한다. 확인된 8.4.13과 같은 버전의 운영 전용 인스턴스와 클라이언트로 전체 복원을 시험한다. PC의 기존 3306과 연습용 데이터 디렉터리는 그대로 보존한다. 운영 데이터·로그·백업은 저장소 밖의 암호화된 디스크에 두고 서비스 계정과 백업 담당자만 접근하도록 Windows ACL을 설정한다.
2. **운영 설정.** 비교가 끝날 때까지 원본과 같은 버전의 API, UTC, `utf8mb4`, `utf8mb4_0900_ai_ci`를 쓴다. 실 서비스의 `JWT_SECRET`은 PC Spring에, 현재 운영 AI와 짝인 `AI_SHARED_SECRET`은 PC Spring의 별도 설정에 주입한다. 두 값은 서로 다르다. 원래 issuer, 동의 정책 버전과 AI 설정을 유지한다. 암호화 백업을 여는 키도 따로 인계한다. 키 값은 암호 관리 경로로 옮기고 로그·명령줄·저장소에 남기지 않는다. DB 계정 암호는 PC 인스턴스 전용으로 설정할 수 있다.
3. **입구와 포트.** 새 API는 PC `127.0.0.1:18980`에 바인딩하고 사설 DB와만 연결한다. 연습 API가 같은 포트를 점유하면 연습 스택부터 정상 종료한다. 전환 시 EC2의 배포 폴더에서 `sudo bash install-ai-tunnel.sh "$api_public_key_file" api`로 전용 공개 키와 [API SSH 규칙](hamkke-api-sshd.conf)을 등록한다. 이 계정에는 `127.0.0.1:18980` 역방향 리스너 하나만 허용한다. Windows 전용 키 폴더에 관리 경로에서 확인한 서버 호스트 키를 넣은 뒤 `.\Connect-AiTunnel.ps1 -Server service.example.com -Service Api -KeyDirectory $apiKeyDirectory`로 연결한다. 기존 AI 계정·키는 18090 전용이다. 인터넷에는 두 DB 포트와 두 역방향 리스너를 열지 않는다.
4. **재부팅 및 복구 연습.** 전용 서비스 계정으로 PC 부팅 후 MySQL → API, AI·모델, 두 터널이 로그온 없이 다시 시작하는지 실제 재부팅으로 확인한다. 현재 AI와 AI 터널의 예약 작업은 사용자 로그온 시 시작한다. 같은 계정으로 보호한 DPAPI 비밀은 다른 서비스 계정에서 그냥 읽을 수 없으므로 운영 계정으로 다시 보호한다. 이 PC에서 확인한 대기 시간은 AC 전원에서 0(자동 대기 없음), 배터리에서 600초다. 전원 복구 및 네트워크 재연결 후 API·AI가 모두 복구되는지 시험한다. 현재 Windows 계정으로는 운영 대상 볼륨의 암호화 상태를 확인할 수 없어서, 담당자가 관리 권한으로 확인해야 한다. 키가 든 계정이나 운영 PC를 사용할 수 없는 경우를 대비해 별도 보관한 키와 암호화 백업으로 빈 DB에 실제 복원해 시간을 기록한다.
5. **백업의 목적지.** EC2의 [백업 스크립트](backup.sh)는 `docker compose exec -T mysql`을 사용한다. 이전 후에는 PC 운영 인스턴스를 대상으로 예약 백업하고, 암호화 사본과 SHA-256 검증 파일을 PC와 독립된 보관소에 둔다. 전환 전 빈 DB로 복원 시험을 한 번 마친다. 이후 예약 백업 대상과 실패 알림을 바꾸고 첫 운영 백업의 복원도 확인한다.

## 중단 시간에 한 번만 복제하기

1. **원본 고정.** 전환 시간과 일시 중단을 알린다. nginx에서 `/api/`와 `/ai/`를 점검 응답(503)으로 차단한다. 이미 시작한 요청의 커밋과 AI 요청 종료를 기다리고, 쓰기가 확인되지 않은 요청은 재시도 대상으로 표시한다. EC2의 배포 폴더에서 아래 명령을 사용한다. Spring을 중지하면 토큰·동의·영수증 정리 같은 예약 쓰기도 멈춘다. 별도 DB 작업과 배포 작업도 중단한다.

   ```bash
   cd /opt/hamkke/hamkke-api/deploy
   docker compose stop api
   docker compose ps --all api mysql
   ```

2. **최종 백업.** 원본 DB의 테이블 목록과 아래 검증 결과를 기록한다. `BACKUP_PASSPHRASE_FILE`이 읽을 수 있게 설정되어 있고 백업 목적지·용량·DB 계정 권한이 맞는지 확인하고 `bash backup.sh`를 실행한다. 최종 `.sql.gz.gpg`와 `.sha256` 두 파일을 인증된 경로로 옮긴다. 파일명을 명시해서 양쪽에서 SHA-256을 비교한다. `backup.sh`는 성공 시 체크섬 파일을 만들며, 설치된 루틴과 트리거가 있으면 복구할 수 있는 권한·옵션도 필요하다. S3 전송 오류가 있으면 로컬 파일과 원격 사본의 상태를 각각 확인한다.
3. **PC 복원.** PC의 운영용 새 DB가 비어 있고 연습 스키마와 구분되는지 확인한다. Spring을 멈춘 상태에서 지정한 백업을 풀어 그 DB에만 넣는다. 예를 들어 전용 MySQL 클라이언트와 GPG가 준비된 **Bash 환경**에서는 아래처럼 바이너리 스트림을 연결한다. `pc_db_options`는 현 호스트에서 운영 DB를 식별하는, 접근 권한을 제한한 MySQL client 옵션 파일이다. Windows 호스트의 루프백을 쓸 수 있는 환경에서 실행하고 접속 대상 포트·DB 이름을 먼저 확인한다.

   ```bash
   set -euo pipefail
   cd "$encrypted_backup_dir"
   sha256sum -c "$backup_name.sha256"
   gpg --batch --quiet --decrypt --pinentry-mode loopback --passphrase-file "$backup_key_file" "$backup_name" |
     gzip -dc |
     mysql --defaults-extra-file="$pc_db_options" --protocol=TCP "$target_db"
   ```

   각 단계의 종료 코드를 확인한다. 입력이 중간에 끊겼으면 새 빈 DB에서 다시 수행한다. EC2의 [복원 도구](restore.sh)는 기본 모드에서 원래 Compose DB를 대상으로 쓰고 API를 다시 시작한다. PC로 복제할 때는 위와 같이 대상을 분리한다.
4. **일치 확인.** PC Spring을 띄우기 전에 아래 대조를 끝낸다. 운영·복원본의 Flyway 이력과 체크섬을 전부 비교한다. 운영 DB에서 확인한 마지막 마이그레이션과 검토 결과 저장 열까지 복원됐는지 확인한다. 실제 이전 시점의 운영 JAR로 먼저 복원 결과를 확인하고 이후 코드 변경은 별도로 배포한다.
5. **외부 연결 차단 상태로 인계.** PC Spring에 `SPRING_PROFILES_ACTIVE=prod`, PC 운영 DB의 `DB_HOST/DB_PORT/DB_NAME/DB_USER/DB_PASSWORD`, 운영 키 및 `AI_ENABLED=true`를 넣는다. 기관 CSV 자동 가져오기 등 별도 초기 쓰기를 제외한다. EC2 Spring은 계속 중지한 상태로 PC Spring과 API 전용 역방향 터널을 기동한다. EC2에서 아래 내부 확인을 마친 뒤 [EC2 웹용 Compose 설정](compose.pc-api-edge.yml)으로 nginx API upstream을 `127.0.0.1:18980`으로 바꾼다. nginx 설정을 검증하고 웹 컨테이너만 재구성하며, 503 해제 전 EC2 18080 경로로 돌아가는 자동 대체 설정이 없는지 확인한다. **원래 Compose의 웹은 API에 의존한다.** 이후에도 운영 배포·재부팅·인증서 갱신이 기존 API를 임의 기동하지 않도록 릴리스가 참조하는 Compose 파일 목록을 지속 저장한다. 웹 재구성 명령의 대상은 `up -d --no-deps web`으로 제한한다.

   Windows에서는 프로젝트 묶음의 루트에서 아래와 같은 명령으로 기동한다. 운영 서비스 계정에 승인된 DB의 포트·이름·접속 계정과 비밀을 먼저 주입한다. 이 명령은 해당 키의 값을 출력하지 않는다. 부팅 시 사용할 서비스에도 같은 실행 인자와 보호된 설정을 적용한다.

   ```powershell
   $expectedHash = (Read-Host '현재 운영 컨테이너에서 확인한 JAR SHA-256').Trim().ToLowerInvariant()
   if ($expectedHash -notmatch '^[0-9a-f]{64}$') { throw 'SHA-256 형식을 확인하세요.' }
   $apiJar = (Resolve-Path -LiteralPath (Read-Host '검증할 운영 JAR 경로')).Path
   if ((Get-FileHash -LiteralPath $apiJar -Algorithm SHA256).Hash -ne $expectedHash) { throw '운영 JAR 해시를 확인하세요.' }
   foreach ($name in 'DB_PORT','DB_NAME','DB_USER','DB_PASSWORD','JWT_SECRET','AI_SHARED_SECRET') {
       if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($name))) { throw "$name 운영 설정이 필요합니다." }
   }
   $env:DB_HOST = '127.0.0.1'
   $env:SPRING_PROFILES_ACTIVE = 'prod'
   $env:AI_ENABLED = 'true'
   $env:SERVER_ADDRESS = '127.0.0.1'
   $env:SERVER_PORT = '18980'
   & java -jar $apiJar
   ```

   ```bash
   curl -fsS http://127.0.0.1:18980/actuator/health
   curl -fsS http://127.0.0.1:18980/api/v1/policies
   curl -fsS 'http://127.0.0.1:18090/ai/v1/health?strict=1'
   ```

   EC2의 `deploy` 폴더에서 세 파일을 이 순서로 사용한다. `cloud-rollback` 프로필은 이전 DB로 복구할 때만 명시적으로 사용한다. 공개 요청이 여전히 점검 응답을 받는 상태에서 다음을 실행한다.

   ```bash
   edge=(docker compose -f docker-compose.yml -f compose.ec2-pc.yml -f compose.pc-api-edge.yml)
   "${edge[@]}" config --quiet
   "${edge[@]}" config --services
   "${edge[@]}" run --rm -T --no-deps web nginx -t </dev/null
   "${edge[@]}" up -d --no-deps --no-build --wait web
   ```

6. **공개 재개.** `/api/`, `/ai/` 점검이 끝나면 기존 HTTPS 주소의 점검 응답을 해제하고 시각을 기록한다. 준비한 검증 계정으로 기존 로그인 세션, 본인의 기존 기록과 선생님 대화, 합성 영상의 관찰 생성·저장을 확인한다. 공개 웹은 EC2를, 가입·대화·관찰 결과는 PC의 단일 운영 DB를 사용한다. PC가 꺼지면 웹 소개 페이지가 열려도 이 기능들은 사용할 수 없다.

### 행 내용과 스키마 대조

- `SHOW FULL TABLES`로 실제 운영 테이블 전체를 열거한다. 각 기본 테이블에서 `SELECT COUNT(*)`를 사용한다. 사용자의 비밀번호 해시와 세션이 있는 `users`, `refresh_tokens`부터 `children`, `consents`, `share_settings`, `teacher_links`, 관찰·대화·읽음 표시·`ai_used_receipts`까지 이전 대상이다. 비교 기록에는 테이블명·건수·해시만 남긴다.
- 두 DB에서 같은 버전과 출력 형식의 `mysqldump`를 사용한다. 각 기본 테이블에서 PK 순서로 정렬한 동일한 표현의 행을 SHA-256으로 비교한다. Linux 원본과 Windows 대상의 클라이언트를 각각 사용할 때는 같은 줄 끝(LF)과 UTC 세션을 적용한 뒤 해시를 계산한다. 예시는 한 테이블에 대해 실행하며 `target_db`, `table`, `db_options`에는 해당 DB의 실제 값을 설정한다. `pipefail`을 켜고 양쪽 모두 명령 전체가 성공했는지 확인한다.

  ```bash
  set -o pipefail
  mysqldump --defaults-extra-file="$db_options" --single-transaction --no-tablespaces --no-create-info \
    --skip-triggers --skip-comments --compact --skip-extended-insert --order-by-primary --hex-blob \
    --default-character-set=utf8mb4 --set-gtid-purged=OFF "$target_db" "$table" | sha256sum
  ```

- `SELECT installed_rank,version,checksum,success FROM flyway_schema_history ORDER BY installed_rank;`의 모든 행, 그리고 DDL·인덱스·외래 키·다음 AUTO_INCREMENT·정렬 규칙을 대조한다. MySQL 패치와 테이블 설정 차이로 해시가 다르면 공개 전에 차이를 설명하고 다시 검사한다.

## 되돌리기

| 시점 | 데이터 손실을 막는 절차 |
| --- | --- |
| 최종 백업 후 PC에 가입·토큰 갱신·읽음 표시·정리 작업을 포함한 **어떤 변경도 생기기 전** | 점검 응답을 유지하고 PC Spring을 중지한다. 원본 DB가 최종 백업 때와 같은지 확인한 다음 EC2 Spring을 시작하고 nginx API upstream을 기존 18080으로 복원한다. 내부 상태와 기존 세션을 확인한 뒤 점검을 해제한다. |
| PC에 쓰기가 생겼거나, 변경 발생 여부가 불분명한 경우 | 양쪽의 사용자 요청을 점검 상태로 두고 PC의 진행 중 쓰기를 마친 후 PC Spring을 중지한다. PC DB를 유일한 최신 원본으로 삼아 **새 암호화 백업**을 만든다. EC2에 별도 빈 DB로 복원하고, PC와 건수·해시·스키마를 대조한다. EC2 Spring의 DB 대상을 이 복원본으로 지정하고 **EC2만** 쓰기를 재개한다. 전체 비교와 점검이 끝날 때까지 503을 유지한다. |

PC 장애로 최신 DB를 읽을 수 없다면 기존 EC2 사본을 자동으로 열지 않는다. 마지막 일치 시점 이후의 쓰기 범위를 백업과 사용할 수 있는 복구 기록으로 확인할 때까지 점검 상태를 유지한다. 이전 DB와 암호화 백업 보관 종료는 전환 검증, 복원 시험 및 운영 보관 기준에 맞춰 별도로 결정한다.
