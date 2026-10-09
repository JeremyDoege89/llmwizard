# llmwizard server status. Read-only, no sudo: service state, Ollama version, load, memory,
# disk, and the last lines of Ollama's log when this user may read the journal.
set +e
section() { printf '\n@@ %s\n' "$1"; }

section service
printf 'active=%s\n' "$(systemctl is-active ollama 2>/dev/null)"
printf 'enabled=%s\n' "$(systemctl is-enabled ollama 2>/dev/null)"
printf 'since=%s\n' "$(systemctl show -p ActiveEnterTimestamp --value ollama 2>/dev/null)"
printf 'env=%s\n' "$(systemctl show -p Environment --value ollama 2>/dev/null)"
printf 'version=%s\n' "$(ollama --version 2>/dev/null | sed -n 's/.*version is \([^ ]*\).*/\1/p' | tail -n1)"

section load
cat /proc/loadavg 2>/dev/null
printf 'nproc=%s\n' "$(nproc 2>/dev/null)"

section meminfo
grep -E '^(MemTotal|MemAvailable):' /proc/meminfo 2>/dev/null

section disk
for d in /usr/share/ollama/.ollama/models /; do
    [ -d "$d" ] && { df -P -B1 "$d" 2>/dev/null | tail -n +2 | sed "s|^|$d |"; break; }
done

section log
journalctl -u ollama -n 80 --no-pager -o short-iso 2>/dev/null || echo "(this user cannot read the system journal)"

section end
