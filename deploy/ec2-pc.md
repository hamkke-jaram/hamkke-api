# EC2 백엔드와 Windows AI 서버 연결

전용 Ubuntu EC2에서 nginx가 80·443 포트를 사용한다. nginx는 EC2의 `127.0.0.1:18080`으로 Spring API를, `127.0.0.1:18090`으로 Windows AI 서버를 호출한다. Windows가 EC2로 SSH 연결을 열므로 PC 공유기에서 포트를 열 필요가 없다. EC2 보안 그룹에는 방문자용 80·443과 운영자 및 AI PC의 공인 IP에서 오는 22/TCP만 연다.

## EC2 배포

1. [기존 배포 가이드](../docs/deploy.md)에 따라 같은 상위 폴더에 `hamkke-api`와 `hamkke-web`을 두고 DNS, 인증서, 데이터베이스 암호와 JWT 서명 키를 준비한다. Docker Compose v2.24.4 이상을 사용한다.
2. Linux의 `hamkke-api/deploy/.env`에 다음 설정을 반영한다. API와 PC에는 같은 `AI_SHARED_SECRET`을 설정한다. 32바이트 이상의 난수를 생성해 인증된 경로로 전달하고, JWT 서명 키는 별도로 생성한다. `.env`는 배포 계정만 읽도록 `chmod 600 .env`를 적용한다.

   ```dotenv
   COMPOSE_FILE=docker-compose.yml:compose.ec2-pc.yml
   COMPOSE_PROFILES=
   AI_ENABLED=true
   ```

3. `deploy`에서 아래 명령을 실행한다. `.env`의 `COMPOSE_FILE`은 같은 디렉터리에서 실행하는 기존 백업·복원·인증서 갱신 스크립트에도 적용된다. 인증서 갱신은 기존의 `certbot-www`와 `certs` 마운트를 사용한다.

   ```bash
   docker compose config --quiet
   docker compose config --services
   docker compose up -d --build
   docker compose ps
   ```

   서비스 목록은 `mysql`, `api`, `web`이다. MySQL은 Compose 내부 네트워크를 사용한다.

## 터널 연결

Windows AI에는 Node.js 24 이상, Ollama, `qwen3.5:4b` 모델과 같은 상위 폴더의 `hamkke-ai` 체크아웃이 필요하다. `hamkke-api/deploy`에서 별도 PowerShell 창으로 아래 명령을 실행한다. `-ApiOrigin`에는 교사 권한 확인 요청을 받을 서비스의 실제 HTTPS 주소를 지정한다.

```powershell
powershell.exe -NoProfile -ExecutionPolicy RemoteSigned -File .\Start-HamkkeAi.ps1 -ApiOrigin https://service.example.com
```

최초 실행은 Windows 사용자 계정의 DPAPI로 보호한 서명 키를 `%LOCALAPPDATA%\HamkkeJaram\ai\ai-secret.dpapi`에 만든다. 기존 키를 재사용할 때는 그 파일을 만든 계정에서 `-StateDirectory`에 기존 폴더를 지정한다. API에는 같은 서명 키를 설정한다. [EC2 활성화 스크립트](activate-ec2.sh)는 이 키를 표준 입력으로 받는다. 모델 요청은 PC의 Ollama로 보내며 AI 작업 프로세스가 끝나면 10초 후 다시 기동한다.

1. Windows에서 이 서비스 전용 키를 만든다. 키와 서버 공개 키를 아래 디렉터리에 보관한다. 암호를 지정한 키는 재연결 전에 이 Windows 계정의 `ssh-agent`에 등록해 둔다.

   ```powershell
   $keyDir = Join-Path $env:USERPROFILE '.ssh/hamkke-jaram'
   New-Item -ItemType Directory -Force -Path $keyDir | Out-Null
   ssh-keygen -t ed25519 -f (Join-Path $keyDir 'id_ed25519') -C 'hamkke-ai-pc'
   ```

2. Windows의 `id_ed25519.pub`를 인증된 EC2 관리 세션으로 전달한다. EC2의 `hamkke-api/deploy`에서 `sudo bash install-ai-tunnel.sh /path/to/id_ed25519.pub`를 실행한다. 설치 프로그램은 SSH 설정을 검증한 뒤 SSH 서비스를 다시 읽히고 터널 계정에 공개 키 한 개를 등록한다. 마지막에 출력되는 `hamkke-ec2 ssh-ed25519 …` **한 줄 전체**를 Windows의 `$keyDir\known_hosts`에 ASCII 또는 UTF-8(BOM 없음)으로 저장한다. 키 교체 시에도 같은 관리 경로에서 새 키를 확인한다.
3. Windows에서 AI 서비스가 `127.0.0.1:18090`에 바인딩되어 있고 모델이 준비됐는지 확인한 뒤 터널을 실행한다. `service.example.com`에는 EC2의 실제 SSH 호스트 이름이나 IPv4 주소를 넣는다.

   ```powershell
   Invoke-RestMethod 'http://127.0.0.1:18090/ai/v1/health?strict=1'
   powershell.exe -NoProfile -ExecutionPolicy RemoteSigned -File .\Connect-AiTunnel.ps1 -Server service.example.com
   ```

   스크립트가 실행되는 동안 연결이 끊기면 10초 후 다시 시도한다. Ctrl+C로 종료한다. 같은 사용자로 로그인할 때 자동으로 시작하려면 수동 실행을 마친 뒤 위 터널 명령에 `-RegisterAtLogon`을 추가해 한 번 실행한다. 이 명령은 현재 사용자 권한의 `HamkkeJaram-AI-Tunnel` 예약 작업을 등록하고 바로 시작한다. 서비스 운영 중에는 AI 프로세스도 실행하고 PC의 절전 진입을 방지한다.
4. EC2의 관리 세션에서 내부 상태와 외부 HTTPS를 각각 확인한다. AI 상태는 `modelReady: true`, 정책 응답은 `aiObservationEnabled: true`여야 한다.

   ```bash
   curl -fsS http://127.0.0.1:18080/actuator/health
   curl -fsS 'http://127.0.0.1:18090/ai/v1/health?strict=1'
   curl -fsS https://service.example.com/api/v1/policies
   curl -fsS 'https://service.example.com/ai/v1/health?strict=1'
   ```

구성 문법은 [Docker Compose 파일 병합](https://docs.docker.com/reference/compose-file/merge/)과 [OpenSSH 서버 설정](https://man.openbsd.org/sshd_config)을 따른다.
