# Source for recipes/*.json. Writing shell inside JSON by hand is error-prone, so the recipes are
# authored here and generated: python3 -I tools/recipes-source.py recipes
# The JSON files are what ships, gets signed (Phase 5) and is reviewed in diffs.
import json, sys, os
out = sys.argv[1]
def w(name, d):
    json.dump(d, open(os.path.join(out, name + ".json"), "w"), indent=2)
    print(name)

API_HOST = 'case "$P_bind" in 0.0.0.0:*) api="127.0.0.1:${P_bind##*:}";; *) api="$P_bind";; esac\n'
UNIT = """[Unit]
Description=Ollama Service
After=network-online.target

[Service]
ExecStart=/usr/local/bin/ollama serve
User=ollama
Group=ollama
Restart=always
RestartSec=3
Environment="PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

[Install]
WantedBy=multi-user.target
"""
WANT = """want=$(printf '%s\\n' \\
    '# Written by llmwizard. Your own settings belong in override.conf, which wins over this file.' \\
    '[Service]' \\
    "Environment=\\"OLLAMA_HOST=$P_bind\\"" \\
    "Environment=\\"OLLAMA_CONTEXT_LENGTH=$P_contextLength\\"" \\
    "Environment=\\"OLLAMA_KEEP_ALIVE=$P_keepAlive\\"")
"""
DROPIN_SKIP = WANT + '[ "$(cat /etc/systemd/system/ollama.service.d/10-llmwizard.conf 2>/dev/null)" = "$want" ]\n'
DROPIN_WRITE = WANT + """mkdir -p /etc/systemd/system/ollama.service.d
tmp=$(mktemp)
printf '%s\\n' "$want" > "$tmp"
install -o0 -g0 -m644 "$tmp" /etc/systemd/system/ollama.service.d/10-llmwizard.conf
rm -f "$tmp"
touch "$P_J/restart-needed"
echo "Settings written."
"""
WAIT_API = API_HOST + "i=0\nwhile [ $i -lt 30 ]; do curl -fsS -m 2 \"http://$api/api/version\" >/dev/null 2>&1 && exit 0; i=$((i+1)); sleep 1; done\nexit 1\n"
common_params = {
  "bind": {"type": "bind", "default": "127.0.0.1:11434", "description": "Address Ollama listens on. 127.0.0.1 means only this machine (and the app, through SSH)."},
  "contextLength": {"type": "int", "default": "8192", "min": 2048, "max": 262144, "description": "How much conversation the model keeps in view, in tokens."},
  "keepAlive": {"type": "duration", "default": "30m", "description": "How long a model stays loaded after its last use."},
}
APT = "apt-get -o DPkg::Lock::Timeout=300 -o Acquire::Retries=3"

DOWNLOAD = """mkdir -p "$P_CACHE"
cd "$P_CACHE"
if [ "$P_ollamaVersion" = latest ]; then
    url=$(curl -fsS -o /dev/null -w '%{redirect_url}' https://github.com/ollama/ollama/releases/latest/download/ollama-linux-amd64.tar.zst)
else
    url="https://github.com/ollama/ollama/releases/download/v${P_ollamaVersion}/ollama-linux-amd64.tar.zst"
fi
version=$(printf '%s\\n' "$url" | sed -n 's|.*/download/v\\([^/]*\\)/.*|\\1|p')
[ -n "$version" ] || { echo "Could not work out which Ollama version to download."; exit 1; }
@@CURRENT@@
# A half-finished download of a different version is thrown away, not resumed.
if [ -f ollama.version ] && [ "$(cat ollama.version)" != "$version" ]; then rm -f ollama-linux-amd64.tar.zst; fi
echo "$version" > ollama.version
echo "Downloading Ollama $version"
# Progress is thinned out so the log stays small; curl resumes a partial file (-C -).
curl -fL --retry 5 --retry-delay 5 --progress-bar -C - -o ollama-linux-amd64.tar.zst "$url" 2>&1 | tr '\\r' '\\n' | awk 'NR % 40 == 0'
sha=$(sha256sum ollama-linux-amd64.tar.zst | cut -d' ' -f1)
echo "$sha" > ollama.sha256
echo "sha256 $sha"
if [ -n "$P_ollamaSha256" ] && [ "$sha" != "$P_ollamaSha256" ]; then
    echo "The download does not match the expected checksum. Not installing it."
    rm -f ollama-linux-amd64.tar.zst
    exit 1
fi
zstd -t -q ollama-linux-amd64.tar.zst
echo "Download complete."
"""

