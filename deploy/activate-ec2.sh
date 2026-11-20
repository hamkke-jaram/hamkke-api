#!/usr/bin/env bash
# Dedicated Ubuntu 24.04 EC2; trusted root-owned sibling checkouts in /opt/hamkke.
# The public IPv4 (or DNS name pointing here) must receive TCP 80/443. The host's
# EBS volume should be encrypted. The SSH tunnel is installed separately.
# From the Windows operator, send the decoded DPAPI key on stdin of pinned SSH:
#   ssh -T ... sudo -n bash /opt/hamkke/hamkke-api/deploy/activate-ec2.sh PUBLIC_IP_OR_DNS
# Run from any directory. On retries send the same key. Never pass it as an argument.
set +x
set -Eeuo pipefail
umask 077

fail() { echo "$*" >&2; exit 1; }
deploy=/opt/hamkke/hamkke-api/deploy
[[ $EUID == 0 && $# == 1 && ! -t 0 ]] || fail 'Run as root with one hostname argument and the PC key on standard input.'
cd "$deploy"
[[ -f compose.ec2-pc.yml && -f ../../hamkke-web/Dockerfile ]] || fail 'Stage the deployment overlay and hamkke-web checkout first.'
source /etc/os-release
[[ $ID == ubuntu && $VERSION_ID == 24.04 ]] || fail 'Ubuntu 24.04 is required.'
unsafe=$(find /opt/hamkke -xdev \( -name .git -o -name certbot-www \) -prune -o \( ! -user root -o \( ! -type l -perm /022 \) \) -print -quit)
[[ -z $unsafe ]] || fail "Use root-owned project files without group/world write permission: $unsafe"
exec 9>/run/lock/hamkke-activate.lock
flock -n 9 || fail 'Another activation is running.'

name=$1
if [[ $name =~ ^[0-9.]+$ ]]; then
    python3 -c 'import ipaddress,sys; a=ipaddress.IPv4Address(sys.argv[1]); sys.exit(not a.is_global or a.is_multicast)' "$name" || fail 'Enter the assigned public IPv4 address.'
    identifier=(--ip-address "$name" --preferred-profile shortlived)
    warn_days=1
else
    label='[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?'
    [[ ${#name} -le 253 && $name =~ ^($label\.)+$label$ ]] || fail 'Enter a lowercase DNS name pointing to this host.'
    identifier=(-d "$name")
    warn_days=14
fi
IFS= read -r key || [[ -n $key ]] || fail 'Missing key on standard input.'
key=${key%$'\r'}  # CRLF from Windows
[[ $key =~ ^[A-Za-z0-9+/]{64}$ ]] || fail 'Expected the 48-byte base64 key from the Windows launcher.'
exec </dev/null
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y ca-certificates curl openssl snapd cron
if ! docker compose version >/dev/null 2>&1; then
    apt-get install -y docker.io docker-compose-v2 docker-buildx
fi
systemctl enable --now docker cron
snap list certbot >/dev/null 2>&1 || snap install --classic certbot
certbot=/snap/bin/certbot
dpkg --compare-versions "$("$certbot" --version | awk '{print $2}')" ge 5.4.0 || snap refresh certbot
dpkg --compare-versions "$("$certbot" --version | awk '{print $2}')" ge 5.4.0 || fail 'Certbot 5.4 or newer is required.'

compose_files=docker-compose.yml:compose.ec2-pc.yml
webroot=$deploy/certbot-www
scratch=$(mktemp -d)
http_pid=
env_tmp=
probe=
stop_http() {
    if [[ -n $http_pid ]]; then
        kill "$http_pid" 2>/dev/null || true
        wait "$http_pid" 2>/dev/null || true
        http_pid=
    fi
}
cleanup() {
    stop_http
    if [[ -n $env_tmp ]]; then rm -f -- "$env_tmp"; fi
    if [[ -n $probe ]]; then rm -f -- "$probe"; fi
    rm -rf -- "$scratch"
}
trap cleanup EXIT
if [[ -e .env || -L .env ]]; then
    [[ -f .env && ! -L .env && $(stat -c '%u:%a' .env) == 0:600 ]] || fail 'Existing .env must be a root-owned regular file with mode 0600.'
    grep -qxF "# Managed by activate-ec2.sh" .env && grep -qxF "SERVER_NAME=$name" .env && grep -qxF "COMPOSE_FILE=$compose_files" .env || fail 'Existing deployment settings differ. Review .env before continuing.'
    [[ $(sed -n 's/^AI_SHARED_SECRET=//p' .env) == "$key" ]] || fail 'Existing AI signing key differs from the PC. Credentials were preserved.'
else
    docker info >/dev/null
    existing_volumes=$(docker volume ls --format '{{.Name}}')
    if grep -qxF 'hamkke-jaram_mysql-data' <<<"$existing_volumes"; then fail 'A database volume already exists. Restore its original .env before continuing.'; fi
    env_tmp=$(mktemp "$deploy/.env.new.XXXXXXXX")
    db_pass=$(openssl rand -hex 32)
    db_root=$(openssl rand -hex 32)
    jwt=$(openssl rand -hex 48)
    cat > "$env_tmp" <<EOF
# Managed by activate-ec2.sh
COMPOSE_FILE=$compose_files
SERVER_NAME=$name
DB_NAME=hamkke
DB_USER=hamkke
DB_PASSWORD=$db_pass
DB_ROOT_PASSWORD=$db_root
JWT_SECRET=$jwt
AI_ENABLED=true
AI_SHARED_SECRET=$key
BACKUP_DIR=/var/backups/hamkke
# The shipped schema has no stored procedures or functions; the DB user has database-local grants.
BACKUP_INCLUDE_ROUTINES=false
EOF
    mv -T "$env_tmp" .env
    env_tmp=
    unset db_pass db_root jwt
fi
unset key
docker compose config -q
docker compose build api
docker compose build web
install -d -m 0755 "$webroot" "$webroot/.well-known" "$webroot/.well-known/acme-challenge"
install -d -m 0700 certs /var/backups/hamkke
probe=$(mktemp "$webroot/.well-known/acme-challenge/hamkke-XXXXXXXX")
marker=$(openssl rand -hex 16)
printf '%s' "$marker" > "$probe"
chmod 0644 "$probe"
probe_url="http://127.0.0.1/.well-known/acme-challenge/${probe##*/}"
probe_ok() { [[ $(curl -fsS --max-time 3 --noproxy '*' -H "Host: $name" "$probe_url" 2>/dev/null) == "$marker" ]]; }
if ! probe_ok; then
    # Only the dedicated challenge directory is served, never application files.
    python3 -m http.server 80 --bind 0.0.0.0 --directory "$webroot" >"$scratch/http.log" 2>&1 &
    http_pid=$!
    for _ in {1..20}; do probe_ok && break; sleep 0.25; done
    probe_ok || fail 'TCP 80 is occupied or the ACME webroot is not being served.'
fi
(umask 022; "$certbot" certonly -n --agree-tos --register-unsafely-without-email --keep-until-expiring --cert-name "$name" \
    --webroot -w "$webroot" "${identifier[@]}")
stop_http
lineage=/etc/letsencrypt/live/$name
install -m 0644 "$lineage/fullchain.pem" certs/fullchain.pem.new
install -m 0600 "$lineage/privkey.pem" certs/privkey.pem.new
mv -f certs/fullchain.pem.new certs/fullchain.pem
mv -f certs/privkey.pem.new certs/privkey.pem
docker compose up -d --wait --wait-timeout 300 mysql api web
docker compose exec -T web nginx -t
docker compose exec -T web nginx -s reload
curl -fsS --retry 6 --retry-all-errors --noproxy '*' --resolve "$name:443:127.0.0.1" \
    "https://$name/api/v1/policies" | python3 -c 'import json,sys; sys.exit(json.load(sys.stdin).get("aiObservationEnabled") is not True)'

hooks=/etc/letsencrypt/renewal-hooks/deploy
install -d -m 0755 "$hooks"
printf '#!/bin/sh\nexec /bin/bash %s/renew-cert.sh deploy-hook\n' "$deploy" > "$scratch/hook"
install -m 0755 "$scratch/hook" "$hooks/60-hamkke-nginx"
systemctl enable --now snap.certbot.renew.timer
cat > "$scratch/cron" <<EOF
SHELL=/bin/bash
PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/snap/bin
17 3 * * * root /bin/bash $deploy/backup.sh >>/var/log/hamkke-backup.log 2>&1
30 5 1 * * root /bin/bash $deploy/restore.sh --drill latest >>/var/log/hamkke-backup.log 2>&1
35 8 * * * root /bin/bash $deploy/renew-cert.sh check $warn_days >>/var/log/hamkke-renew-cert.log 2>&1
EOF
install -m 0644 "$scratch/cron" /etc/cron.d/hamkke-jaram
bash "$deploy/backup.sh"
(umask 022; "$certbot" renew -n --agree-tos --register-unsafely-without-email --cert-name "$name" --dry-run)
printf 'Backend ready at https://%s. Check /ai/v1/health?strict=1 after connecting the PC tunnel.\n' "$name"
