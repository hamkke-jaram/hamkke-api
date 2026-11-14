#!/usr/bin/env bash
# 함께자람 MySQL 복원 (docs/deploy.md 5.1절 연습, 5.2절 실제 복원).
#
#   ./restore.sh --drill <백업 파일 | s3://…>   복원 연습: 임시 DB(hamkke_restore_drill)에 넣어 표마다 행 수를 보이고 지운다.
#                                               운영 DB 는 건드리지 않는다. 한 달에 한 번 해 본다.
#   ./restore.sh <백업 파일 | s3://…>           운영 DB 를 백업 시점으로 되돌린다. api 를 멈추고, DB 이름을 직접 입력해야 진행한다.
#
# 파일 옆에 .sha256 이 있으면(또는 S3 에 있으면) 먼저 확인한다. .gpg 파일은 BACKUP_PASSPHRASE_FILE 로 푼다.
# 복원한 내용(아동 정보)을 화면이나 파일로 내보내지 않는다. 연습 모드는 표 이름과 행 수만 보인다.
set -euo pipefail
umask 077

DEPLOY_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$DEPLOY_DIR"

DRILL_DB="hamkke_restore_drill"

env_value() {
  [ -f .env ] || return 0
  { grep -E "^$1=" .env || true; } | tail -n 1 | cut -d= -f2- | sed -e 's/^"\(.*\)"$/\1/' -e "s/^'\(.*\)'$/\1/"
}
log() { printf '%s restore: %s\n' "$(date -u +%FT%TZ)" "$*"; }
fail() { log "실패: $*" >&2; exit 1; }
usage() { sed -n '4,7p' "$0" | sed 's/^# \{0,1\}//'; exit 2; }

drill=false
if [ "${1:-}" = "--drill" ]; then drill=true; shift; fi
[ $# -eq 1 ] || usage
source_path="$1"

PASSPHRASE_FILE="${BACKUP_PASSPHRASE_FILE:-$(env_value BACKUP_PASSPHRASE_FILE)}"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

# 1) 파일 가져오기
if [[ "$source_path" == s3://* ]]; then
  command -v aws >/dev/null || fail "aws CLI 가 없어요"
  file="$work/$(basename "$source_path")"
  aws s3 cp --only-show-errors "$source_path" "$file"
  aws s3 cp --only-show-errors "$source_path.sha256" "$file.sha256" 2>/dev/null || true
else
  [ -f "$source_path" ] || fail "파일이 없어요: $source_path"
  file="$source_path"
fi

# 2) 무결성 확인
if [ -f "$file.sha256" ]; then
  (cd "$(dirname "$file")" && sha256sum -c --quiet "$(basename "$file").sha256") || fail "sha256 이 맞지 않아요"
  log "sha256 확인"
else
  log "sha256 파일이 없어 무결성 확인을 건너뛰어요"
fi

# 3) 풀어서 내보내는 명령
decompress() {
  case "$file" in
    *.gpg)
      [ -n "$PASSPHRASE_FILE" ] && [ -r "$PASSPHRASE_FILE" ] || fail "암호화된 백업이에요. BACKUP_PASSPHRASE_FILE 이 필요해요"
      gpg --batch --quiet --decrypt --pinentry-mode loopback --passphrase-file "$PASSPHRASE_FILE" "$file" | gunzip -c ;;
    *.gz) gunzip -c "$file" ;;
    *) fail "확장자가 .sql.gz 또는 .sql.gz.gpg 여야 해요" ;;
  esac
}

if $drill; then
  log "연습 복원 → 임시 DB $DRILL_DB (운영 DB 는 그대로)"
  # 임시 DB 를 만들고 지우는 일은 root 로 한다. 비밀번호는 컨테이너 안의 환경 변수로만 넘긴다.
  root_sql() { docker compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot "$@"' sh "$@"; }
  root_sql -e "DROP DATABASE IF EXISTS \`$DRILL_DB\`; CREATE DATABASE \`$DRILL_DB\` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;"
  started=$(date +%s)
  decompress | root_sql "$DRILL_DB"
  log "복원 $(( $(date +%s) - started ))초. 표마다 행 수:"
  root_sql -N -e "SELECT table_name FROM information_schema.tables WHERE table_schema='$DRILL_DB' ORDER BY table_name" |
    while read -r table; do
      # docker compose exec 는 표준 입력을 넘기므로 /dev/null 을 준다(아니면 남은 표 이름을 먹어 버린다).
      count=$(root_sql -N -e "SELECT COUNT(*) FROM \`$DRILL_DB\`.\`$table\`" </dev/null)
      printf '  %-40s %s\n' "$table" "$count"
    done
  flyway=$(root_sql -N -e "SELECT MAX(version) FROM \`$DRILL_DB\`.flyway_schema_history WHERE success=1" 2>/dev/null || echo "?")
  log "Flyway 마지막 버전: V$flyway"
  root_sql -e "DROP DATABASE \`$DRILL_DB\`;"
  log "연습 끝. 임시 DB 를 지웠어요"
  exit 0
fi

db_name=$(docker compose exec -T mysql sh -c 'printf %s "$MYSQL_DATABASE"' </dev/null)
echo "운영 DB '$db_name' 의 내용이 백업 시점으로 바뀌어요. 그 뒤에 쌓인 기록은 사라져요."
echo "먼저 --drill 로 이 파일을 연습 복원해 보세요."
read -r -p "계속하려면 DB 이름($db_name)을 입력하세요: " answer
[ "$answer" = "$db_name" ] || fail "입력이 달라 멈췄어요"

log "api 를 멈춰요(복원 중 쓰기를 막는다)"
docker compose stop api
if ! decompress | docker compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" exec mysql -u"$MYSQL_USER" "$MYSQL_DATABASE"'; then
  # 반쯤 복원된 DB 로 api 를 켜지 않는다.
  fail "복원 중 멈췄어요. api 는 멈춘 채로 두었어요. 원인을 고친 뒤 다시 복원하세요(되돌리지 않으려면 docker compose start api)"
fi
log "복원 완료. api 를 다시 켜요"
docker compose start api
log "끝. docker compose logs -f api 로 기동을 확인하세요"