w("ollama-install", {
 "id": "ollama-install", "version": 2, "title": "Install Ollama",
 "description": "Installs Ollama as a system service the way Ollama's own Linux instructions do, without touching GPU drivers, then downloads the chosen model.",
 "targets": {"os": ["ubuntu>=22.04", "debian>=12"], "arch": ["x86_64"], "init": "systemd"},
 "params": dict(common_params, **{
   "ollamaVersion": {"type": "version", "default": "0.40.2", "description": "The Ollama release this recipe was tested with. 'latest' takes the newest at download time."},
   "ollamaSha256": {"type": "sha256", "default": "726bee78706c281b0eeef00746efe51a044d71c592c3f0b195820707f31fdf04", "description": "sha256 of ollama-linux-amd64.tar.zst for that release, as GitHub publishes it. Empty means record without checking."},
   "model": {"type": "tag", "default": "", "description": "Model to download after installing, for example qwen3:0.6b."}
 }),
 "preflight": [
   {"id": "systemd", "title": "systemd is running", "run": "[ -d /run/systemd/system ]", "failMessage": "This machine does not run systemd, which the Ollama service needs."},
   {"id": "arch", "title": "64-bit x86 machine", "run": "[ \"$(uname -m)\" = x86_64 ]", "failMessage": "Only x86_64 machines are supported for now."},
   {"id": "dns", "title": "Can look up github.com", "run": "getent hosts github.com >/dev/null", "failMessage": "This machine cannot resolve github.com, where Ollama is downloaded from. Check its internet connection and DNS."},
   {"id": "port", "title": "Port 11434 is free or already Ollama", "run": "if ss -ltnH 2>/dev/null | awk '{print $4}' | grep -q ':11434$'; then systemctl is-active --quiet ollama; fi", "failMessage": "Something other than Ollama is already using port 11434."}
 ],
 "steps": [
  {"id": "prereqs", "title": "Install curl and zstd if they are missing", "why": "curl downloads Ollama and zstd unpacks it.", "sudo": True,
   "skipIf": "command -v curl >/dev/null && command -v zstd >/dev/null && command -v tar >/dev/null",
   "run": APT + " update -qq\n" + APT + " install -y -qq --no-install-recommends curl zstd tar ca-certificates\n",
   "verify": "command -v curl >/dev/null && command -v zstd >/dev/null", "timeoutSec": 900},
  {"id": "download", "title": "Download Ollama", "why": "About 1.5 GB. Runs in the background on the server, so it keeps going if your phone sleeps, and it resumes if interrupted.",
   "skipIf": "command -v ollama >/dev/null || [ -x /usr/local/bin/ollama ]",
   "detached": True, "timeoutSec": 7200,
   "run": DOWNLOAD.replace("@@CURRENT@@\n", ""),
   "verify": "[ -s \"$P_CACHE/ollama-linux-amd64.tar.zst\" ]"},
  {"id": "unpack", "title": "Unpack Ollama into /usr/local", "why": "Puts the ollama program in /usr/local/bin and its libraries in /usr/local/lib/ollama, where Ollama's own installer puts them.", "sudo": True,
   "skipIf": "command -v ollama >/dev/null || [ -x /usr/local/bin/ollama ]",
   "run": """install -o0 -g0 -m755 -d /usr/local/bin /usr/local/lib/ollama
zstd -dc "$P_CACHE/ollama-linux-amd64.tar.zst" | tar -xf - -C /usr/local --no-same-owner
/usr/local/bin/ollama --version || true
rm -f "$P_CACHE/ollama-linux-amd64.tar.zst"
""",
   "verify": "[ -x /usr/local/bin/ollama ]",
   "undo": "rm -f /usr/local/bin/ollama\nrm -rf /usr/local/lib/ollama\n",
   "creates": [{"kind": "file", "name": "/usr/local/bin/ollama"}, {"kind": "dir", "name": "/usr/local/lib/ollama"}],
   "facts": "printf 'ollama.prefix=/usr/local\\nollama.version=%s\\nollama.sha256=%s\\n' \"$(cat \"$P_CACHE/ollama.version\" 2>/dev/null)\" \"$(cat \"$P_CACHE/ollama.sha256\" 2>/dev/null)\""},
  {"id": "user", "title": "Create the ollama system user", "why": "The service runs as its own unprivileged user, as Ollama's instructions set it up. Your user joins its group so you can read its files.", "sudo": True,
   "skipIf": "id ollama >/dev/null 2>&1",
   "run": """useradd -r -s /bin/false -U -m -d /usr/share/ollama ollama
if getent group render >/dev/null 2>&1; then usermod -a -G render ollama; fi
if getent group video >/dev/null 2>&1; then usermod -a -G video ollama; fi
usermod -a -G ollama "$P_USER"
""",
   "verify": "id ollama >/dev/null 2>&1",
   "undo": "userdel ollama 2>/dev/null || true\ngroupdel ollama 2>/dev/null || true\nrm -rf /usr/share/ollama\n",
   "creates": [{"kind": "user", "name": "ollama"}, {"kind": "group", "name": "ollama"}, {"kind": "dir", "name": "/usr/share/ollama"}]},
  {"id": "unit", "title": "Create the Ollama service", "why": "A systemd service starts Ollama at boot and restarts it if it stops.", "sudo": True,
   "skipIf": "[ -f /etc/systemd/system/ollama.service ] || [ -f /lib/systemd/system/ollama.service ] || [ -f /usr/lib/systemd/system/ollama.service ]",
   "run": "cat > /etc/systemd/system/ollama.service <<'UNIT'\n" + UNIT + "UNIT\nchmod 644 /etc/systemd/system/ollama.service\ntouch \"$P_J/restart-needed\"\n",
   "verify": "[ -f /etc/systemd/system/ollama.service ]",
   "undo": "systemctl disable --now ollama 2>/dev/null || true\nrm -f /etc/systemd/system/ollama.service\nsystemctl daemon-reload\n",
   "creates": [{"kind": "unit", "name": "/etc/systemd/system/ollama.service"}]},
  {"id": "settings", "title": "Write llmwizard's settings for Ollama", "why": "Keeps Ollama listening only on this machine, sets how much conversation it remembers and how long a model stays loaded. Stored in its own file so your own changes are never overwritten.", "sudo": True,
   "skipIf": DROPIN_SKIP, "run": DROPIN_WRITE,
   "verify": "[ -f /etc/systemd/system/ollama.service.d/10-llmwizard.conf ]",
   "undo": "rm -f /etc/systemd/system/ollama.service.d/10-llmwizard.conf\nrmdir /etc/systemd/system/ollama.service.d 2>/dev/null || true\nsystemctl daemon-reload\n",
   "creates": [{"kind": "dropin", "name": "/etc/systemd/system/ollama.service.d/10-llmwizard.conf"}],
   "facts": "printf 'settings.bind=%s\\nsettings.contextLength=%s\\nsettings.keepAlive=%s\\n' \"$P_bind\" \"$P_contextLength\" \"$P_keepAlive\""},
  {"id": "start", "title": "Start Ollama and turn it on at boot", "why": "Loads the new settings and starts the service.", "sudo": True,
   "skipIf": "systemctl is-active --quiet ollama && systemctl is-enabled --quiet ollama && [ ! -f \"$P_J/restart-needed\" ]",
   "run": "systemctl daemon-reload\nsystemctl enable ollama\nsystemctl restart ollama\nrm -f \"$P_J/restart-needed\"\n",
   "verify": WAIT_API,
   "facts": API_HOST + "printf 'ollama.apiVersion=%s\\n' \"$(curl -fsS -m 3 \"http://$api/api/version\" | sed -n 's/.*\"version\":\"\\([^\"]*\\)\".*/\\1/p')\"\n"},
  {"id": "pull", "title": "Download the model", "why": "Models are large; this runs in the background on the server and survives your phone disconnecting.",
   "requires": ["param:model"],
   "skipIf": API_HOST + "curl -fsS -m 10 \"http://$api/api/show\" -d \"{\\\"model\\\":\\\"$P_model\\\"}\" >/dev/null 2>&1",
   "detached": True, "timeoutSec": 86400,
   "run": API_HOST + """echo "Downloading $P_model"
curl -sS -N "http://$api/api/pull" -d "{\\"model\\":\\"$P_model\\"}" | awk '/"error"/ { print; err=1; next } { if (++n % 50 == 1 || /success/) print } END { exit err }'
""",
   "verify": API_HOST + "curl -fsS -m 10 \"http://$api/api/show\" -d \"{\\\"model\\\":\\\"$P_model\\\"}\" >/dev/null",
   "creates": [{"kind": "model", "name": "{{model}}"}]}
 ]})

