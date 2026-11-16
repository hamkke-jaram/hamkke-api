#!/usr/bin/env bash
# 운영 알림 (docs/deploy.md 5.3절). 백업·복원 연습·인증서 스크립트가 실패하거나 사람이 봐야 할 일이 생기면 부른다.
#
#   ./notify.sh "보낼 말"
#
# 늘 syslog(logger -t hamkke)에 남기고, deploy/.env 의 ALERT_WEBHOOK_URL 이 있으면 그 주소로도 보낸다.
# 웹훅 본문은 {"text": …, "content": …} 라서 Slack·Discord 들어오는 웹훅 어느 쪽에도 그대로 쓸 수 있다.
# 메시지에는 아동 정보·비밀번호·토큰을 넣지 않는다(단계, 파일 이름, 호스트 이름만). 웹훅 주소는 명령줄에 드러나지 않게 넘긴다.
set -uo pipefail

DEPLOY_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

env_value() {
  [ -f "$DEPLOY_DIR/.env" ] || return 0
  { grep -E "^$1=" "$DEPLOY_DIR/.env" || true; } | tail -n 1 | cut -d= -f2- | sed -e 's/^"\(.*\)"$/\1/' -e "s/^'\(.*\)'$/\1/"
}

[ $# -ge 1 ] || { echo "사용: $0 \"보낼 말\"" >&2; exit 2; }
msg="[함께자람 $(hostname)] $*"

logger -t hamkke -- "$msg" 2>/dev/null || true

url="${ALERT_WEBHOOK_URL:-$(env_value ALERT_WEBHOOK_URL)}"
[ -n "$url" ] || exit 0
[[ "$url" == https://* ]] || { echo "ALERT_WEBHOOK_URL 은 https:// 로 시작해야 해요" >&2; exit 1; }
command -v curl >/dev/null || { echo "curl 이 없어요(sudo apt install -y curl)" >&2; exit 1; }

# JSON 문자열로 옮긴다: 역슬래시·따옴표를 이스케이프하고 줄바꿈은 \n, 그 밖의 제어 문자는 공백으로.
esc="${msg//\\/\\\\}"
esc="${esc//\"/\\\"}"
esc="${esc//$'\n'/\\n}"
esc="$(printf '%s' "$esc" | tr '\000-\037' ' ')"

if ! curl -fsS --max-time 10 -H 'Content-Type: application/json' \
     -d "{\"text\":\"$esc\",\"content\":\"$esc\"}" \
     -K <(printf 'url = "%s"\n' "$url") >/dev/null; then
  echo "웹훅으로 알림을 보내지 못했어요(syslog 에는 남겼어요)" >&2
  exit 1
fi
