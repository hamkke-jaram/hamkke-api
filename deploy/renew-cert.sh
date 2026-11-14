#!/usr/bin/env bash
# TLS 인증서 갱신과 만료 확인 (docs/deploy.md 3.1절). root 로 실행한다(certbot 이 /etc/letsencrypt 를 쓴다).
#
#   sudo ./renew-cert.sh            certbot renew(webroot) → 갱신된 인증서가 있으면 certs/ 로 복사하고 nginx 를 다시 읽힌다
#   sudo ./renew-cert.sh --dry-run  Let's Encrypt 연습 서버로 갱신 절차만 확인한다(인증서는 바뀌지 않는다)
#   ./renew-cert.sh check [일수]    certs/fullchain.pem 이 [일수](기본 14)일 안에 만료되면 0 이 아닌 값으로 끝난다
#   (deploy-hook)                   certbot 이 갱신 직후 부른다. 직접 부를 일은 없다
#
# 갱신은 nginx 가 /.well-known/acme-challenge/ 를 ./certbot-www 에서 내주는 webroot 방식이다(80 포트를 비우지 않아도 된다).
# 처음 발급(certbot certonly --standalone)은 3장 절차대로 한 번 한다.
set -euo pipefail
umask 022

DEPLOY_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SELF="$DEPLOY_DIR/$(basename "${BASH_SOURCE[0]}")"
cd "$DEPLOY_DIR"

env_value() {
  [ -f .env ] || return 0
  { grep -E "^$1=" .env || true; } | tail -n 1 | cut -d= -f2- | sed -e 's/^"\(.*\)"$/\1/' -e "s/^'\(.*\)'$/\1/"
}
log() { printf '%s renew-cert: %s\n' "$(date -u +%FT%TZ)" "$*"; }
fail() { log "실패: $*" >&2; exit 1; }

SERVER_NAME="$(env_value SERVER_NAME)"
CERT_DIR="$(env_value CERT_DIR)"
CERT_DIR="${CERT_DIR:-./certs}"
case "$CERT_DIR" in /*) ;; *) CERT_DIR="$DEPLOY_DIR/${CERT_DIR#./}" ;; esac

check() {
  local days="${1:-14}"
  [[ "$days" =~ ^[0-9]+$ ]] || fail "일수는 정수여야 해요"
  local cert="$CERT_DIR/fullchain.pem"
  [ -f "$cert" ] || fail "$cert 이 없어요"
  local end
  end=$(openssl x509 -in "$cert" -noout -enddate | cut -d= -f2)
  if openssl x509 -in "$cert" -noout -checkend $((days * 86400)) >/dev/null; then
    log "만료 $end — ${days}일 안에 만료되지 않아요"
  else
    log "만료 $end — ${days}일 안에 만료돼요. sudo ./renew-cert.sh 를 실행하세요" >&2
    return 1
  fi
}

deploy_hook() {
  # certbot 이 갱신에 성공한 인증서마다 RENEWED_LINEAGE(/etc/letsencrypt/live/<도메인>)를 넘긴다.
  [ -n "${RENEWED_LINEAGE:-}" ] || fail "RENEWED_LINEAGE 가 없어요(certbot 이 부를 때만 쓴다)"
  if [ -n "$SERVER_NAME" ] && [ "$(basename "$RENEWED_LINEAGE")" != "$SERVER_NAME" ]; then
    log "다른 도메인($(basename "$RENEWED_LINEAGE"))이라 건너뛰어요"
    return 0
  fi
  mkdir -p "$CERT_DIR"
  install -m 644 "$RENEWED_LINEAGE/fullchain.pem" "$CERT_DIR/fullchain.pem.new"
  install -m 600 "$RENEWED_LINEAGE/privkey.pem" "$CERT_DIR/privkey.pem.new"
  mv -f "$CERT_DIR/fullchain.pem.new" "$CERT_DIR/fullchain.pem"
  mv -f "$CERT_DIR/privkey.pem.new" "$CERT_DIR/privkey.pem"
  # 설정을 먼저 검사하고, 맞으면 연결을 끊지 않고 다시 읽힌다.
  docker compose exec -T web nginx -t
  docker compose exec -T web nginx -s reload
  log "인증서를 바꾸고 nginx 를 다시 읽혔어요"
  check 30 || true
}

case "${1:-}" in
  check) check "${2:-14}"; exit $? ;;
  deploy-hook) deploy_hook; exit 0 ;;
esac

[ "$(id -u)" -eq 0 ] || fail "root 로 실행해 주세요(sudo ./renew-cert.sh)"
command -v certbot >/dev/null || fail "certbot 이 없어요(sudo apt install -y certbot)"
mkdir -p "$DEPLOY_DIR/certbot-www"

extra=()
[ "${1:-}" = "--dry-run" ] && extra+=(--dry-run)
# --webroot: 처음 standalone 으로 받았어도 이 실행부터 webroot 로 갱신한다(설정에 저장된다).
# --deploy-hook: 실제로 갱신된 인증서가 있을 때만 불린다(--dry-run 에서는 불리지 않는다).
certbot renew --quiet --webroot -w "$DEPLOY_DIR/certbot-www" --deploy-hook "$SELF deploy-hook" "${extra[@]}"
log "certbot renew 끝(갱신할 때가 아니면 아무것도 바꾸지 않아요)"