w("ollama-configure", {
 "id": "ollama-configure", "version": 1, "title": "Change Ollama's settings",
 "description": "Rewrites llmwizard's settings file for the Ollama service and restarts it if anything changed. Restarting unloads any loaded model.",
 "params": common_params,
 "steps": [
  {"id": "settings", "title": "Write llmwizard's settings for Ollama", "why": "Updates the address, context length and keep-alive.", "sudo": True, "skipIf": DROPIN_SKIP, "run": DROPIN_WRITE,
   "creates": [{"kind": "dropin", "name": "/etc/systemd/system/ollama.service.d/10-llmwizard.conf"}],
   "facts": "printf 'settings.bind=%s\\nsettings.contextLength=%s\\nsettings.keepAlive=%s\\n' \"$P_bind\" \"$P_contextLength\" \"$P_keepAlive\""},
  {"id": "restart", "title": "Restart Ollama", "why": "Ollama reads its settings when it starts. This unloads any loaded model.", "sudo": True,
   "skipIf": "[ ! -f \"$P_J/restart-needed\" ]",
   "run": "systemctl daemon-reload\nsystemctl restart ollama\nrm -f \"$P_J/restart-needed\"\n",
   "verify": WAIT_API}
 ]})

w("ollama-update", {
 "id": "ollama-update", "version": 2, "title": "Update Ollama",
 "description": "Downloads a newer Ollama and replaces the program and its libraries where they are installed, then restarts the service. Models and settings are kept.",
 "params": {
   "ollamaVersion": {"type": "version", "default": "0.40.2"},
   "ollamaSha256": {"type": "sha256", "default": "726bee78706c281b0eeef00746efe51a044d71c592c3f0b195820707f31fdf04"},
   "prefix": {"type": "path", "default": "/usr/local", "description": "Where Ollama is installed: /usr/local or /usr."}
 },
 "steps": [
  {"id": "download", "title": "Download the new Ollama", "why": "Runs in the background on the server and resumes if interrupted.", "detached": True, "timeoutSec": 7200,
   "run": DOWNLOAD.replace("@@CURRENT@@\n", """current=$("$P_prefix/bin/ollama" --version 2>/dev/null | sed -n 's/.*version is \\([^ ]*\\).*/\\1/p' | tail -n1)
if [ "$current" = "$version" ]; then echo "Already on $version."; echo "$version" > ollama.version; rm -f ollama-linux-amd64.tar.zst; exit 0; fi
""")},
  {"id": "replace", "title": "Replace the Ollama program and libraries", "why": "Stops the service, removes the old libraries as Ollama's installer does, unpacks the new version and starts the service again.", "sudo": True,
   "skipIf": "[ ! -f \"$P_CACHE/ollama-linux-amd64.tar.zst\" ]",
   "run": """systemctl stop ollama || true
rm -rf "$P_prefix/lib/ollama"
install -o0 -g0 -m755 -d "$P_prefix/bin" "$P_prefix/lib/ollama"
zstd -dc "$P_CACHE/ollama-linux-amd64.tar.zst" | tar -xf - -C "$P_prefix" --no-same-owner
rm -f "$P_CACHE/ollama-linux-amd64.tar.zst"
systemctl start ollama
"$P_prefix/bin/ollama" --version || true
""",
   "verify": "i=0\nwhile [ $i -lt 30 ]; do systemctl is-active --quiet ollama && exit 0; i=$((i+1)); sleep 1; done\nexit 1\n",
   "facts": "printf 'ollama.version=%s\\nollama.sha256=%s\\n' \"$(cat \"$P_CACHE/ollama.version\" 2>/dev/null)\" \"$(cat \"$P_CACHE/ollama.sha256\" 2>/dev/null)\""}
 ]})

