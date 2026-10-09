#!/usr/bin/env bash
# Throwaway test targets as LXD system containers, standing in for the wiz-* Hyper-V VMs
# until those exist. Both run systemd and sshd, so the whole install flow can be exercised.
#
#   wiz-ubuntu  Ubuntu 24.04, user "wiz" whose sudo ASKS for a password
#   wiz-debian  Debian 12,    user "wiz" with PASSWORDLESS sudo
#
# Each gets a snapshot named "clean". Put one back with:
#   lxc restore wiz-ubuntu clean
#
# Usage: tools/lxd-targets.sh <test-public-key-file> <sudo-password-for-wiz-ubuntu>
# Writes nothing outside LXD; prints the targets.local.properties lines to use.
set -euo pipefail
pub="$1"; pass="$2"

provision() {
    local name="$1" sudo_mode="$2"
    lxc exec "$name" -- sh -c 'until systemctl is-system-running 2>/dev/null | grep -qE "running|degraded"; do sleep 1; done'
    lxc exec "$name" -- sh -c 'export DEBIAN_FRONTEND=noninteractive;
        command -v sshd >/dev/null && command -v sudo >/dev/null ||
        { apt-get update -qq && apt-get install -y -qq openssh-server sudo; }'
    lxc exec "$name" -- sh -c 'id wiz >/dev/null 2>&1 || useradd -m -s /bin/bash -G sudo wiz'
    lxc exec "$name" -- sh -c "echo 'wiz:$pass' | chpasswd"
    lxc exec "$name" -- sh -c 'install -d -m700 -o wiz -g wiz ~wiz/.ssh'
    lxc file push "$pub" "$name/home/wiz/.ssh/authorized_keys" --uid "$(lxc exec "$name" -- id -u wiz)" --gid "$(lxc exec "$name" -- id -g wiz)" --mode 600
    if [ "$sudo_mode" = nopasswd ]; then
        lxc exec "$name" -- sh -c 'echo "wiz ALL=(ALL) NOPASSWD:ALL" > /etc/sudoers.d/90-wiz && chmod 440 /etc/sudoers.d/90-wiz'
    fi
    # Password logins on so the first-contact (password, then install key) path can be tested.
    lxc exec "$name" -- sh -c 'printf "PasswordAuthentication yes\n" > /etc/ssh/sshd_config.d/60-llmwizard-test.conf; systemctl enable --now ssh >/dev/null 2>&1 || systemctl restart ssh'
    lxc exec "$name" -- systemctl restart ssh
    lxc delete "$name/clean" </dev/null 2>/dev/null || true
    lxc snapshot "$name" clean </dev/null
    echo "$name.host=$(lxc list "$name" -c4 --format csv | cut -d' ' -f1)"
}

[ "${3:-}" = debian-only ] || provision wiz-ubuntu password
provision wiz-debian nopasswd
