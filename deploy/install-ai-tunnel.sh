#!/usr/bin/env bash
# Install on a dedicated Ubuntu EC2 host using its existing SSH service.
set -euo pipefail

[[ $EUID == 0 && $# -ge 1 && $# -le 2 ]] || { echo 'Usage: sudo bash install-ai-tunnel.sh windows-id_ed25519.pub [ai|api]' >&2; exit 2; }
case ${2:-ai} in
    ai)  service=ai;  port=18090 ;;
    api) service=api; port=18980 ;;
    *) echo 'The service must be ai or api.' >&2; exit 2 ;;
esac
user=hamkke-$service-tunnel
keydir=/etc/ssh/authorized_keys
config=/etc/ssh/sshd_config.d/10-$user.conf
source_dir=$(cd "$(dirname "$0")" && pwd)
exec 9>/run/lock/hamkke-ai-tunnel.lock
flock -x 9
ssh-keygen -lf "$1" >/dev/null
# Only a single ed25519 public key is accepted. Drop any comment or options.
key=$(awk '{sub(/\r$/, "")} NR == 1 && $1 == "ssh-ed25519" && $2 ~ /^[[:alnum:]+\/=]+$/ {k=$1 " " $2}
           END {if (NR != 1 || !k) exit 1; print k}' "$1")

if getent passwd "$user" >/dev/null; then
    [[ $(getent passwd "$user" | cut -d: -f6-7) == /nonexistent:/usr/sbin/nologin ]] || {
        echo "The existing $user account has a different home or shell." >&2; exit 1;
    }
else
    useradd --system --no-create-home --home-dir /nonexistent --shell /usr/sbin/nologin "$user"
fi

work=$(mktemp -d)
rollback=false
staged_key=
cleanup() {
    if $rollback; then
        if [[ -f "$work/previous" ]]; then
            install -m 644 "$work/previous" "$config"
        else
            rm -f "$config"
        fi
    fi
    [[ -z "$staged_key" ]] || rm -f "$staged_key"
    rm -f "$work/key" "$work/previous"
    rmdir "$work"
}
trap cleanup EXIT
[[ ! -f "$config" ]] || cp "$config" "$work/previous"
rollback=true
install -m 644 "$source_dir/hamkke-$service-sshd.conf" "$config"
/usr/sbin/sshd -t
effective=$(/usr/sbin/sshd -T -C "user=$user,host=localhost,addr=127.0.0.1")
for rule in 'authenticationmethods publickey' "authorizedkeysfile $keydir/$user" \
            'authorizedkeyscommand none' 'trustedusercakeys none' 'maxsessions 0' \
            'allowtcpforwarding remote' "permitlisten 127.0.0.1:$port" 'permitopen none' \
            'gatewayports no' 'allowstreamlocalforwarding no'; do
    grep -Fxq "$rule" <<< "$effective" || { echo "Ineffective sshd rule: $rule" >&2; exit 1; }
done
systemctl reload ssh
rollback=false
# Write the key only after the running daemon has the account restrictions.
install -d -m 755 "$keydir"
printf 'restrict,port-forwarding,permitlisten="127.0.0.1:%s" %s\n' "$port" "$key" > "$work/key"
staged_key=$(mktemp "$keydir/.$user.XXXXXXXX")
install -m 644 "$work/key" "$staged_key"
mv -f "$staged_key" "$keydir/$user"
# Pin this PUBLIC server key on Windows through this authenticated admin session.
awk '{print "hamkke-ec2", $1, $2}' /etc/ssh/ssh_host_ed25519_key.pub