w("ollama-uninstall", {
 "id": "ollama-uninstall", "version": 1, "title": "Remove Ollama",
 "description": "Removes only what llmwizard installed, as listed in its record on the server. An Ollama you installed yourself is left alone. Models are deleted only if you confirm the list.",
 "params": {
   "deleteModels": {"type": "bool", "default": "no", "description": "Delete downloaded models too. The app lists them first."}
 },
 "steps": [
  {"id": "stop", "title": "Stop and disable the Ollama service", "why": "The service has to stop before its files are removed.", "sudo": True,
   "requires": ["created:unit:/etc/systemd/system/ollama.service"],
   "run": "systemctl disable --now ollama 2>/dev/null || systemctl stop ollama 2>/dev/null || true\n"},
  {"id": "dropin", "title": "Remove llmwizard's settings file", "why": "It was added by llmwizard.", "sudo": True,
   "requires": ["created:dropin:/etc/systemd/system/ollama.service.d/10-llmwizard.conf"],
   "run": "rm -f /etc/systemd/system/ollama.service.d/10-llmwizard.conf\nrmdir /etc/systemd/system/ollama.service.d 2>/dev/null || true\nsystemctl daemon-reload\n",
   "removes": [{"kind": "dropin", "name": "/etc/systemd/system/ollama.service.d/10-llmwizard.conf"}]},
  {"id": "unit", "title": "Remove the Ollama service", "why": "It was created by llmwizard.", "sudo": True,
   "requires": ["created:unit:/etc/systemd/system/ollama.service"],
   "run": "rm -f /etc/systemd/system/ollama.service\nsystemctl daemon-reload\nsystemctl reset-failed ollama 2>/dev/null || true\n",
   "removes": [{"kind": "unit", "name": "/etc/systemd/system/ollama.service"}]},
  {"id": "binary", "title": "Remove the Ollama program and libraries", "why": "They were installed by llmwizard.", "sudo": True,
   "requires": ["created:file:/usr/local/bin/ollama"],
   "run": "rm -f /usr/local/bin/ollama\nrm -rf /usr/local/lib/ollama\n",
   "verify": "[ ! -e /usr/local/bin/ollama ]",
   "removes": [{"kind": "file", "name": "/usr/local/bin/ollama"}, {"kind": "dir", "name": "/usr/local/lib/ollama"}]},
  {"id": "models", "title": "Delete the downloaded models", "why": "You confirmed the models should be deleted. They live in /usr/share/ollama.", "sudo": True,
   "requires": ["created:dir:/usr/share/ollama", "param:deleteModels"],
   "run": "rm -rf /usr/share/ollama/.ollama\n",
   "removes": [{"kind": "model", "name": "*"}]},
  {"id": "user", "title": "Remove the ollama user and group", "why": "They were created by llmwizard. Its home folder (with any models) is removed only if you chose to delete the models.", "sudo": True,
   "requires": ["created:user:ollama"],
   "run": """if [ "$P_deleteModels" = yes ]; then
    userdel -r ollama 2>/dev/null || userdel ollama
else
    userdel ollama
fi
groupdel ollama 2>/dev/null || true
""",
   "verify": "! id ollama >/dev/null 2>&1",
   "removes": [{"kind": "user", "name": "ollama"}, {"kind": "group", "name": "ollama"}]},
  {"id": "home", "title": "Forget the ollama folder", "why": "Bookkeeping only: the models folder was deleted.",
   "requires": ["created:dir:/usr/share/ollama", "param:deleteModels"],
   "run": "true\n",
   "removes": [{"kind": "dir", "name": "/usr/share/ollama"}]},
  {"id": "ufw", "title": "Remove llmwizard's firewall rules", "why": "They were added when the model was shared with your network.", "sudo": True,
   "requires": ["createdkind:ufw"],
   "run": "ufw status numbered | sed -n 's/^\\[ *\\([0-9]*\\)\\].*# llmwizard$/\\1/p' | sort -rn | while read -r n; do ufw --force delete \"$n\"; done\n",
   "removes": [{"kind": "ufw", "name": "*"}]},
  {"id": "sudoers", "title": "Remove llmwizard's sudo rule", "why": "It was added by llmwizard.", "sudo": True,
   "requires": ["created:file:/etc/sudoers.d/llmwizard-ollama"],
   "run": "rm -f /etc/sudoers.d/llmwizard-ollama\n",
   "removes": [{"kind": "file", "name": "/etc/sudoers.d/llmwizard-ollama"}]},
  {"id": "cache", "title": "Remove llmwizard's download cache", "why": "Leftover downloads in llmwizard's own folder.",
   "run": "rm -rf \"$P_CACHE\"\n"}
 ]})

w("authorized-key-add", {
 "id": "authorized-key-add", "version": 1, "title": "Let this phone log in with its own key",
 "description": "Adds the app's public key to your ~/.ssh/authorized_keys, so the app can log in without your password from now on.",
 "params": {"publicKey": {"type": "sshPublicKey", "required": True, "description": "The app's public key line."}},
 "steps": [
  {"id": "add", "title": "Add the app's key to ~/.ssh/authorized_keys", "why": "Afterwards the app logs in with the key and forgets your password.",
   "skipIf": "[ -f \"$HOME/.ssh/authorized_keys\" ] && grep -qxF \"$P_publicKey\" \"$HOME/.ssh/authorized_keys\"",
   "run": """umask 077
mkdir -p "$HOME/.ssh"
chmod 700 "$HOME/.ssh"
# Make sure the file ends with a newline before appending.
if [ -s "$HOME/.ssh/authorized_keys" ] && [ -n "$(tail -c1 "$HOME/.ssh/authorized_keys")" ]; then echo >> "$HOME/.ssh/authorized_keys"; fi
printf '%s\\n' "$P_publicKey" >> "$HOME/.ssh/authorized_keys"
chmod 600 "$HOME/.ssh/authorized_keys"
""",
   "verify": "grep -qxF \"$P_publicKey\" \"$HOME/.ssh/authorized_keys\"",
   "undo": "tmp=$(mktemp)\ngrep -vxF \"$P_publicKey\" \"$HOME/.ssh/authorized_keys\" > \"$tmp\" || true\ncat \"$tmp\" > \"$HOME/.ssh/authorized_keys\"\nrm -f \"$tmp\"\n"}
 ]})

