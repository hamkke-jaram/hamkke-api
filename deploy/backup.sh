#!/usr/bin/env bash
# 함께자람 MySQL 백업 (docs/deploy.md 5장).
#   mysqldump → gzip → (선택) gpg 대칭 암호화 → 이 서버의 BACKUP_DIR → (선택) S3 업로드(서버 측 암호화)
#   → 이 서버에서는 BACKUP_KEEP_DAYS 일이 지난 파일을 지운다. S3 보관 기간은 버킷 수명 주기(s3-lifecycle.json, 30일)로 정한다.
#   이 서버의 백업을 먼저 끝낸 뒤 S3 에 올린다. S3 쪽이 실패해도 이 서버의 백업은 남는다.
#   어느 단계든 실패하면 notify.sh 로 알린다(syslog, ALERT_WEBHOOK_URL).
#
# 사용: deploy 폴더에서 ./backup.sh      (cron 예는 docs/deploy.md 5장)
# 설정: deploy/.env 에서 아래 이름만 한 줄씩 꺼내 읽는다(.env 를 통째로 셸에서 실행하지 않는다).
#   BACKUP_DIR                 백업 파일을 둘 폴더(기본 $HOME/backup)
#   BACKUP_KEEP_DAYS           이 서버에 두는 일수(기본 7, 1 이상)
#   BACKUP_INCLUDE_ROUTINES    저장 프로시저·함수도 덤프할지(기본 true, MySQL 8.4에서 전역 SELECT 필요)
#   BACKUP_S3_URI              버킷의 mysql/ 아래여야 한다. 예: s3://hamkke-backup/mysql   (비우면 올리지 않는다)
#   BACKUP_S3_SSE              AES256(기본) 또는 aws:kms
#   BACKUP_S3_KMS_KEY_ID       aws:kms 일 때 쓸 KMS 키(선택)
#   BACKUP_PASSPHRASE_FILE     정하면 gpg(AES256)로 한 번 더 암호화한다. 파일 권한 600, 백업과 다른 곳에 사본을 둔다
#
# 백업에는 아동 정보와 AI 관찰 기록(글자)이 들어 있다. 사진은 어디에도 저장되지 않으므로 백업에도 없다.
# 비밀번호는 명령줄에 쓰지 않는다(컨테이너 안에서 MYSQL_PWD 로 넘긴다). 로그에는 파일 이름·크기만 남긴다.
set -Eeuo pipefail
umask 077
# cron 의 PATH 는 /usr/bin:/bin 뿐이다. aws CLI(공식 설치는 /usr/local/bin, snap 은 /snap/bin)를 찾을 수 있게 넓힌다.
export PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/snap/bin${PATH:+:$PATH}"

DEPLOY_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$DEPLOY_DIR"

env_value() {
  # .env 에서 한 이름의 값만 꺼낸다. 없으면 빈 값.
  [ -f .env ] || return 0
  { grep -E "^$1=" .env || true; } | tail -n 1 | cut -d= -f2- | sed -e 's/^"\(.*\)"$/\1/' -e "s/^'\(.*\)'$/\1/"
}

log() { printf '%s backup: %s\n' "$(date -u +%FT%TZ)" "$*"; }
alerted=false
alert() {
  $alerted && return 0
  alerted=true
  bash "$DEPLOY_DIR/notify.sh" "백업 실패: $*" >/dev/null 2>&1 || log "알림을 보내지 못했어요(notify.sh)" >&2
}
fail() { log "실패: $*" >&2; alert "$*"; exit 1; }
# fail 을 거치지 않고 멈춘 경우(덤프 도중 docker 오류 등)에도 알린다.
# 파이프라인 안의 서브셸도 트랩을 물려받으므로(set -E) 본 셸에서만 알린다(아니면 두 번 간다).
main_pid=$$
trap '[ "$BASHPID" = "$main_pid" ] && alert "${LINENO}번째 줄에서 멈췄어요. 로그를 확인하세요"' ERR

BACKUP_DIR="${BACKUP_DIR:-$(env_value BACKUP_DIR)}"
BACKUP_DIR="${BACKUP_DIR:-$HOME/backup}"
KEEP_DAYS="${BACKUP_KEEP_DAYS:-$(env_value BACKUP_KEEP_DAYS)}"
KEEP_DAYS="${KEEP_DAYS:-7}"
S3_URI="${BACKUP_S3_URI:-$(env_value BACKUP_S3_URI)}"
S3_SSE="${BACKUP_S3_SSE:-$(env_value BACKUP_S3_SSE)}"
S3_SSE="${S3_SSE:-AES256}"
KMS_KEY_ID="${BACKUP_S3_KMS_KEY_ID:-$(env_value BACKUP_S3_KMS_KEY_ID)}"
PASSPHRASE_FILE="${BACKUP_PASSPHRASE_FILE:-$(env_value BACKUP_PASSPHRASE_FILE)}"
INCLUDE_ROUTINES="${BACKUP_INCLUDE_ROUTINES:-$(env_value BACKUP_INCLUDE_ROUTINES)}"
routine_args=()
case "${INCLUDE_ROUTINES:-true}" in
  true) routine_args+=(--routines) ;;
  false) ;;
  *) fail "BACKUP_INCLUDE_ROUTINES 는 true 또는 false 여야 해요" ;;
