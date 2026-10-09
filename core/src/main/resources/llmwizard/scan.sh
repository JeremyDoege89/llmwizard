# llmwizard hardware scan. Read-only: it installs nothing, writes nothing, never uses sudo
# to change anything, and only reads files and asks tools for their version or status.
# Each section is printed between "@@ name" markers; a missing tool skips its section.
# The app shows this exact text before running it.
set +e

section() { printf '\n@@ %s\n' "$1"; }
have() { command -v "$1" >/dev/null 2>&1; }

section os
cat /etc/os-release 2>/dev/null
printf 'ARCH=%s\n' "$(uname -m 2>/dev/null)"
printf 'KERNEL=%s\n' "$(uname -r 2>/dev/null)"

section init
if [ -d /run/systemd/system ]; then echo systemd=yes; else echo systemd=no; fi
printf 'pid1=%s\n' "$(cat /proc/1/comm 2>/dev/null)"

section virt
if have systemd-detect-virt; then printf 'virt=%s\n' "$(systemd-detect-virt 2>/dev/null)"; fi
if have systemd-detect-virt; then printf 'container=%s\n' "$(systemd-detect-virt --container 2>/dev/null)"; fi

section lscpu
have lscpu && lscpu 2>/dev/null

section cpuflags
grep -m1 '^flags' /proc/cpuinfo 2>/dev/null
grep -m1 '^model name' /proc/cpuinfo 2>/dev/null
printf 'nproc=%s\n' "$(nproc 2>/dev/null)"

section numa
if [ -d /sys/devices/system/node ]; then
    for n in /sys/devices/system/node/node[0-9]*; do [ -d "$n" ] && printf '%s %s\n' "${n##*/}" "$(cat "$n/cpulist" 2>/dev/null)"; done
fi

section meminfo
grep -E '^(MemTotal|MemAvailable|SwapTotal|SwapFree):' /proc/meminfo 2>/dev/null

section pci
for d in /sys/bus/pci/devices/*; do
    [ -f "$d/class" ] || continue
    c=$(cat "$d/class" 2>/dev/null)
    case "$c" in 0x03*|0x12*) printf '%s class=%s vendor=%s device=%s driver=%s\n' "${d##*/}" "$c" \
        "$(cat "$d/vendor" 2>/dev/null)" "$(cat "$d/device" 2>/dev/null)" \
        "$(basename "$(readlink "$d/driver" 2>/dev/null)" 2>/dev/null)";; esac
done

section nvidia
if have nvidia-smi; then
    nvidia-smi --query-gpu=name,memory.total,driver_version --format=csv,noheader,nounits 2>&1
    printf 'nvidia_smi_exit=%s\n' "$?"
else
    echo nvidia_smi=absent
fi

section disk
df -P -B1 / 2>/dev/null | tail -n +2 | sed 's/^/root /'
for d in /usr/share/ollama/.ollama/models /var/lib/ollama "$HOME/.ollama/models"; do
    [ -d "$d" ] && df -P -B1 "$d" 2>/dev/null | tail -n +2 | sed "s|^|models:$d |"
done

section ollama
if have ollama; then
    printf 'path=%s\n' "$(command -v ollama)"
    printf 'version=%s\n' "$(ollama --version 2>/dev/null | head -n1)"
else
    echo path=
fi
if have systemctl; then
    printf 'service_active=%s\n' "$(systemctl is-active ollama 2>/dev/null)"
    printf 'service_enabled=%s\n' "$(systemctl is-enabled ollama 2>/dev/null)"
    printf 'service_fragment=%s\n' "$(systemctl show -p FragmentPath --value ollama 2>/dev/null)"
    printf 'service_dropins=%s\n' "$(systemctl show -p DropInPaths --value ollama 2>/dev/null)"
    printf 'service_env=%s\n' "$(systemctl show -p Environment --value ollama 2>/dev/null)"
fi
if have curl; then
    printf 'api=%s\n' "$(curl -s -m 3 http://127.0.0.1:11434/api/version 2>/dev/null)"
fi
[ -f "$HOME/.local/state/llmwizard/installed.json" ] && echo manifest=yes || echo manifest=no

section net
have ip && ip -4 -o addr show scope global 2>/dev/null
have ss && ss -ltnH 2>/dev/null

section firewall
have ufw && echo ufw=present || echo ufw=absent
[ -d /etc/ufw ] && grep -E '^ENABLED=' /etc/ufw/ufw.conf 2>/dev/null
have firewall-cmd && echo firewalld=present
have tailscale && echo tailscale=present

section tools
for t in curl wget zstd tar sudo systemctl setsid nohup gzip; do
    if have "$t"; then echo "$t=yes"; else echo "$t=no"; fi
done

section priv
printf 'uid=%s\n' "$(id -u)"
printf 'user=%s\n' "$(id -un)"
printf 'groups=%s\n' "$(id -Gn)"
if have sudo; then
    if sudo -n true 2>/dev/null; then echo sudo=nopasswd; else echo sudo=password-or-none; fi
else
    echo sudo=absent
fi
printf 'shell=%s\n' "$(getent passwd "$(id -un)" 2>/dev/null | cut -d: -f7)"

section end