w("model-pull", {
 "id": "model-pull", "version": 1, "title": "Download a model",
 "description": "Asks Ollama on the server to download a model. Runs in the background there, so it survives the phone disconnecting.",
 "params": {
   "model": {"type": "tag", "required": True, "description": "The model to download, for example qwen3:4b."},
   "bind": {"type": "bind", "default": "127.0.0.1:11434"}
 },
 "steps": [
  {"id": "pull", "title": "Download the model", "why": "Models are large; this runs in the background on the server.",
   "skipIf": API_HOST + "curl -fsS -m 10 \"http://$api/api/show\" -d \"{\\\"model\\\":\\\"$P_model\\\"}\" >/dev/null 2>&1",
   "detached": True, "timeoutSec": 86400,
   "run": API_HOST + """echo "Downloading $P_model"
curl -sS -N "http://$api/api/pull" -d "{\\"model\\":\\"$P_model\\"}" | awk '/"error"/ { print; err=1; next } { if (++n % 50 == 1 || /success/) print } END { exit err }'
""",
   "verify": API_HOST + "curl -fsS -m 10 \"http://$api/api/show\" -d \"{\\\"model\\\":\\\"$P_model\\\"}\" >/dev/null",
   "creates": [{"kind": "model", "name": "{{model}}"}]}
 ]})

ACTION = {"action": {"type": "enum", "values": ["start", "stop", "restart"], "required": True, "description": "What to do with the Ollama service."}}
w("service-control", {
 "id": "service-control", "version": 1, "title": "Start, stop or restart Ollama",
 "description": "Runs systemctl for the Ollama service. Stopping or restarting unloads any loaded model.",
 "params": ACTION,
 "steps": [
  {"id": "systemctl", "title": "Tell systemd to change the Ollama service", "why": "Ollama runs as a system service.", "sudo": True,
   "run": "systemctl \"$P_action\" ollama\nsystemctl is-active ollama || true\n",
   "verify": "case \"$P_action\" in stop) ! systemctl is-active --quiet ollama;; *) i=0; while [ $i -lt 20 ]; do systemctl is-active --quiet ollama && exit 0; i=$((i+1)); sleep 1; done; exit 1;; esac\n"}
 ]})
w("service-control-nopw", {
 "id": "service-control-nopw", "version": 1, "title": "Start, stop or restart Ollama (no password)",
 "description": "The same as service-control, using the sudo rule llmwizard added so these three commands need no password.",
 "params": ACTION,
 "steps": [
  {"id": "systemctl", "title": "Tell systemd to change the Ollama service", "why": "Allowed without a password by /etc/sudoers.d/llmwizard-ollama.",
   "run": "sudo -n /usr/bin/systemctl \"$P_action\" ollama\nsystemctl is-active ollama || true\n",
   "verify": "case \"$P_action\" in stop) ! systemctl is-active --quiet ollama;; *) i=0; while [ $i -lt 20 ]; do systemctl is-active --quiet ollama && exit 0; i=$((i+1)); sleep 1; done; exit 1;; esac\n"}
 ]})
w("sudoers-dropin", {
 "id": "sudoers-dropin", "version": 1, "title": "Let the app control Ollama without your password",
 "description": "Optional. Adds a sudo rule that lets your user start, stop and restart the Ollama service and read its log without typing a password. Nothing else.",
 "steps": [
  {"id": "dropin", "title": "Add /etc/sudoers.d/llmwizard-ollama", "why": "Checked with visudo before it is put in place, because a broken sudoers file locks everyone out of sudo.", "sudo": True,
   "skipIf": "[ -f /etc/sudoers.d/llmwizard-ollama ]",
   "run": """tmp=$(mktemp)
printf '%s\\n' \\
    '# Added by llmwizard. Remove this file to undo.' \\
    "$P_USER ALL=(root) NOPASSWD: /usr/bin/systemctl start ollama, /usr/bin/systemctl stop ollama, /usr/bin/systemctl restart ollama, /usr/bin/journalctl -u ollama *" > "$tmp"
visudo -cf "$tmp"
install -o0 -g0 -m440 "$tmp" /etc/sudoers.d/llmwizard-ollama
rm -f "$tmp"
""",
   "verify": "sudo -n -l /usr/bin/systemctl restart ollama >/dev/null 2>&1",
   "undo": "rm -f /etc/sudoers.d/llmwizard-ollama\n",
   "creates": [{"kind": "file", "name": "/etc/sudoers.d/llmwizard-ollama"}]}
 ]})

RESTART = {"id": "restart", "title": "Restart Ollama", "why": "Ollama reads its address when it starts. This unloads any loaded model.", "sudo": True,
   "skipIf": "[ ! -f \"$P_J/restart-needed\" ]",
   "run": "systemctl daemon-reload\nsystemctl restart ollama\nrm -f \"$P_J/restart-needed\"\n",
   "verify": WAIT_API}