esac

[[ "$KEEP_DAYS" =~ ^[1-9][0-9]*$ ]] || fail "BACKUP_KEEP_DAYS 는 1 이상의 정수여야 해요"
if [ -n "$PASSPHRASE_FILE" ]; then
  # 암호화하기로 했으면 암호화하지 못할 때 평문으로 남기지 않는다.
  [ -r "$PASSPHRASE_FILE" ] || fail "BACKUP_PASSPHRASE_FILE 을 읽을 수 없어요"
  command -v gpg >/dev/null || fail "gpg 가 없어요(sudo apt install -y gnupg)"
fi

# S3 설정은 여기서 확인만 해 두고, 문제가 있어도 이 서버의 백업은 먼저 만든다.
s3_problem=""
if [ -n "$S3_URI" ]; then
  if [[ ! "${S3_URI%/}/" =~ ^s3://[^/]+/mysql/ ]]; then
    s3_problem="BACKUP_S3_URI 는 버킷의 mysql/ 아래여야 해요(예: s3://hamkke-backup/mysql). 그래야 30일 수명 주기 규칙(s3-lifecycle.json)이 적용돼요"
  elif ! command -v aws >/dev/null; then
    s3_problem="aws CLI 가 없어요. docs/deploy.md 5장대로 설치하세요"
  else
    case "$S3_SSE" in AES256|aws:kms) ;; *) s3_problem="BACKUP_S3_SSE 는 AES256 또는 aws:kms 여야 해요" ;; esac
  fi
fi

mkdir -p "$BACKUP_DIR"
chmod 700 "$BACKUP_DIR"

stamp="$(date -u +%Y%m%dT%H%M%SZ)"
name="hamkke-$stamp.sql.gz"
[ -n "$PASSPHRASE_FILE" ] && name="$name.gpg"
target="$BACKUP_DIR/$name"
partial="$target.partial"
trap 'rm -f "$partial"' EXIT

log "덤프 시작 → $name"
# --single-transaction: 잠그지 않고 일관된 덤프. --no-tablespaces: 앱 계정에 PROCESS 권한이 없어도 된다.
dump() {
  docker compose exec -T mysql sh -c \
    'MYSQL_PWD="$MYSQL_PASSWORD" exec mysqldump -u"$MYSQL_USER" --single-transaction --no-tablespaces --triggers --set-gtid-purged=OFF --default-character-set=utf8mb4 "$@" "$MYSQL_DATABASE"' sh "${routine_args[@]}"
}
if [ -n "$PASSPHRASE_FILE" ]; then
  dump | gzip -9 | gpg --batch --yes --quiet --symmetric --cipher-algo AES256 --pinentry-mode loopback \
    --passphrase-file "$PASSPHRASE_FILE" -o "$partial"
else
  dump | gzip -9 > "$partial"
  gzip -t "$partial" || fail "압축 파일 검사에 실패했어요"
fi
[ -s "$partial" ] || fail "백업 파일이 비어 있어요"
mv "$partial" "$target"
(cd "$BACKUP_DIR" && sha256sum "$name" > "$name.sha256")
log "완료 $(du -h "$target" | cut -f1)"

# 이 서버에 둔 오래된 백업을 지운다(S3 는 수명 주기 규칙이 지운다).
# -mtime +N 은 N+1 일이 지나야 지워서 하루 더 남는다. 분 단위로 정확히 N 일이 지난 파일만 지운다.
find "$BACKUP_DIR" -maxdepth 1 -type f -name 'hamkke-*.sql.gz*' -mmin +$((KEEP_DAYS * 1440)) -print -delete | sed 's/^/  지움: /'
log "보관 정리 완료(${KEEP_DAYS}일)"

if [ -n "$S3_URI" ]; then
  [ -z "$s3_problem" ] || fail "이 서버에는 백업했지만 S3 에 올리지 못했어요: $s3_problem"
  sse_args=(--sse "$S3_SSE")
  [ "$S3_SSE" = "aws:kms" ] && [ -n "$KMS_KEY_ID" ] && sse_args+=(--sse-kms-key-id "$KMS_KEY_ID")
  aws s3 cp --only-show-errors "${sse_args[@]}" "$target" "${S3_URI%/}/$name" \
    || fail "이 서버에는 백업했지만 S3 업로드에 실패했어요($name)"
  aws s3 cp --only-show-errors "${sse_args[@]}" "$target.sha256" "${S3_URI%/}/$name.sha256" \
    || fail "이 서버에는 백업했지만 S3 에 .sha256 을 올리지 못했어요($name)"
  log "S3 업로드 완료(서버 측 암호화 $S3_SSE)"
fi
