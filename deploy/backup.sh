#!/usr/bin/env bash
# 함께자람 MySQL 백업 (docs/deploy.md 5장).
#   mysqldump → gzip → (선택) gpg 대칭 암호화 → 이 서버의 BACKUP_DIR → (선택) S3 업로드(서버 측 암호화)
#   → 이 서버에서는 BACKUP_KEEP_DAYS 일이 지난 파일을 지운다. S3 보관 기간은 버킷 수명 주기(s3-lifecycle.json, 30일)로 정한다.
#
# 사용: deploy 폴더에서 ./backup.sh      (cron 예는 docs/deploy.md 5장)
# 설정: deploy/.env 에서 아래 이름만 한 줄씩 꺼내 읽는다(.env 를 통째로 셸에서 실행하지 않는다).
#   BACKUP_DIR                 백업 파일을 둘 폴더(기본 $HOME/backup)
#   BACKUP_KEEP_DAYS           이 서버에 두는 일수(기본 7)
#   BACKUP_S3_URI              예: s3://hamkke-backup/mysql   (비우면 올리지 않는다)
#   BACKUP_S3_SSE              AES256(기본) 또는 aws:kms
#   BACKUP_S3_KMS_KEY_ID       aws:kms 일 때 쓸 KMS 키(선택)
#   BACKUP_PASSPHRASE_FILE     정하면 gpg(AES256)로 한 번 더 암호화한다. 파일 권한 600, 백업과 다른 곳에 사본을 둔다
#
# 백업에는 아동 정보와 AI 관찰 기록(글자)이 들어 있다. 사진은 어디에도 저장되지 않으므로 백업에도 없다.
# 비밀번호는 명령줄에 쓰지 않는다(컨테이너 안에서 MYSQL_PWD 로 넘긴다). 로그에는 파일 이름·크기만 남긴다.
set -euo pipefail
umask 077

DEPLOY_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$DEPLOY_DIR"

env_value() {
  # .env 에서 한 이름의 값만 꺼낸다. 없으면 빈 값.
  [ -f .env ] || return 0
  { grep -E "^$1=" .env || true; } | tail -n 1 | cut -d= -f2- | sed -e 's/^"\(.*\)"$/\1/' -e "s/^'\(.*\)'$/\1/"
}

log() { printf '%s backup: %s\n' "$(date -u +%FT%TZ)" "$*"; }
fail() { log "실패: $*" >&2; exit 1; }

BACKUP_DIR="${BACKUP_DIR:-$(env_value BACKUP_DIR)}"
BACKUP_DIR="${BACKUP_DIR:-$HOME/backup}"
KEEP_DAYS="${BACKUP_KEEP_DAYS:-$(env_value BACKUP_KEEP_DAYS)}"
KEEP_DAYS="${KEEP_DAYS:-7}"
S3_URI="${BACKUP_S3_URI:-$(env_value BACKUP_S3_URI)}"
S3_SSE="${BACKUP_S3_SSE:-$(env_value BACKUP_S3_SSE)}"
S3_SSE="${S3_SSE:-AES256}"
KMS_KEY_ID="${BACKUP_S3_KMS_KEY_ID:-$(env_value BACKUP_S3_KMS_KEY_ID)}"
PASSPHRASE_FILE="${BACKUP_PASSPHRASE_FILE:-$(env_value BACKUP_PASSPHRASE_FILE)}"

[[ "$KEEP_DAYS" =~ ^[0-9]+$ ]] || fail "BACKUP_KEEP_DAYS 는 0 이상의 정수여야 해요"
case "$S3_SSE" in AES256|aws:kms) ;; *) fail "BACKUP_S3_SSE 는 AES256 또는 aws:kms 여야 해요" ;; esac
if [ -n "$PASSPHRASE_FILE" ]; then
  [ -r "$PASSPHRASE_FILE" ] || fail "BACKUP_PASSPHRASE_FILE 을 읽을 수 없어요"
  command -v gpg >/dev/null || fail "gpg 가 없어요(sudo apt install -y gnupg)"
fi
if [ -n "$S3_URI" ]; then
  [[ "$S3_URI" == s3://* ]] || fail "BACKUP_S3_URI 는 s3:// 로 시작해야 해요"
  command -v aws >/dev/null || fail "aws CLI 가 없어요"
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
    'MYSQL_PWD="$MYSQL_PASSWORD" exec mysqldump -u"$MYSQL_USER" --single-transaction --no-tablespaces --routines --triggers --set-gtid-purged=OFF --default-character-set=utf8mb4 "$MYSQL_DATABASE"'
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

if [ -n "$S3_URI" ]; then
  sse_args=(--sse "$S3_SSE")
  [ "$S3_SSE" = "aws:kms" ] && [ -n "$KMS_KEY_ID" ] && sse_args+=(--sse-kms-key-id "$KMS_KEY_ID")
  aws s3 cp --only-show-errors "${sse_args[@]}" "$target" "${S3_URI%/}/$name"
  aws s3 cp --only-show-errors "${sse_args[@]}" "$target.sha256" "${S3_URI%/}/$name.sha256"
  log "S3 업로드 완료(서버 측 암호화 $S3_SSE)"
fi

# 이 서버에 둔 오래된 백업을 지운다(S3 는 수명 주기 규칙이 지운다).
find "$BACKUP_DIR" -maxdepth 1 -type f -name 'hamkke-*.sql.gz*' -mtime +"$KEEP_DAYS" -print -delete | sed 's/^/  지움: /'
log "보관 정리 완료(${KEEP_DAYS}일)"