w("expose-lan", {
 "id": "expose-lan", "version": 1, "title": "Let other devices on your network use the model",
 "description": "Makes Ollama listen on one of this machine's network addresses instead of only on itself. Ollama has no password: anything that can reach that address can use it.",
 "params": dict(common_params, **{"firewallCidr": {"type": "cidr", "default": "", "description": "If a firewall (ufw) is active, the network allowed to reach port 11434."}}),
 "steps": [
  {"id": "settings", "title": "Point Ollama at the chosen address", "why": "Rewrites llmwizard's settings file with the new address.", "sudo": True, "skipIf": DROPIN_SKIP, "run": DROPIN_WRITE,
   "creates": [{"kind": "dropin", "name": "/etc/systemd/system/ollama.service.d/10-llmwizard.conf"}],
   "facts": "printf 'settings.bind=%s\\nsettings.contextLength=%s\\nsettings.keepAlive=%s\\n' \"$P_bind\" \"$P_contextLength\" \"$P_keepAlive\""},
  {"id": "ufw", "title": "Allow the model's port through the firewall, from your network only", "why": "The firewall is on, so without a rule nothing could connect. The rule is limited to your network.", "sudo": True,
   "requires": ["param:firewallCidr"],
   "skipIf": "! grep -q '^ENABLED=yes' /etc/ufw/ufw.conf 2>/dev/null",
   "run": "ufw allow from \"$P_firewallCidr\" to any port 11434 proto tcp comment llmwizard\n",
   "undo": "ufw delete allow from \"$P_firewallCidr\" to any port 11434 proto tcp || true\n",
   "creates": [{"kind": "ufw", "name": "{{firewallCidr}}"}]},
  RESTART
 ]})
w("expose-private", {
 "id": "expose-private", "version": 1, "title": "Go back to private",
 "description": "Ollama listens only on this machine again; only this app (through SSH) can use it. Removes the firewall rule llmwizard added.",
 "params": dict(common_params, **{"firewallCidr": {"type": "cidr", "default": "", "description": "The network whose firewall rule llmwizard added."}}),
 "steps": [
  {"id": "settings", "title": "Make Ollama listen only on this machine", "why": "127.0.0.1 cannot be reached from any other device.", "sudo": True, "skipIf": DROPIN_SKIP, "run": DROPIN_WRITE,
   "creates": [{"kind": "dropin", "name": "/etc/systemd/system/ollama.service.d/10-llmwizard.conf"}],
   "facts": "printf 'settings.bind=%s\\nsettings.contextLength=%s\\nsettings.keepAlive=%s\\n' \"$P_bind\" \"$P_contextLength\" \"$P_keepAlive\""},
  {"id": "ufw", "title": "Remove llmwizard's firewall rule", "why": "It is no longer needed.", "sudo": True,
   "requires": ["param:firewallCidr", "created:ufw:{{firewallCidr}}"],
   "run": "ufw delete allow from \"$P_firewallCidr\" to any port 11434 proto tcp || true\n",
   "removes": [{"kind": "ufw", "name": "{{firewallCidr}}"}]},
  RESTART
 ]})

# ---- BUILD_PLAN E3/E4: Python tools on the target (2026-10-13) ----
# Everything lives in the login user's ~/.local/share/llmwizard, so only the apt step needs sudo.
# uv (pinned) fetches its own Python, because Aider needs < 3.13 and Debian 13 / Ubuntu 26.04 ship newer.
DATA = '"$P_HOME/.local/share/llmwizard"'
UV_ENV = ('D=' + DATA + '\nexport UV_CACHE_DIR="$P_CACHE/uv" UV_PYTHON_INSTALL_DIR="$D/python" UV_NO_PROGRESS=1\n'
          'uv="$D/uv/bin/uv"\n')
uv_params = {"uvVersion": {"type": "version", "default": "0.13.0", "description": "The uv release used to fetch Python and install packages."}}
PY_PREREQS = {"id": "prereqs", "title": "Install Python, venv, tmux and git if they are missing",
  "why": "The tools run in their own Python environment; tmux keeps agent sessions alive when your phone disconnects.", "sudo": True,
  "skipIf": "command -v python3 >/dev/null && python3 -c 'import venv, ensurepip' 2>/dev/null && command -v tmux >/dev/null && command -v git >/dev/null && command -v curl >/dev/null",
  "run": APT + " update -qq\n" + APT + " install -y -qq --no-install-recommends python3 python3-venv tmux git curl ca-certificates\n",
  "verify": "command -v tmux >/dev/null && python3 -c 'import venv, ensurepip'", "timeoutSec": 900}
UV_STEP = {"id": "uv", "title": "Install uv (Python package manager) for your user",
  "why": "uv installs the tools and the Python version they need into your home folder, without touching the system Python.",
  "skipIf": UV_ENV + '"$uv" --version 2>/dev/null | grep -q " $P_uvVersion"',
  "run": UV_ENV + 'rm -rf "$D/uv"\npython3 -m venv "$D/uv"\n"$D/uv/bin/pip" install -q --disable-pip-version-check "uv==$P_uvVersion"\n"$uv" --version\n',
  "verify": UV_ENV + '"$uv" --version >/dev/null',
  "creates": [{"kind": "llmwizard-data", "name": "uv"}]}

# The training recipe also needs libgomp1: llama.cpp's prebuilt llama-quantize links the OpenMP runtime
# (missing on a clean Debian 13, found 2026-10-13).
TRAIN_PREREQS = dict(PY_PREREQS,
  skipIf=PY_PREREQS["skipIf"] + " && ldconfig -p 2>/dev/null | grep -q libgomp.so.1",
  run=PY_PREREQS["run"].replace("tmux git curl ca-certificates", "tmux git curl ca-certificates libgomp1"),
  title="Install Python, venv, tmux, git and the OpenMP runtime if they are missing")

def tool_venv_step(step_id, title, why, venv, python, packages, check, kind_name, timeout=3600):
    return {"id": step_id, "title": title, "why": why, "detached": True, "timeoutSec": timeout,
      "skipIf": UV_ENV + check,
      "run": UV_ENV + f'"$uv" venv -q --clear --python {python} "$D/{venv}"\n' + f'"$uv" pip install -q --python "$D/{venv}/bin/python" {packages}\n' + check + '\n',
      "verify": UV_ENV + check,
      "undo": UV_ENV + f'rm -rf "$D/{venv}"\n',
      "creates": [{"kind": kind_name[0], "name": kind_name[1]}]}

w("aider-install", {
 "id": "aider-install", "version": 1, "title": "Install Aider (AI pair programmer)",
 "description": "Installs Aider into its own environment in your home folder. It edits code in a git repository using a model on this server's Ollama. Nothing is sent anywhere else: analytics are switched off when llmwizard starts it.",
 "params": dict(uv_params, **{"version": {"type": "version", "default": "0.86.2", "description": "The Aider release to install."}}),
 "steps": [PY_PREREQS, UV_STEP,
  tool_venv_step("aider", "Install Aider", "About 550 MB of Python packages, installed in the background on the server.",
                 "agents/aider", "3.12", '"aider-chat==$P_version"', '"$D/agents/aider/bin/aider" --version 2>/dev/null | grep -q "$P_version"', ("agent-tool", "aider"))]})

w("open-interpreter-install", {
 "id": "open-interpreter-install", "version": 1, "title": "Install Open Interpreter",
 "description": "Installs Open Interpreter into its own environment in your home folder. It lets a model on this server write and run code here. By default every command waits for your approval; auto-run is a per-profile choice.",
 "params": dict(uv_params, **{"version": {"type": "version", "default": "0.4.3", "description": "The Open Interpreter release to install."}}),
 "steps": [PY_PREREQS, UV_STEP,
  tool_venv_step("interpreter", "Install Open Interpreter", "About 400 MB of Python packages, installed in the background on the server.",
                 "agents/open-interpreter", "3.11", '"open-interpreter==$P_version" "setuptools<80"',  # 0.4.3 imports pkg_resources, gone from setuptools 80+
                 '(cd /tmp && "$D/agents/open-interpreter/bin/interpreter" --version 2>/dev/null | grep -q "$P_version")', ("agent-tool", "open-interpreter"))]})

w("agent-tool-remove", {
 "id": "agent-tool-remove", "version": 1, "title": "Remove an agent tool",
 "description": "Stops its sessions and deletes the tool's environment from your home folder. Your code and git repositories are not touched.",
 "params": {"tool": {"type": "enum", "values": ["aider", "open-interpreter"], "required": True, "description": "Which tool to remove."}},
 "steps": [{"id": "remove", "title": "Stop its sessions and delete its environment", "why": "Only the folder llmwizard created is removed.",
   "run": 'D=' + DATA + '\nfor s in $(tmux ls -F "#{session_name}" 2>/dev/null | grep "^llmw-$P_tool-" || true); do tmux kill-session -t "$s"; done\nrm -rf "$D/agents/$P_tool"\n',
   "verify": 'D=' + DATA + '\n[ ! -e "$D/agents/$P_tool" ]',
   "removes": [{"kind": "agent-tool", "name": "{{tool}}"}]}]})

TRAIN_PKGS = ('"transformers==$P_transformersVersion" "peft==$P_peftVersion" "datasets==$P_datasetsVersion" '
              '"accelerate==$P_accelerateVersion" "gguf==$P_ggufVersion" sentencepiece protobuf safetensors numpy')
TRAIN_CHECK = '"$D/training/venv/bin/python" -c "import torch, transformers, peft, datasets, gguf; assert torch.__version__.split(\'+\')[0] == \'$P_torchVersion\'" 2>/dev/null'
w("training-stack-install", {
 "id": "training-stack-install", "version": 1, "title": "Install the fine-tuning tools",
 "description": "Installs PyTorch (processor build), Hugging Face Transformers and PEFT for LoRA fine-tuning, plus llama.cpp's converter that turns the trained model into something Ollama can load. All in your home folder, about 2 GB.",
 "params": dict(uv_params, **{
   "torchVersion": {"type": "version", "default": "2.14.1", "description": "PyTorch, CPU build."},
   "transformersVersion": {"type": "version", "default": "5.19.0", "description": "Hugging Face Transformers."},
   "peftVersion": {"type": "version", "default": "0.21.2", "description": "PEFT (LoRA adapters)."},
   "datasetsVersion": {"type": "version", "default": "5.1.0", "description": "Hugging Face Datasets."},
   "accelerateVersion": {"type": "version", "default": "1.15.0", "description": "Accelerate."},
   "ggufVersion": {"type": "version", "default": "0.19.0", "description": "gguf, the file format library llama.cpp's converters use."},
   "llamaCppTag": {"type": "ident", "default": "b11306", "description": "llama.cpp release (2026-09-30) whose converter script and llama-quantize are used."},
   "llamaCppSha256": {"type": "sha256", "default": "605cfa2fc0e1d27822a179c561a6db411d90ebcc42bd8b57d0bda33475c9655a", "description": "sha256 of llama-<tag>-bin-ubuntu-x64.tar.gz, as GitHub publishes it."}}),
 "preflight": [{"id": "disk", "title": "At least 15 GB free in your home folder",
   "run": "[ \"$(df -Pk \"$HOME\" | awk 'NR==2 {print $4}')\" -gt 15000000 ]",
   "failMessage": "Fine-tuning needs room for the tools (about 2 GB), the base model's original weights and checkpoints. Free up space in your home folder first."},
   {"id": "pypi", "title": "Can look up pypi.org and download.pytorch.org",
   "run": "getent hosts pypi.org >/dev/null && getent hosts download.pytorch.org >/dev/null",
   "failMessage": "This machine cannot resolve the Python package servers. Check its internet connection and DNS."}],
 "steps": [TRAIN_PREREQS, UV_STEP,
  {"id": "venv", "title": "Install PyTorch and the training libraries", "why": "About 2 GB, installed in the background. The processor build of PyTorch is used; it needs no graphics card.",
   "detached": True, "timeoutSec": 7200,
   "skipIf": UV_ENV + TRAIN_CHECK,
   "run": UV_ENV + '"$uv" venv -q --clear --python 3.12 "$D/training/venv"\n'
          '"$uv" pip install -q --python "$D/training/venv/bin/python" --index-url https://download.pytorch.org/whl/cpu "torch==$P_torchVersion"\n'
          '"$uv" pip install -q --python "$D/training/venv/bin/python" ' + TRAIN_PKGS + '\n' + TRAIN_CHECK + '\n',
   "verify": UV_ENV + TRAIN_CHECK,
   "undo": UV_ENV + 'rm -rf "$D/training/venv"\n',
   "facts": UV_ENV + '"$D/training/venv/bin/python" -c "import torch, transformers, peft; print(\'training.torch=\' + torch.__version__); print(\'training.transformers=\' + transformers.__version__); print(\'training.peft=\' + peft.__version__)"',
   "creates": [{"kind": "training-venv", "name": "venv"}]},
  {"id": "llamacpp", "title": "Fetch llama.cpp's converter and quantizer", "why": "They turn the trained model into a GGUF file of the size you pick, the format Ollama loads. The converter is a Python script; llama-quantize is llama.cpp's own prebuilt processor build, checked against its published sha256. Nothing is compiled.",
   "detached": True, "timeoutSec": 3600,
   "skipIf": UV_ENV + '[ -f "$D/training/llama.cpp/convert_hf_to_gguf.py" ] && [ -x "$D/training/llama.cpp/bin/llama-quantize" ] && [ "$(cat "$D/training/llama.cpp/.llmwizard-tag" 2>/dev/null)" = "$P_llamaCppTag" ]',
   "run": UV_ENV + 'rm -rf "$D/training/llama.cpp"\ngit -c advice.detachedHead=false clone -q --depth 1 --branch "$P_llamaCppTag" https://github.com/ggml-org/llama.cpp "$D/training/llama.cpp"\n'
          'mkdir -p "$P_CACHE"\ntgz="$P_CACHE/llama-$P_llamaCppTag-bin-ubuntu-x64.tar.gz"\n'
          'curl -fsSL --retry 3 -C - -o "$tgz" "https://github.com/ggml-org/llama.cpp/releases/download/$P_llamaCppTag/llama-$P_llamaCppTag-bin-ubuntu-x64.tar.gz"\n'
          'echo "$P_llamaCppSha256  $tgz" | sha256sum -c - || { rm -f "$tgz"; echo "The download does not match its published checksum."; exit 1; }\n'
          'mkdir -p "$D/training/llama.cpp/bin"\ntar -xzf "$tgz" -C "$D/training/llama.cpp/bin" --strip-components=1\nrm -f "$tgz"\n'
          'printf "%s" "$P_llamaCppTag" > "$D/training/llama.cpp/.llmwizard-tag"\n',
   "verify": UV_ENV + '"$D/training/venv/bin/python" "$D/training/llama.cpp/convert_hf_to_gguf.py" --help >/dev/null && { "$D/training/llama.cpp/bin/llama-quantize" 2>&1 || true; } | grep -qi usage',
   "undo": UV_ENV + 'rm -rf "$D/training/llama.cpp"\n',
   "creates": [{"kind": "training-tool", "name": "llama.cpp"}]},
  {"id": "smoke", "title": "Check that training works on this processor", "why": "Runs one tiny training step on random numbers, so a broken install shows up now and not hours into a job.",
   "run": UV_ENV + '"$D/training/venv/bin/python" - <<\'PY\'\nimport time, torch\nm = torch.nn.Linear(256, 256)\nopt = torch.optim.AdamW(m.parameters())\nt = time.time()\nfor _ in range(20):\n    loss = m(torch.randn(64, 256)).pow(2).mean()\n    loss.backward(); opt.step(); opt.zero_grad()\nprint("ok", torch.__version__, "threads", torch.get_num_threads(), "cpu", torch.backends.cpu.get_cpu_capability(), "%.2fs" % (time.time() - t))\nPY\n',
   "facts": UV_ENV + '"$D/training/venv/bin/python" -c "import torch; print(\'training.cpuCapability=\' + torch.backends.cpu.get_cpu_capability())"'}]})

w("training-cleanup", {
 "id": "training-cleanup", "version": 1, "title": "Remove the fine-tuning tools",
 "description": "Deletes the training environment and llama.cpp's scripts. Jobs, datasets and finished adapters are kept unless you choose to delete them too. Models already registered with Ollama are not touched.",
 "params": {"deleteJobs": {"type": "bool", "default": "no", "description": "Also delete datasets, jobs, checkpoints and adapters."}},
 "steps": [
  {"id": "tools", "title": "Delete the training environment", "why": "About 2 GB in your home folder.",
   "run": 'D=' + DATA + '\nrm -rf "$D/training/venv" "$D/training/llama.cpp"\n',
   "removes": [{"kind": "training-venv", "name": "venv"}, {"kind": "training-tool", "name": "llama.cpp"}]},
  {"id": "jobs", "title": "Delete datasets, jobs and adapters", "why": "You chose to delete them. Running jobs are stopped first.", "requires": ["param:deleteJobs"],
   "run": 'D=' + DATA + '\nfor p in "$D"/training/jobs/*/pid; do [ -f "$p" ] && kill "$(cat "$p")" 2>/dev/null || true; done\nrm -rf "$D/training/jobs" "$D/training/datasets" "$D/training/adapters" "$D/training/hf"\n',
   "removes": [{"kind": "dataset", "name": "*"}, {"kind": "training-job", "name": "*"}, {"kind": "adapter", "name": "*"}]}]})
