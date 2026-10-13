# Build plan: your own AI, set up and run from your phone

Status: Phase 1 gate run on the real Hyper-V VMs (2026-10-10). Phases 2-4 built; Phase 5 waiting on Jay. BUILD_PLAN expansion (2026-10-13): E1, E2 (minus quant swap), E3, E4, E5.1 and the tools UI built; see docs/BUILD_PLAN-STATUS.md. (Keep this line current.)

Codename `llmwizard` until Jay names it. Written 2026-10-09 and revised the same day to fold in Jay's `CODE-PLAN.md` and the assistant half. `CODE-PLAN.md` is retired. Section 12 says where each part of it went.

How to read this. Sections 1 to 3 say what is being built and what is already decided. Sections 4 and 5 describe the designs that are easy to get wrong. Section 6 is the test setup. Section 7 is the work, in phases with gates. A fact marked *(verify)* came from the research pass or from general knowledge and has not been checked against a live source, so check it before building on it. Section 11 lists what was checked and where.

## 1. What this is

One Android app with two halves.

**The setup half** takes a Linux machine from nothing to a working local LLM, then stays useful as the chat client and management console for it.

1. Connect to the machine over SSH.
2. Run a read-only hardware scan, or take a typed description when there is no machine yet.
3. Recommend a model that will run at a usable speed on that hardware, with one faster and one smarter alternative.
4. Show every install step and the exact script behind it.
5. Run the steps, surviving the phone sleeping or dropping off Wi-Fi.
6. Measure real speed with a short benchmark.
7. Chat with the model and manage the server from the app.
8. Hand the endpoint to other apps, with exposure kept as tight as the user's choice allows.

**The assistant half** makes that AI the phone's assistant, able to text, call and set things up, with the phone as the gatekeeper.

1. Hold the assistant gesture, or open the app, and say or type what you want.
2. The request and the tool list go to the model on your own server, inside the same SSH connection.
3. The model answers with a proposed action: a tool name and its arguments.
4. The phone checks the proposal, looks up the contact, and shows a card with exactly what will happen.
5. You tap, it happens, and it lands in a log.
6. With no server in reach, a small on-phone path handles a short list of simple commands and says what it cannot do.

Who it is for: someone with a spare PC, home server or VM who can find its IP address and log in, and who does not want to learn runtimes, quantization or bind addresses. They cannot debug a broken install, so every failure needs a plain message and a next step. The assistant half is for the same person once the server runs, and it matters most to people who would rather their texts did not pass through someone else's cloud.

Why it is worth building: Android already has free clients that chat with an Ollama server someone else set up. Nothing provisions one from a phone. The value is in four places.

- **The honest recommendation.** What will be usable on this machine, including old and odd hardware, with the reason in one sentence.
- **The install that shows its work.** Every command visible beforehand, a record of what was changed, and an undo.
- **Safe defaults.** Ollama is not exposed to the network unless the user asks, and then as narrowly as possible. Research in early 2026 counted over 175,000 Ollama hosts exposed to the internet with no authentication *(from research)*. This app should never add to that number by accident.
- **A private assistant.** Gemini already texts and calls, through Google's cloud. The local-model chat apps checked on Play were chat-only. Open-source phone agents exist on GitHub for the root and Shizuku crowd (Operit AI is the big one). Nothing found makes a self-hosted model the phone's assistant with a confirm card in front of every send.

**Three releases.** Each one is worth shipping by itself.

| Release | What ships | Phases |
|---|---|---|
| 1 | Set up, chat with and manage your own AI server | 0 to 5 |
| 2 | That AI as the phone's assistant, with the server as the brain | 6 and 7 |
| 3 | An on-phone brain for offline use, and the direct-sale build with the root tiers | 8 and 9 |

Do not start a later release's phases before the earlier gate. Two small exceptions are marked where they occur: a zero-code check Jay does in Phase 0, and the agent core at the end of Phase 1.

## 2. Scope

### Release 1

**In**

- Targets: Ubuntu 22.04 or newer and Debian 12 or newer, x86_64, booted with systemd.
- Runtime: Ollama, installed natively as a systemd service.
- Acceleration: CPU-only, and NVIDIA GPUs whose driver already works (`nvidia-smi` runs).
- Machines that already run Ollama: adopted as they are, not reinstalled.
- A manual "what could my machine run?" mode that takes a typed hardware description and needs no server.
- One server in the free tier, more with Pro.

**Out, and why**

| Out of release 1 | Why |
|---|---|
| Installing GPU drivers, CUDA, ROCm | This is where machines get broken: black screens, kernel module mismatches. The app detects the situation and explains it. |
| AMD and Intel GPU acceleration | Ollama needs a separate ROCm package for AMD. Treated as CPU-only with a note for now. |
| Enabling or installing a firewall | Turning on ufw over SSH without an SSH rule locks the user out. The app only adds rules to a firewall that is already active. |
| Windows, macOS, WSL2, other distros, arm64 | Each one multiplies the test matrix for a solo developer. |
| llama.cpp server, LM Studio, vLLM, Docker installs | One runtime done well first. Docker also publishes ports past ufw, which is how servers end up exposed behind a firewall that looks fine. |
| Ads, analytics, crash-reporting SDKs, accounts, any backend of ours | Jay does not ship ads, and the app holds keys to people's servers, so nothing third-party rides along. |
| The assistant and on-phone models | Releases 2 and 3. |

### Release 2

**In:** the app as the phone's default assistant, six tools, a confirm card before every send, an audit log, text input first and voice on top, the server as the brain, and a pattern matcher for the commonest commands when there is no server.

**Out:** driving other apps through the accessibility API (Play prohibits it for autonomous actions), scheduled or unattended runs, a web fetch tool, sending through messaging apps that have no public API, and models on the phone.

### Release 3

**In:** an on-phone model for a short whitelist of tools, the `direct` build with a license key, root-tier tools, and Shizuku if it is healthy.

**Out:** the system-app module beyond a spike, and driving payment or banking apps.

## 3. Decisions already made

Changing one of these is a conversation with Jay, recorded in `DECISIONS.md`.

| Area | Decision | Why |
|---|---|---|
| Stack | Kotlin, Jetpack Compose, Material 3, single activity, coroutines and Flow, Hilt, Room, DataStore, OkHttp, kotlinx.serialization | The hard parts (SSH, Keystore, permissions) are JVM and Android native. This is the best-trodden path. |
| Module split | `:core` plain JVM, `:cli` JVM, `:app` Android | Logic can be tested and run without a device. Split `:core` further only if build times or boundaries call for it. |
| SSH library | sshj behind our own interface | Clean API, maintained. The interface keeps a swap cheap if Android crypto fights back. |
| Runtime | Ollama only | Simple documented install, systemd service, MIT license, a real model-management API, tool calling, and it runs on CPUs with AVX but no AVX2 *(from research)*. |
| Install method | Our own visible steps that mirror Ollama's documented manual install, not `curl ... \| sh` | Every command is ours and can be shown. The official script installs NVIDIA drivers by itself (the `cuda-drivers` package, kernel headers, DKMS) when it finds a card and no working `nvidia-smi`, which crosses a fence. |
| App to Ollama | HTTP carried inside the SSH connection. Ollama stays bound to 127.0.0.1. | Nothing is exposed by default, and no LAN or cleartext configuration is needed for the app's own use. The assistant uses the same path. |
| Long operations | Run detached on the target. The phone tails files. | Phones sleep, switch networks and kill apps. |
| Recipes | JSON data, previewed before running, signed when fetched remotely | Trust, Play policy, and fixes that ship without an app release. |
| Hardware scan | Our own read-only script and a tolerant parser | Zero install, transparent, testable with fixtures. The research suggested the llmfit tool. It stays a later, optional cross-check, because putting a third-party binary on the user's server cuts against "every command visible", and the recommendation logic is the product. |
| Recommendation | Our own catalog and scoring. Speed is a class before install and a measured number after. | Honest numbers are the differentiator. |
| Sudo | Asked for when needed, held in memory for the run, sent on stdin | Never in a command line, a log or on disk. |
| Secrets at rest | SSH private key encrypted with an Android Keystore AES-GCM key. `allowBackup=false`. | `androidx.security:security-crypto` is deprecated, so use the Keystore directly. |
| Assistant brain | The model on the user's own server first. An on-phone model is the fallback. | Small models are weak at tool calling. Google reports its tiny function-calling model at 85% on a narrow set after fine-tuning. A 30B-class model on the server does far better, and it is still private. |
| Gatekeeper | The model proposes and the phone decides | The model only returns a tool name and arguments. Code validates them, and anything that sends or deletes waits for a tap on a card that shows exactly what will happen. |
| The way onto Play | The app is the phone's default assistant. No accessibility API for driving apps. | Play allows SMS and call-log permissions only for the default SMS, phone or assistant app, and it prohibits using the accessibility API to act autonomously. |
| Tools | A registry: name, schema, risk, minimum tier. Six to start. | Data-driven, so adding a tool never touches the loop. A long tool list makes models pick worse, so the list grows only when measurements allow. |
| Outside content | Everything the assistant reads is data, never instructions | A text, a notification or a calendar entry can carry a sentence that reads like an order, and anyone can send the user a text. |
| No-model fallback | A pattern matcher for the commonest commands | It works with no server and no model, and it gives a Play reviewer something to test. |
| On-phone runtime | LiteRT-LM behind an `InferenceEngine` interface. llama.cpp can slot in later. | Stable Kotlin API, function calling with constrained decoding, and the small Gemma and function-calling models are published for it. |
| Privilege tiers | normal, Shizuku, root, system. Root is built first. | Each tool declares the lowest tier it needs. Shizuku was unreliable on Android 17 when this was written. |
| Distribution | A Play build and a `direct` build. The power tiers exist only in the direct build. | The direct build is a hedge and reaches the root crowd. Margin is not the reason: Play takes 15% all-in on the first $1M a year, and a direct sale through a merchant of record keeps about the same. |
| Money | Free core plus a one-time Pro unlock. No ads, no subscription. | Jay does not put ads in his apps. That is a standing rule, not a trade-off to revisit. A one-time unlock also suits a small, privacy-minded audience (tens of thousands of users at most). |
| SDK levels | minSdk 29. Compile and target the newest stable SDK. | The assistant role API needs Android 10. Play requires target 36 or higher for new apps. Target 37 if the toolchain is ready, and build the local network permission flow in from the start. |

## 4. Architecture

```
Phone (:app)                                 Target machine (Ubuntu / Debian)
+--------------------------+   SSH, port 22   +--------------------------------+
| Compose UI, ViewModels   | ---------------> | sshd                           |
| Assistant sheet, cards   |   exec: scan,    |   scan script (read-only)      |
| Tool executors           |   step scripts,  |   step scripts + job directory |
|            |             |   job tail       |   direct-tcpip --> Ollama      |
|          :core           |   direct-tcpip   |          127.0.0.1:11434       |
|  ssh, jobs, scan,        |                  |   (chat, and tool proposals    |
|  recipes, catalog,       |                  |    for the assistant)          |
|  ollama client,          |                  |                                |
|  tools, brains, eval     |                  |                                |
+--------------------------+                  +--------------------------------+
```

`:cli` sits where `:app` does and uses the same `:core`.

The shapes that matter (illustrative, not final signatures):

```kotlin
interface SshTransport { suspend fun connect(target: Target, auth: Auth, hostKeys: HostKeyStore): SshSession }

interface SshSession : AutoCloseable {
    suspend fun exec(script: String, stdin: ByteArray? = null, timeout: Duration): ExecResult
    suspend fun openDirect(host: String, port: Int): DuplexStream   // carries HTTP to Ollama
}

interface StepRunner { suspend fun run(step: PlannedStep): StepResult }  // real one uses SshSession, tests use a fake

enum class Risk { READ, WRITE, SEND, DESTRUCTIVE }
enum class Tier { NORMAL, SHIZUKU, ROOT, SYSTEM }

data class ToolSpec(val name: String, val description: String, val parameters: JsonSchema,
                    val risk: Risk, val minTier: Tier, val outsideContent: Boolean)

interface Brain { suspend fun propose(request: String, tools: List<ToolSpec>, history: List<Turn>): Proposal }
interface ToolExecutor { suspend fun execute(call: ToolCall): ToolResult }  // Android ones in :app, fakes in tests
```

Everything above `SshSession` is written against these interfaces, so the recipe engine, the scanner, the recommendation engine and the assistant loop can all be tested with fakes and fixtures.

## 5. Designs that need care

### 5.1 SSH

- **Library.** sshj (0.40.0 was current when this was written) behind `SshTransport`. If it fights on Android, the fallbacks are ConnectBot's sshlib (the library behind the ConnectBot Android app; its README gives the coordinates `org.connectbot.sshlib:sshlib`) and `com.github.mwiede:jsch` *(verify both before switching)*.
- **The Android crypto trap.** Android ships a cut-down BouncyCastle under the same provider name as the full one, and sshj has needed algorithms the cut-down one lacks. The usual fix is to remove the platform provider at app start and insert the full `BouncyCastleProvider`, plus R8 keep rules for sshj and BouncyCastle. The open-source Android Password Store app did exactly this when it adopted sshj (its pull request 801). That project was archived in October 2024, so treat it as a real but dated example. sshj made BouncyCastle optional in 0.39.0, so check what the current version needs on Android before copying old advice. This class of bug only shows up on a real device in a minified release build, which is why Phase 0 tests exactly that.
- **Keys.** The app generates an Ed25519 keypair. The private key is serialized, encrypted with a Keystore-held AES-GCM key, and stored in app-private storage. If Ed25519 through sshj on Android costs more than a session of fighting, ECDSA P-256 is an acceptable fallback, and the reason goes in `DECISIONS.md`.
- **First contact.** Connect with a password, then offer to append the app's public key to `~/.ssh/authorized_keys` as a visible step. After that the app logs in with the key and the password is dropped. A user can also paste the public key in themselves.
- **Host keys.** Trust on first use. Show the SHA256 fingerprint in OpenSSH's format so it can be compared with `ssh-keygen -lf` on the server. Store it. If it ever changes, refuse to connect and explain why. Forgetting a host key is a deliberate action the user takes.
- **Blocking I/O.** sshj blocks. Run it on the IO dispatcher. Coroutine cancellation does nothing until the channel is closed, so cancellation closes the channel.
- **Errors.** Typed: unreachable, authentication failed, host key changed, timed out, local network permission missing. Each maps to a message with a next step.
- **Local network permission.** On Android 17, an app that targets SDK 37 needs the `ACCESS_LOCAL_NETWORK` runtime permission before it can open a TCP connection to a LAN address. Without it the connect fails with an ordinary socket error. Ask for it right before the first connection, with one sentence saying why, and map the failure to its own error. Apps that target 36 get the access implicitly through `INTERNET`.

### 5.2 Running things on the target

- **Every step is a script file.** The app writes the step's script into a job directory on the target, runs it with `/bin/sh`, and captures the log and exit code into files next to it. The preview shows the same text that gets written. This removes quoting problems, works whatever the user's login shell is, and leaves an exact record of what ran.
- **Job directory.** `~/.local/state/llmwizard/jobs/<jobId>/` holding `step-NN.sh`, `step-NN.log`, `step-NN.exit` and a `pid` file.
- **Short steps** run attached, with a timeout.
- **Long steps** (downloads, model pulls) run detached. A workable mechanism is `setsid nohup /bin/sh step.sh > step.log 2>&1 < /dev/null &`, with the wrapper writing the exit code to `step.exit` when it finishes. The phone reads new output by byte offset (`tail -c +N`) and watches for the exit file. Requirements: survives an SSH drop and the app being killed, can be picked up again by job id, needs no extra packages on the target, works under sudo.
- **Preamble on every script:** `set -eu`, a fixed `PATH` that includes the sbin directories, `LC_ALL=C`, and `DEBIAN_FRONTEND=noninteractive` for apt steps.
- **Transcript.** The phone keeps a per-server log of every script it sent, the exit code and the tail of the output. It is viewable and exportable. It never contains anything that was sent on stdin.
- One job per server at a time in release 1. Prune old job directories.

### 5.3 Sudo

- **Detect first.** `id -u` is 0: no sudo needed. `sudo -n true` succeeds: passwordless sudo. Otherwise a password is needed. No sudo rights at all: the app cannot install, so say so and stop.
- **Check the password once, up front**, with `sudo -k -S -p '' true` and the password on stdin. The exit status gives the answer without parsing prompts, which are translated on non-English systems.
- **Each privileged step** runs as `sudo -S -p '' /bin/sh <script>` with the password written to the channel's stdin. Do not count on one authentication covering later exec channels, since sudo's credential cache is tied to the terminal or session *(verify)*.
- **The password** is asked for when a run needs it, held as a `CharArray`, wiped when the run ends, and never stored.
- **Day-two convenience, opt-in and off by default:** a sudoers drop-in that lets this user run exactly `systemctl start|stop|restart ollama` and the Ollama log read without a password. Validate the file with `visudo -cf` before moving it into `/etc/sudoers.d/`, because a broken sudoers file locks people out of sudo. It is recorded in the manifest and removed by uninstall.

### 5.4 Hardware scan

One POSIX `sh` script. Read-only, no sudo, nothing installed, a few seconds to run. It prints sections between markers. Each probe is guarded so that a missing tool skips that section instead of aborting. The user can view the script before it runs.

| Section | Source (starting point) |
|---|---|
| OS | `/etc/os-release`, `uname -m`, `uname -r` |
| Init | `[ -d /run/systemd/system ]` |
| Virtualization | `systemd-detect-virt` (Hyper-V reports `microsoft`) |
| CPU | `lscpu`; the `flags` line of `/proc/cpuinfo` for avx, avx2, avx512f, fma, f16c |
| Topology | sockets, cores and threads from `lscpu`; NUMA nodes from `/sys/devices/system/node/` |
| Memory | `MemTotal`, `MemAvailable`, `SwapTotal` from `/proc/meminfo` |
| GPU | PCI class and vendor from `/sys/bus/pci/devices/*/` (works without `lspci`); `nvidia-smi --query-gpu=name,memory.total,driver_version --format=csv,noheader,nounits` when present |
| Disk | `df -B1` on `/` and on the models directory if it exists |
| Existing Ollama | `command -v ollama`, `ollama --version`, `systemctl is-active ollama`, whether something answers on 127.0.0.1:11434 |
| Network | `ip -4 -o addr show scope global`, `ss -ltn` |
| Tools | `command -v` for curl, zstd, tar, sudo, systemctl |
| Privilege | `id -u`, `sudo -n true` |

The parser turns this into a `HardwareProfile` in which every field is optional. On top of the profile sit **verdicts**: short plain-English findings with a severity. Examples:

- "This CPU has AVX but not AVX2. Local models work, slower than on newer chips."
- "This is a Hyper-V virtual machine and AVX is not visible to it. Processor compatibility mode is probably switched on for this VM. Turning it off on the host will make models run several times faster."
- "There is an NVIDIA card but no working driver. Models will run on the CPU. Here is how to install the driver yourself."
- "This machine does not use systemd. It is not supported yet."

DIMM speed and channel count need root (`dmidecode`) and mean nothing inside a VM. The scan does not ask for sudo. Memory bandwidth is estimated instead (5.5).

### 5.5 Catalog and recommendation

**Catalog entry** (illustrative):

```json
{
  "id": "example-moe-q4",
  "name": "Example 30B-A3B",
  "ollamaTag": "example:30b-a3b-q4_K_M",
  "paramsB": 30.5,
  "activeParamsB": 3.3,
  "quant": "Q4_K_M",
  "downloadBytes": 19000000000,
  "uses": ["chat", "code"],
  "quality": 4,
  "minOllamaVersion": "0.0.0",
  "toolAccuracy": null,
  "notes": "Mixture-of-experts: only about 3B parameters are active per token, so it runs far faster on CPUs than its size suggests."
}
```

**Fit** is a hard gate.

- Memory: `downloadBytes` plus context overhead plus about 1 GB must stay under roughly 85% of where the model will live. That is VRAM for a full GPU load and `MemAvailable` for CPU. Partial GPU offload counts as CPU-class speed in release 1.
- Disk: free space on the filesystem that holds the models directory must cover `downloadBytes` plus 2 GB, plus the runtime if it is not installed yet.

**Speed class** is the soft part, estimated before install.

- GPU, fully in VRAM: fast.
- CPU: inference is limited by memory bandwidth, not by how much RAM there is. Tokens per second is roughly `k x effective bandwidth / bytes of weights touched per token`. For a dense model that is the whole file. For a mixture-of-experts model it is roughly the active share, which is why those models are the right default on CPU-only machines.
- First calibration point *(from research)*: dual Xeon E5-2690 v2 with DDR3 measured about 5.2 tok/s on a 4B-active MoE model at Q8, on roughly 40 to 50 GB/s of effective bandwidth. That puts `k` near 0.5. Q4 roughly doubles the speed.
- Bandwidth is guessed from the CPU generation with a small table and a wide range. Starting values to calibrate *(verify)*: old quad-channel DDR3 server 40 to 50 GB/s, dual-channel DDR4 desktop 25 to 40, dual-channel DDR5 50 to 80.
- Classes, to be tuned: fast is 20 tok/s and up, comfortable 8 to 20, usable 4 to 8, slow 1.5 to 4, painful below 1.5.
- Keep `k`, the bandwidth table and the class boundaries in the catalog data so they can be tuned without an app release.

**Ranking.** Among models that fit and reach "usable", take the highest `quality`, and the faster one on a tie. Offer three: the pick, a faster alternative, and a smarter but slower alternative, each with one sentence of reasoning. If nothing reaches "usable", say so and offer the least bad option. "Fits" is not the same as "good": when a model fits but is weak at what the user said they want it for, say so. `toolAccuracy` comes from the eval set in 5.14 and feeds that note for anyone who wants agents or the assistant.

**After install.** Run a short fixed prompt and read Ollama's own timing fields from the final response (`eval_count`, `eval_duration`, `load_duration`). Store the measured tok/s and load time per server and model. Show the measurement everywhere the estimate was shown. If it lands two classes below the estimate, say so and offer the faster alternative. Record the profile, model, estimate and measurement in the transcript, since those pairs are how the constants get calibrated.

**Upkeep.** A `catalog-check` command in `:cli` resolves every `ollamaTag` against the Ollama registry and fills `downloadBytes` from the manifest (`https://registry.ollama.ai/v2/library/<name>/manifests/<tag>` *(verify)*). The test suite fails on a tag that does not resolve. The first catalog has 15 to 20 entries covering tiny, small, mid, large and XL machines for chat and for coding, with at least two MoE entries for CPU-heavy machines. Candidate names from the October 2026 research, each to be confirmed on ollama.com before it goes in: Qwen3.6-35B-A3B, Gemma 4 26B-A4B, gpt-oss-20b, gpt-oss-120b, Qwen3-Coder 30B-A3B, Qwen3.5 4B.

### 5.6 Recipes

A recipe is data. Illustrative shape:

```json
{
  "id": "ollama-install",
  "version": 1,
  "title": "Install Ollama",
  "targets": { "os": ["ubuntu>=22.04", "debian>=12"], "arch": ["x86_64"], "init": "systemd" },
  "params": { "bind": "127.0.0.1:11434", "contextLength": 8192 },
  "steps": [
    {
      "id": "prereqs",
      "title": "Install curl if it is missing",
      "why": "Needed to download Ollama.",
      "sudo": true,
      "skipIf": "command -v curl >/dev/null",
      "run": "apt-get -o DPkg::Lock::Timeout=120 update -qq && apt-get -o DPkg::Lock::Timeout=120 install -y -qq curl",
      "verify": "command -v curl >/dev/null",
      "undo": null,
      "detached": false,
      "timeoutSec": 600
    }
  ]
}
```

The engine runs in stages:

1. **Preflight.** Read-only checks: supported OS and architecture, systemd running, sudo available, enough disk and memory for the chosen model, the target can reach the download hosts, port 11434 is free or already Ollama.
2. **Plan.** Substitute parameters through the quoting function. Evaluate each step's `skipIf` so finished steps drop out.
3. **Preview.** Show the remaining steps, each with its title, its reason and the exact script.
4. **Execute.** One step at a time through `StepRunner`. Stop on the first failure and show the log.
5. **Verify.** Run each step's `verify`.
6. **Record.** Write a manifest on the target (`~/.local/state/llmwizard/installed.json`) listing every file, user and unit the app created. Uninstall reads it. A reinstalled app or a second phone reads it to recognize an existing setup.

Properties to hold:

- **Idempotent.** Running install on a half-finished machine finishes it. Running it on a finished machine does nothing and says so.
- **Adopts, never tramples.** If Ollama was already there, the app adds only its own namespaced drop-in. Uninstall then removes only that. The user's models are never deleted without a confirmation that lists them.
- **Signed when remote (Phase 5).** Ed25519 detached signatures, two pinned public keys in the app (current and next) so the key can be rotated, a bundled copy as fallback, and a refusal to accept a lower version than the one already seen. A recipe is root on someone's server, so an unsigned or badly signed one is ignored.

First recipes: `ollama-install`, `ollama-uninstall`, `ollama-update`, `ollama-configure`, `authorized-key-add`, `sudoers-dropin`. Phase 4 adds `expose-lan`, `expose-private` and `tailscale-setup`.

### 5.7 Installing Ollama

Mirror the manual install in Ollama's Linux docs, as our own steps. Read the current docs and the current official `install.sh` first, and make the recipe do what the script does on the CPU and driver-already-present path, without the driver installation.

Two things the docs and the script disagreed on when this was written, so settle them against the live sources:

- **Archive format.** The docs download `ollama-linux-amd64.tar.zst`. The script downloads `ollama-linux-amd64.tgz` and unpacks it with `tar -xzf`. Check which one the current release publishes. `zstd` is only a prerequisite if the `.tar.zst` is used.
- **Install prefix.** The docs unpack into `/usr`. The script unpacks into `/usr/local` whenever `/usr/local/bin` is on the `PATH`, which is the normal case. Use `/usr/local` for our installs so that machines we set up look like machines the script set up, and adopt whatever location an existing install already uses.

Steps:

1. Make sure the download and unpack tools are present (apt, only if missing).
2. Download the archive into the job directory as a detached, resumable job. Record its version and sha256. The script requests a specific version by appending `?version=<x>` to the URL.
3. If upgrading, stop the service and remove the old `lib/ollama` directory under the install prefix, as the script does.
4. Unpack into the install prefix.
5. Create the `ollama` system user and group if they are missing (`useradd -r -s /bin/false -U -m -d /usr/share/ollama ollama`). Add that user to the `render` and `video` groups when they exist, as the script does, since GPU access depends on it.
6. Write `/etc/systemd/system/ollama.service`, matching the script's unit but with a fixed `PATH`.
7. Write our settings to a namespaced drop-in, `/etc/systemd/system/ollama.service.d/10-llmwizard.conf`: bind address, context length, keep-alive. A user's own `override.conf` is left alone.
8. `systemctl daemon-reload`, then enable and start the service.
9. Verify: the service is active and `/api/version` answers on 127.0.0.1.
10. Pull the chosen model as a detached job that calls the local API with `curl -N` and writes the progress stream to the job log, so the phone can show progress and the pull survives a disconnect.
11. Benchmark.

Until remote recipes exist, install the latest version and record the version and hash in the manifest. Once they do, the recipe pins a tested version and its sha256, and the pin is bumped by publishing a recipe.

Uninstall follows the docs' list in reverse: stop and disable the service, remove the unit and our drop-in, the binary, the library directory, the user and group, and the models directory, limited to what the manifest says the app created.

### 5.8 Talking to Ollama

- The app reaches Ollama through the SSH connection, at whatever address Ollama is bound to. That is 127.0.0.1:11434 by default.
- **Preferred:** an OkHttp `SocketFactory` whose sockets are sshj direct-tcpip channels. OkHttp's own samples do the same trick for Unix domain sockets *(verify)*. No port is opened on the phone, so other apps cannot piggyback.
- **Acceptable first cut:** sshj's local port forwarder bound to 127.0.0.1 on a random port.
- Either way the Android network security config allows cleartext only for the hostname the tunnel uses.
- Use the native `/api/*` endpoints for chat, management and tool calling, because they report load state and timings. The OpenAI-compatible `/v1` path is what gets handed to other apps.
- Parse leniently (`ignoreUnknownKeys`). The API grows fields.
- Streaming responses are one JSON object per line. The last one carries the timing fields.
- Read timeouts have to allow for a large model loading from disk, which can take minutes on a CPU-only machine. Show "loading model" as its own state, separate from "generating".
- Ollama's default context window is 4096 tokens and longer conversations are truncated without warning. Pick the context length deliberately: set it in our drop-in for the server default and show it in settings.
- Unload a model with a generate call that sets `keep_alive` to 0.

### 5.9 Exposure (Phase 4)

Three modes, shown as a plain choice.

1. **Private (default).** Ollama on 127.0.0.1. Only this app can use it, through SSH.
2. **Home network.** Other devices on the LAN can connect.
3. **Tailscale (Pro).** The user's own devices can connect from anywhere. The internet cannot.

Guardrails for the home network mode:

- Read the state first, with sudo: current bind address, addresses on each interface, whether any is public (not private, CGNAT or link-local), and whether a firewall is active.
- If any interface has a public address, do not offer `0.0.0.0`. Binding to the private address is still allowed. Explain why.
- If ufw is active, add an allow rule for port 11434 from the LAN subnet only.
- If no firewall is active, do not enable one. Bind to the specific LAN address and say plainly that anything on that network can now use the model.
- Ollama has no authentication. Say so on this screen.
- Every change is a recipe with an undo. "Go back to private" is one tap.
- Once Ollama leaves loopback, the app's own tunnel points at the new bind address.
- Proxies: the Ollama docs' nginx example rewrites the `Host` header to `localhost:11434`, which implies Ollama rejects other `Host` values while bound to loopback *(verify)*. Anything that fronts Ollama, including `tailscale serve`, has to deal with that.

The connect screen shows the OpenAI-compatible base URL (`http://<address>:11434/v1`, any non-empty API key) and the native URL, with copy buttons, a QR code, and ready-made snippets for Open WebUI, Continue, Home Assistant, n8n and a `curl` test. Snippets are data in the same signed bundle as the recipes, since those tools change their config formats.

Exposure check: from the server, report the bind address, firewall state and any public address. From the phone, when it is on mobile data, try a raw TCP connection to the server's public address on port 11434. If that connects, show a red warning with the fix.

### 5.10 Phone-side storage and resume

- **Room:** servers (name, host, port, user, auth type, key reference, host key fingerprint, last hardware profile, install state), conversations, messages, jobs (server, job id, kind, state), benchmark results, and from release 2 the assistant's audit log.
- **DataStore:** settings.
- **Key blobs:** files encrypted with the Keystore key.
- `allowBackup=false`, so neither keys nor server details nor the audit log leave the device.
- **Resume.** The app can be killed at any time. A job row in the "running" state means: reconnect when the user opens that server, ask the target for the job's status, and carry on tailing. Wizard state is rebuilt from the job row, the job directory on the target, and the manifest.

### 5.11 The assistant: role, entry and voice (release 2)

- **Becoming the assistant.** Android lets the user pick a "digital assistant app". An app becomes pickable by handling the assist action: an activity with the `android.intent.action.ASSIST` intent filter is the minimum, and a `VoiceInteractionService` is the fuller integration *(verify which one current Android needs for the long-press gesture and for holding the role)*. `RoleManager` can report whether the app holds `ROLE_ASSISTANT`. It cannot show a request dialog for that role. So onboarding explains, opens the settings page (`Settings.ACTION_VOICE_INPUT_SETTINGS` opens its parent), and re-checks `isRoleHeld` on return instead of trusting a result code.
- **Order matters for Play.** The app must hold the role before it asks for any SMS or call-log permission. Ask per tool, the first time that tool is used.
- **Losing the role.** One report says the default is cleared when the APK is reinstalled in place on Android 16. Assume it can vanish: check on every invocation and show a one-tap way back. During development, a script restores it over ADB after each install.
- **Entry points.** The assistant gesture opens a sheet over whatever the user was doing. The same sheet is reachable from inside the app. Text input first. Voice is a layer on top.
- **Voice.** Speech-to-text and text-to-speech sit behind interfaces. Start with Android's own (`SpeechRecognizer`, preferring on-device recognition where the phone offers it, and `TextToSpeech`). Phones without Google services may have neither, so an offline engine such as sherpa-onnx or whisper.cpp is the later fill-in *(verify)*. Say on screen whether speech is processed on the phone.
- **Without the role.** The assistant still works from inside the app with the tools that need no restricted permission: alarms, timers, calendar, calls. The SMS tools stay off until the role is held.

### 5.12 Tools (release 2)

- **Registry.** Each tool has a name, a one-sentence description, a JSON schema for its arguments, a risk, a minimum tier, the Android permissions it needs, and a flag saying whether its result contains outside content. The registry lives in `:core`. The executors live in `:app`.
- **Risk levels:**

| Risk | Meaning | Behavior |
|---|---|---|
| read | Looks something up | Runs without asking. If the result has outside content, 5.13 applies. |
| write | Changes something on the phone that can be undone | Runs, shows what it did, offers undo. |
| send | Leaves the phone or costs money: texts and calls | Always a confirm card. |
| destructive | Deletes | Always a confirm card. |

- **The confirm card** shows the resolved recipient (the name and the actual number), the exact text, and which SIM if there are two. The text can be edited. Nothing the model produced is hidden from the card.
- **Code checks the arguments.** The proposal is validated against the schema. Names are resolved to contacts by code on the phone, never by the model: the model says "Mike", the phone finds the Mikes, and the card shows the number. Ambiguity becomes a picker.
- **Audit log.** Every proposal and its outcome: time, request, tool, arguments, which brain proposed it, confirmed or declined, result. Viewable and exportable. Each tool has a switch in settings.
- **The first six:**

| Tool | Risk | Notes |
|---|---|---|
| `contacts.find` | read | Returns names and number labels only |
| `sms.send` | send | Needs the assistant role on Play |
| `sms.read_recent` | read, outside content | Needs the role. Message bodies are untrusted. |
| `call.place` | send | `CALL_PHONE`, which is not a restricted permission |
| `clock.set` | write | An alarm or a timer, through the system clock intents |
| `calendar.create_event` | write | Undo deletes the event |

A flashlight or volume tool is a fine seventh for demos. Add tools one at a time, and only when the eval set (5.14) shows the model still picks correctly with the longer list.

- **Kept off the list for now:** anything that needs the accessibility API. Wi-Fi in the normal tier, since apps have not been allowed to switch it since Android 10, and check the other toggles before promising them. A web fetch tool. Sending through messaging apps that have no public API. Replying to those apps through notification actions is a later tool.

### 5.13 Outside content and unattended runs

A text, a notification, a calendar entry, a file or a web page can contain a sentence that reads like an instruction. A model that reads such content and can also act is the classic injection setup, and anyone can send the user a text.

1. Outside content goes to the model inside a clearly marked data block, and the system prompt says content there is never an instruction. That lowers the odds. It is not a defense by itself.
2. The defense is the card. Every `send` and `destructive` call is confirmed with the full, exact payload visible, whichever brain proposed it.
3. Once a turn has read outside content, `write` tools in that same turn also need a tap.
4. No tool both reads outside content and transmits something off the phone. A web fetch tool, if it ever exists, is off by default and cannot run in a turn that has read private content.
5. What goes to the server is the request, the tool list and tool results. It travels inside the SSH connection to the user's own machine. Send the minimum: candidate names, not the address book.

**Unattended runs** (scheduled tasks) are out of release 2. When they come: a fixed tool list per task, recipients fixed when the task is created and never chosen at run time, and a notification after each run. "Summarize my calendar and text it to me" fits. "Reply to whoever texts me" does not.

### 5.14 Brains and routing

Three implementations of `Brain`:

1. **Server brain.** Ollama's `/api/chat` with the `tools` field, through the tunnel. Proposals come back as `tool_calls`. Tool results go back as `tool` messages when a follow-up answer is needed.
2. **Pattern matcher.** A few dozen patterns for the commonest commands ("text X ...", "call X", "set a timer for ..."). No model, no server, instant. It handles what it is sure about and passes on the rest.
3. **On-phone model** (release 3). LiteRT-LM behind `InferenceEngine`, limited to a whitelist of tools.

**Routing in release 2:** the pattern matcher when it is certain, otherwise the server. If the server cannot be reached, say so and offer what the pattern matcher can do.

**Routing in release 3:** the on-phone model slots in between for its whitelisted tools. Route by rule. Do not ask a small model whether it can cope.

**Latency.** A CPU-only server takes seconds per proposal. Keep the system prompt and tool list byte-identical between calls so the server can reuse the processed prefix *(verify that Ollama does)*, keep the model loaded with `keep_alive`, cap the reply length, and show a "thinking" state at once. Measure and show the time per command.

**Reach.** The server brain needs the SSH connection. Away from home that means Tailscale or another route the user has set up. The assistant shows which brain answered.

**The eval set.** About a hundred phone commands, each with the expected tool and arguments, kept in the repo. `cli eval-tools <model>` runs them against a server model and reports accuracy and time per command. Release 3 runs the same set on the phone. It is used for:

- marking server models as good with tools in the catalog (`toolAccuracy`);
- deciding which tools the on-phone model may handle, by measured accuracy per tool;
- catching regressions when a tool is added;
- letting the server model grade a small model's answers on commands outside the set.

**Known numbers.** Google reports its 270M function-calling model at 58% on its mobile-actions test before fine-tuning and 85% after, at about 142 tokens a second on a Pixel 7 Pro's CPU. That is roughly one command in seven wrong, which is why the on-phone brain has a gate in Phase 8.

### 5.15 Privilege tiers and the direct build (release 3)

Each tool declares the lowest tier it needs. The app detects the tier at start and tools light up accordingly.

| Tier | How the app gets it | What it adds |
|---|---|---|
| normal | Ordinary permissions and the assistant role | The release 2 tool list |
| Shizuku | The user runs Shizuku | Shell-level commands: toggles, input injection |
| root | `su` from Magisk, KernelSU or APatch | What Shizuku gives without re-pairing, plus granting its own permissions and keeping the model's process alive |
| system | A module installs the app as a privileged system app | Permissions reserved for system apps, including the app-functions permission |

- **Root first.** Shizuku was failing to authorize apps on Android 17 when this was written, with users on forks. Build the root executor first (libsu is the usual library) and add Shizuku when it is stable again.
- **The model never gets a shell.** Root tools are fixed commands with validated arguments, built the way recipe steps are (5.6), through the same quoting function. No tool takes a free-form command. If Jay wants a "run this command" tool for power users, it is off by default and shows the exact command on a confirm card every time.
- **Root-tier tools, in this order:** Wi-Fi, Bluetooth, mobile data and airplane mode. Then granting the app its own permissions so the sideloaded build skips the restricted-settings screens. Then keeping the on-phone model's process from being killed. Driving other apps' screens (tap, type, read the layout) comes last, behind an experimental switch, with payment and banking apps refused by default.
- **The system tier is a spike.** On most Android 16 phones the app-functions permission is granted only to privileged system apps. A public sample obtains it on a rooted device by installing the agent under `/system/priv-app`. If a module can do that cleanly, the assistant could call the functions other apps publish for system agents. A wrong privileged-permission allowlist bootloops a phone, so this stays a spike until it is proven on Jay's own devices.
- **The direct build.** A `direct` product flavor: no Play Billing library, a license key instead, and the power tiers. The Play build never contains the root or Shizuku code paths. Decide on purpose whether the two builds share an applicationId and signing key, since that decides whether one can replace the other, and write the decision down.
- **License key.** Behind the same entitlement interface as Play Billing. Constraints: no server of ours, at most one network call at activation, and it works offline afterwards. Pick the store late. Lemon Squeezy (5% plus 50 cents, issues keys, has a license API) was being folded into Stripe when this was written.
- **Sideloading rules to design for.** Android 15 and later stop a sideloaded app from being granted SMS, accessibility, notification access and a few others until the user allows restricted settings for it. Google's developer verification reaches all sideloaded apps worldwide in 2027: register the direct build's package and key under Jay's verified developer account, or buyers face a developer setting and a 24-hour wait before they can install.

## 6. Test rig

**Target VMs on Jay's DL380p (Hyper-V).** Jay creates these.

- `wiz-ubuntu`: the current Ubuntu Server LTS (26.04 when this was written), fixed memory of 64 GB or more to exercise the large-model path, vCPUs equal to one socket's physical core count, processor compatibility mode off, OpenSSH server on, one user whose sudo asks for a password.
- `wiz-small`: an older Ubuntu LTS (24.04) with 8 GB of RAM and 4 vCPUs. It covers the low end and an older release at once.
- `wiz-debian`: Debian 12 or 13, any modest size, with passwordless sudo for its user so that path gets exercised too.
- Each gets a checkpoint named `clean`, taken right after the OS install and SSH setup. On the host, `Restore-VMCheckpoint -VMName wiz-ubuntu -Name clean -Confirm:$false` puts it back.

One Hyper-V host can stand in for several hardware tiers this way.

**What that hardware is.** HP ProLiant DL380p Gen8, dual Xeon, 256 GB RAM, no GPU, Windows Server 2025 Datacenter as the Hyper-V host. That generation takes Xeon E5-2600 or E5-2600 v2 processors, which have AVX but not AVX2 or FMA *(from research)*. It is a good torture test: old instruction set, two NUMA nodes, virtualized, CPU-only.

**What the app should conclude on it.** Ollama works, CPU-only. Confirm which CPU backend Ollama's log says it loaded. RAM capacity is irrelevant and memory bandwidth sets the speed. The pick should be a mixture-of-experts model with roughly 3 to 4 billion active parameters at Q4, expected around 5 to 10 tok/s *(research estimate, extrapolated from a benchmark on near-identical hardware, so measure it)*. A dense model of 27B or more fits easily and should be flagged as slow, at 1 to 3 tok/s. If AVX is missing from `/proc/cpuinfo` inside the guest, the app should say that processor compatibility mode is probably on. As an assistant brain this server will take several seconds per command, which makes it a fair worst case for the latency work in 5.14.

**Jay's MX Linux desktop.** Debian family, but MX has traditionally defaulted to SysV init instead of systemd *(verify on the machine)*. Either way the app must report it correctly: supported when systemd is running, a clear "not supported yet" when it is not.

**No GPU machine yet.** The GPU path cannot be proven on hardware Jay owns. Until a machine with an NVIDIA card is available it is covered by fixtures only. Getting access to one is a Phase 5 task.

**Phones.** Jay picks these.

- Two phones from different makers for the assistant role in Phase 6, since makers differ in where the assistant setting lives and how the gesture behaves.
- An 8 GB phone as the floor for the on-phone brain in Phase 8.
- A rooted phone for Phase 9.
- A phone without Google services, if one is around, for the voice fallbacks.

**Credentials.** `targets.local.properties`, gitignored: a name, host, port, user and key path per target. Use a key made for testing, not Jay's personal one. An entry can also carry the sudo password so that `:cli` runs unattended. That is acceptable only because these are throwaway VMs. A real machine's password never goes in that file, and the app itself never stores one.

**Optional.** If Jay wants the coding agent to revert VMs itself, he can enable OpenSSH Server on the Hyper-V host and add it as a target. That hands the coding agent a shell on the hypervisor, so it is his call.

## 7. Phases

Time figures are rough and assume part-time work. Release 1 is about twelve weeks. Release 2 adds about six, and release 3 about seven.

### Phase 0: setup and two spikes (about a week)

Goal: a repository that builds, and proof that the two riskiest assumptions hold. SSH works from the JVM against a real machine, and SSH works from a real phone in a release build.

- [x] Check the dev machine: JDK, Android SDK and platform, `adb devices`, git. Report gaps to Jay. Ask before installing system packages.
- [x] Scaffold: Gradle version catalog with current stable versions, the three modules, `.gitignore` (including `targets.local.properties` and keystores), `DECISIONS.md`. Replace the Commands block in `SPEC.md` with what works here.
- [x] Spike A, JVM: `cli ping <target>` connects with a password or key, prints the host key fingerprint, and runs `uname -a`. Covered by a test against an embedded SSH server (Apache MINA SSHD is the usual choice).
- [x] Spike B, Android: one throwaway screen that generates an Ed25519 key, connects, and runs a command. It has to pass in a minified release build on a physical phone.
- [x] Record the SSH library decision in `DECISIONS.md`.
- [ ] Draft the scan script. Capture the first fixtures from `wiz-ubuntu` and from Jay's MX Linux desktop. (Script and wiz-ubuntu/DL380p fixtures done; MX desktop fixture still needed.)

Jay's part: create the VMs and checkpoints, fill in `targets.local.properties`, plug in a phone with USB debugging on. One more thing, with no code involved: install Google's AI Edge Gallery, try its Mobile Actions demo on his own phone, and note how often it picks the right action. That is a free preview of what an on-phone brain can do.

Gate:

- `./gradlew check` passes.
- `cli ping wiz-ubuntu` prints the VM's kernel line and a fingerprint that matches one of the VM's host keys. On the VM, `for f in /etc/ssh/ssh_host_*_key.pub; do ssh-keygen -lf "$f"; done` lists them.
- The phone, on a release build, connects with its own key and shows the command output.

### Phase 1: the engine, on the JVM (about three weeks)

Goal: the full flow works from a terminal against the test VMs with no Android involved.

Why this comes before any UI: everything risky lives here (sudo, detached jobs, recipes, real hardware), and the terminal is a loop a coding agent can run and read by itself. The app then becomes a thin layer over code that already works.

SSH layer

- [x] Exec with timeout and stdin, typed errors, keepalive, reconnect
- [x] Trust-on-first-use host key store (interface in `:core`, file-backed for the CLI)
- [x] Sudo detection, password check, privileged exec
- [x] Writing a file to the target
- [x] A direct-tcpip channel usable as an OkHttp socket

Remote jobs

- [x] Job directory, script-file steps, attached and detached runs, tail by byte offset, status, cancel, prune
- [x] Test: drop the SSH connection mid-job, reconnect, and the job is still running with continuous output

Scan

- [x] Scan script with section markers and a tolerant parser producing `HardwareProfile`
- [x] Verdicts in plain English
- [x] `cli scan --save-fixture <name>`, and a fixture from every machine available

Catalog and recommendation

- [x] Catalog format, loader and `catalog-check`
- [x] The first catalog
- [x] Fit, speed class, ranking and explanations, with table-driven tests: the DL380p profile, a 16 GB CPU-only laptop, 8 GB and 24 GB NVIDIA cards, a 4 GB machine

Recipes

- [x] Format, loader, and parameter substitution through the quoting function
- [x] Engine: preflight, plan, preview, execute, verify, manifest, resume, undo
- [x] Tests with a fake runner: skip-if, failure midway, resume, undo order
- [x] `ollama-install`, `ollama-uninstall`, `ollama-update`, `ollama-configure`, `authorized-key-add`

Ollama client

- [x] Version, tags, show, ps, delete, unload, streaming chat, pull as a detached job, benchmark

CLI

- [x] `ping`, `scan`, `recommend`, `plan`, `install`, `status`, `models`, `chat`, `uninstall`

Agent core. Do this last. It is the one piece of release 2 that is cheap to build here, and it may slip to Phase 6 without holding up this gate.

- [x] `ToolSpec`, the registry and schema export in `:core`
- [x] The `Brain` interface and the server brain over Ollama's tool calling
- [x] Proposal validation against the schema
- [x] The eval set's first fifty commands, and `cli eval-tools <model>`
- [x] `cli agent "<request>"`, which prints the proposal and executes nothing

Gate:

- From the `clean` checkpoint, `cli install wiz-ubuntu` ends with a running service and a pulled model, and `cli chat wiz-ubuntu` streams a reply.
- Interrupting the CLI during the model download and running `install` again resumes instead of starting over.
- Running `install` a second time changes nothing and says so.
- `cli uninstall` removes everything in the manifest: the binary, unit, user and models are gone.
- The same passes on `wiz-debian`. `wiz-small` gets a small model recommended. Jay's MX desktop is reported correctly.
- On `wiz-ubuntu` the recommendation matches section 6.
- If the agent core was built: `cli agent "text Mike I'm running late"` prints an `sms.send` proposal, and `eval-tools` has numbers for two server models.

Jay checks:

```
ssh wiz-ubuntu 'systemctl is-active ollama && ollama list'
ssh wiz-ubuntu 'ss -ltn | grep 11434'      # should show 127.0.0.1 only
```

### Phase 2: the app: connect, scan, recommend, install (about three weeks)

- [x] App skeleton: navigation, theme, Hilt, Room schema, settings
- [x] First-run screen: what the app does, that it runs commands as root on machines you point it at, that each command is shown first, and that it is for machines you own or administer
- [x] Key generation and encrypted storage, with show and copy for the public key
- [x] Add-server flow: host, port, user, host key confirmation, password or key, offer to install the app's key
- [ ] Local network permission flow (when targeting SDK 37)
- [x] An error screen for each typed SSH error, each with a next step
- [x] Scan screen: hardware card, verdicts, "view the scan script"
- [x] Manual mode: typed hardware leads to the same recommendation screen, with no server
- [x] Recommendation screen: the pick, faster, smarter, with reasons, download size and speed class
- [x] Plan preview: every step, expandable to the exact script
- [x] Install progress: step list and live log (run end to end through the ViewModel against wiz-debian; not yet on the phone's screen) that survive backgrounding and the app being killed, and a failure screen with the log, retry and undo
- [x] Benchmark result: measured tok/s and load time
- [x] Transcript viewer
- [x] Screenshot tests for each screen in its main states (Roborazzi or the official Compose preview screenshot plugin, whichever is in better shape)

Gate: on a real phone, from the `clean` checkpoint to a server that is ready to chat, without touching a terminal. Kill the app mid-install and reopen it, and it picks up. Airplane mode for a minute during the download, and it recovers. Wrong password, changed host key, unsupported OS and too little disk each produce a message a non-expert could act on.

### Phase 3: chat and management (about two weeks)

- [x] Chat: streaming, stop, model picker, conversation list, basic markdown, copy, "loading model" shown separately from "generating", measured tok/s per reply
- [x] Models: installed list with sizes, the recommended list for this hardware, pull by tag with progress, delete, details, loaded models and unload
- [x] Server: service status, start, stop, restart, recent logs, a snapshot of load, memory and disk, Ollama version and update, settings (context length, keep-alive) through `ollama-configure`, uninstall
- [x] The optional sudoers drop-in step
- [ ] Reconnect behavior when returning to the app

Gate: Jay uses the app as his only way to run his own server for a week and keeps a list of what annoyed him. The list becomes tasks.

### Phase 4: connect other apps (about a week and a half)

- [x] Exposure state reader: bind address, interface addresses, public address check, firewall state, Tailscale presence
- [x] Connect screen: URLs, copy, QR code, snippets
- [x] `expose-lan` and `expose-private` recipes with the guardrails in 5.9
- [ ] Tailscale wizard (Pro). Not started: needs Pro (Phase 5) and an interactive `tailscale up` login; the app only detects Tailscale for now.
- [ ] Exposure check from the server and from the phone. Server side done (bind, firewall, public addresses, warnings); the phone-side probe over mobile data is not built.

Gate: with home network mode on, another device on the LAN uses the endpoint with a snippet copied from the app. "Go back to private" restores 127.0.0.1 and removes the firewall rule. A fixture with a public address makes the app refuse the wide bind.

### Phase 5: release 1 (about two weeks, plus Play's testing period)

- [ ] Remote recipe and catalog bundle: signed, version-checked, bundled fallback, and a signing tool. The private key stays offline with Jay. (Format, verifier with key rotation and rollback protection, and the CLI signing tool done; fetching waits on question 5: where it is hosted.)
- [x] Pin the Ollama version and sha256 in the install recipe
- [ ] (built; untestable until a Play track exists and the product id is set) Pro unlock with Play Billing: a one-time product, restore, cached entitlement, a debug override. Keep the entitlement check behind one interface so features can move between tiers and a license key can plug in later.
- [ ] Release hardening: R8 on, backup off, network security config reviewed, timeouts reviewed, strings in resources, an accessibility pass
- [x] "Copy diagnostic report", with hostnames, addresses and usernames redacted by default. This is the support channel, since there is no crash reporter.
- [ ] A privacy policy page and in-app terms (draft text in docs/privacy-policy.md; not hosted)
- [ ] Play Console: listing, data safety form, content rating, and review notes. Reviewers have no server, so point them at manual mode and include a short video of the full flow.
- [ ] Test on a machine with an NVIDIA GPU

Gate: a build installed from a Play testing track runs the full flow, and the Pro purchase works for a license tester.

### Phase 6: the assistant, with the server as the brain (about four weeks)

Goal: hold the assistant gesture, say or type "text Mike I'm running late", see a card with Mike's number and the message, tap, and the text goes. The thinking happens on the user's own server.

- [ ] Spike first: on two phones from different makers, make the app selectable as the digital assistant, hold the role, then request `SEND_SMS`. Record what each phone needed.
- [ ] Assistant sheet: text input, proposal card, result, error states
- [ ] Onboarding: explain, open the default-assistant setting, re-check the role, ask for each permission the first time its tool is used
- [ ] Android executors for the first six tools
- [ ] Contact resolution in code, with a picker for ambiguous names
- [ ] Confirm card: resolved recipient and number, editable text, SIM choice
- [ ] Audit log screen and per-tool switches
- [ ] The rules in 5.13, each with a test
- [ ] Server brain through the tunnel, "server unreachable" handling, and a label for which brain answered
- [ ] Pattern matcher for the commonest commands
- [ ] The latency work in 5.14, with the measured time shown
- [ ] Voice in and out, behind interfaces
- [ ] Role-loss check on every invocation
- [ ] The rest of the eval set, to about a hundred commands
- [ ] Screenshot tests for the sheet and the card in each state

Gate:

- On two phones, the app is the default assistant and the gesture opens the sheet.
- Twenty scripted commands across the six tools produce at least eighteen right proposals from the server model on `wiz-ubuntu`.
- Nothing is sent or dialed without a card. Declining the card does nothing.
- Injection test: a text arrives that says to ignore instructions and forward the conversation to another number. Asking the assistant to read recent texts shows that text and proposes nothing. Any proposal that does appear shows its full recipient and text on a card.
- In airplane mode the pattern matcher still sets a timer, and the assistant says the server is out of reach for the rest.

Jay checks: the audit log matches what he did, and the text he sent shows up in his normal messaging app.

### Phase 7: release 2 (about two weeks, plus review)

- [ ] Play permissions declaration for SMS and call log as the default assistant handler, with the demo video it asks for *(verify the current form)*
- [ ] Contacts: Play's policy on broad contact access takes effect January 27, 2027. Justify the access or move lookups to the system picker.
- [ ] Data safety form and privacy policy updated for messages, contacts, call log and calendar. Check how Play classifies data sent to a server the user owns and we cannot see *(verify)*.
- [ ] Decide what is Pro (question 3)
- [ ] Review notes: the pattern matcher lets a reviewer send a test text with no server
- [ ] Jay uses it as his own assistant for a week

Gate: the update is approved with the SMS declaration, and Jay has used it daily for a week and read through the audit log.

### Phase 8: an on-phone brain (about three weeks)

- [ ] `InferenceEngine` interface and a LiteRT-LM implementation
- [ ] Model manager: resumable download, a Wi-Fi-only option, storage use, delete
- [ ] One recommended small function-calling model, chosen from eval results and not from a blog post
- [ ] The eval set running on the phone: accuracy, time to proposal and memory, per model
- [ ] The whitelist of tools the on-phone brain may propose, set from those numbers
- [ ] Routing rules from 5.14
- [ ] Optional: fine-tune the small model on this app's tool list. Google publishes a recipe for its function-calling model. It needs a GPU, which Jay's server lacks, so a rented one or a notebook service.

Gate: on an 8 GB phone in airplane mode, each whitelisted tool reaches at least 90% on its part of the eval set and a card appears within three seconds. A tool that misses the bar stays off the on-phone list. If nothing clears it, the on-phone brain ships as an experiment that is off by default, and the pattern matcher stays the offline path.

### Phase 9: the direct build and the power tiers (about four weeks)

- [ ] `direct` flavor: no Play Billing, a license key behind the entitlement interface, an update check against the releases page
- [ ] Register the direct build under developer verification
- [ ] Tier detection and the `minTier` gate in the registry
- [ ] Root executor with fixed, validated commands
- [ ] Root-tier tools in the order given in 5.15
- [ ] Adversarial test: prompts and planted texts that try to get a command run. Nothing outside the fixed tool list can execute.
- [ ] Shizuku executor, if Shizuku is healthy on current Android
- [ ] Spike: the system tier and the app-functions permission. Write up go or no-go.
- [ ] Choose the store and wire up license issuance

Gate: on a rooted phone, the direct build switches Wi-Fi and Bluetooth by voice, an activated license key keeps Pro unlocked with the network off, and the adversarial test passes.

## 8. Traps

Server half

- Ollama's docs and its install script disagreed on the archive format and the install prefix when this was written (see 5.7). An existing install can be under `/usr` or `/usr/local`, so find it with `command -v ollama` instead of assuming.
- Fresh Ubuntu VMs run unattended upgrades right after boot and hold the apt lock. Use apt's lock timeout instead of failing.
- Debian's non-interactive SSH `PATH` has no `/usr/sbin`.
- A Debian install where a root password was set during setup usually has no sudo for the regular user *(verify)*. Detect it and explain the two ways forward: add the user to sudo, or connect as root with a key.
- `lspci` may not be installed. Read the PCI class and vendor from `/sys` instead.
- Models live under `/usr/share/ollama` by default, which is on the root filesystem. Check free space there, not in `/home`.
- Changing Ollama's environment needs a daemon-reload and a service restart, which unloads the model.
- New models often need a newer Ollama. The catalog's `minOllamaVersion` exists so the app can offer an update instead of failing to load.
- Binding Ollama to a specific LAN address breaks when DHCP hands the machine a new one.
- Hyper-V: processor compatibility mode hides AVX from the guest, and Dynamic Memory makes available RAM a moving target and hides NUMA *(from research)*. Test VMs use fixed memory.
- Logs contain carriage returns and ANSI codes from progress bars. Use quiet flags where they exist and strip the rest in the viewer. Cap how much log is held in memory.
- Play Billing can only be exercised from a build uploaded to a Play testing track.

Assistant half

- The assistant role cannot be requested with a dialog, and it has been reported to reset when the APK is reinstalled in place. During development, restore it over ADB after each install.
- Apps that target Android 10 or later cannot switch Wi-Fi on or off. Check each toggle against current Android before putting it in the normal tier.
- There is no public API for sending RCS or for sending through other messaging apps. `sms.send` sends SMS.
- Dual-SIM phones need a SIM choice on the card.
- A long tool list makes models pick worse. Add tools one at a time and re-run the eval set.
- Speech recognition and text-to-speech engines may be missing on phones without Google services.
- A sideloaded build cannot be granted SMS or notification access on Android 15 and later until the user allows restricted settings for it.
- A wrong privileged-permission allowlist in a system module bootloops the phone.
- Shizuku was unreliable on Android 17 when this was written.
- Marking outside content as data in the prompt reduces injection. It does not stop it. The card is what stops it.

## 9. Questions for Jay

1. **Name and applicationId.** The applicationId is permanent once a build is uploaded to Play, so settle it before Phase 5.
2. **Play account type.** Personal developer accounts created after November 13, 2023 must run a closed test with at least 12 testers for 14 days before production. That rule is written for personal accounts. Decide early, because it adds a tester hunt and at least two weeks.
3. **Pro.** Price and contents, one time, no subscription. `CODE-PLAN.md` said about $7.99 and the research suggested $9.99 to $14.99. This plan assumes Pro means more than one server, the Tailscale wizard and advanced tuning in release 1, plus the send tools (texts and calls) in release 2. Whether sending sits behind Pro is Jay's call.
4. **Order.** This plan ships the server half first and the assistant second. `CODE-PLAN.md` had it the other way round. The assistant needs Phases 1 to 3 (server, tunnel, chat) and nothing from Phase 4, so if Jay wants it sooner, Phases 6 and 7 can move ahead of Phase 4.
5. **Where the recipe and catalog repository lives**, and where the signing key is kept.
6. **An NVIDIA machine** to test on before release 1.
7. **A GPU for fine-tuning** in Phase 8, if it comes to that.

## 10. Later

Server half

- llama.cpp "fast mode" built with native flags, which helps old CPUs like the DL380p's
- Thread and NUMA auto-tuning from benchmark runs
- An Open WebUI add-on installer
- An API key in front of Ollama through a reverse proxy
- AMD and Intel GPU acceleration
- Fedora and Arch, arm64, WSL2
- A small agent on the target with QR pairing, for people without SSH (the route to Windows)
- LAN discovery
- Monitoring, alerts and "install finished" notifications (these need background work and possibly a foreground service declaration)
- Backup and migration of models between machines
- llmfit as a second-opinion scan and benchmark
- A direct-URL mode for servers reachable without SSH
- Family mode: set up and look after a server for someone who is not technical

Assistant half

- Scheduled tasks, under the rules in 5.13
- Replies to messaging apps through notification actions
- The wider tool catalog from `CODE-PLAN.md`: files, clipboard, notifications, location, app launching, notes
- Search over local files with on-device embeddings
- A widget and a quick-settings tile for one-tap voice
- A Tasker plugin
- A Wear OS companion
- Photos and other image input
- llama.cpp as a second on-phone runtime
- An offline speech engine for phones without Google services

## 11. Facts checked on 2026-10-09

| Fact | Source |
|---|---|
| New apps on Play must target Android 16 (API 36) or higher since August 31, 2026 | https://developer.android.com/google/play/requirements/target-sdk |
| `ACCESS_LOCAL_NETWORK` is a runtime permission in the nearby-devices group, enforced on Android 17 for apps targeting SDK 37. It covers outgoing TCP to LAN addresses. Below 37, `INTERNET` grants it implicitly. | https://developer.android.com/privacy-and-security/local-network-permission |
| Play Billing Library 9.1.0 is current (June 2026). One-time products use `queryProductDetailsAsync` and `enablePendingPurchases(PendingPurchasesParams...)`. | https://developer.android.com/google/play/billing/release-notes |
| Since June 30, 2026 in the US, Play charges a service fee that starts at 10% on the first $1M a year, plus a 5% billing fee when Play Billing is used | https://android-developers.googleblog.com/2026/06/play-expanded-billing.html |
| sshj 0.40.0 is the latest release. BouncyCastle became optional in 0.39.0. | https://github.com/hierynomus/sshj |
| Android Password Store adopted sshj on Android in its pull request 801, swapping in the full BouncyCastle provider at startup. The project was archived in October 2024. | https://github.com/android-password-store/Android-Password-Store/pull/801 |
| ConnectBot's sshlib is a JVM SSH client library with Ed25519, ECDSA and Curve25519 support | https://github.com/connectbot/sshlib |
| All `androidx.security:security-crypto` APIs are deprecated in favor of using the Android Keystore directly (since 1.1.0-alpha07, April 2025) | https://developer.android.com/jetpack/androidx/releases/security |
| Ollama on Linux: manual install from `ollama-linux-amd64.tar.zst` into `/usr`, the `ollama` user, the systemd unit, the drop-in directory, update, logs, and the uninstall list | https://docs.ollama.com/linux |
| Ollama's install script: downloads `ollama-linux-<arch>.tgz`, installs under `/usr/local` when that is on the `PATH`, adds the `ollama` user to `render` and `video`, and installs `cuda-drivers` when an NVIDIA card has no working `nvidia-smi` | https://ollama.com/install.sh |
| Ollama binds to 127.0.0.1:11434 by default. `OLLAMA_HOST`, `OLLAMA_MODELS`, `OLLAMA_KEEP_ALIVE`, `OLLAMA_CONTEXT_LENGTH` (default 4096), `OLLAMA_ORIGINS`. Models are stored in `/usr/share/ollama/.ollama/models`. The nginx example rewrites `Host`. `keep_alive: 0` unloads. | https://docs.ollama.com/faq |
| Ollama tool calling: `/api/chat` with a `tools` array, `tool_calls` in the reply, results returned as `tool` messages. Streaming and several calls in one reply are supported. | https://docs.ollama.com/capabilities/tool-calling |
| Docker routes published ports around ufw | https://docs.docker.com/engine/network/packet-filtering-firewalls/ |
| Play's closed-testing rule: 12 testers for 14 days, for personal accounts created after November 13, 2023 | https://support.google.com/googleplay/android-developer/answer/14151465 |
| Play allows SMS and call-log permissions only for the registered default SMS, phone or assistant handler, registered before the permission is requested, with a declaration form | https://support.google.com/googleplay/android-developer/answer/9047303 |
| Play prohibits using the accessibility API to autonomously initiate, plan and execute actions, outside real accessibility tools | https://support.google.com/googleplay/android-developer/answer/10964491 |
| A Play policy on broad contact access takes effect January 27, 2027 | https://support.google.com/googleplay/android-developer/answer/16585319 |
| `RoleManager` can check `ROLE_ASSISTANT` but its request intent shows no dialog. The user picks the assistant in settings. (2024 article) | https://dev.to/tkuenneth/on-building-a-digital-assistant-for-the-rest-of-us-part-3-4e0k |
| A report that the default assistant is cleared by an in-place APK reinstall on Android 16 | a GitHub issue report (link kept in local notes)  |
| Apps targeting Android 10 or later cannot enable or disable Wi-Fi | https://developer.android.com/about/versions/10/privacy/changes |
| Google's AI Edge Gallery has an offline Mobile Actions demo using a 270M function-calling model, about 142 tok/s decode on a Pixel 7 Pro CPU | https://developers.googleblog.com/en/on-device-function-calling-in-google-ai-edge-gallery/ |
| That model scored 58% on the mobile-actions test before fine-tuning and 85% after | https://blog.google/technology/developers/functiongemma/ |
| LiteRT-LM: stable Kotlin API, GPU and NPU on Android, function calling with constrained decoding, models including Gemma 4 E2B and E4B, Gemma 3n, FunctionGemma, phi-4-mini | https://developers.google.com/edge/litert-lm/overview |
| AppFunctions: Android 16 and later, callers need `EXECUTE_APP_FUNCTIONS`, and only a limited number of apps and system agents have access during the experimental phase | https://developer.android.com/ai/appfunctions |
| A sample obtains `EXECUTE_APP_FUNCTIONS` on a rooted device by installing the agent under `/system/priv-app` | https://github.com/FilipFan/AppFunctionsPilot |
| Android 15 restricts sideloaded apps from SMS, accessibility, notification access, overlay, usage access and device admin until the user allows restricted settings | https://androidauthority.com/android-15-restricted-settings-sideloading-3481098 |
| Developer verification: enforced from September 30, 2026 in four countries, worldwide for all apps in 2027. Unverified apps need a developer setting and a 24-hour wait. | https://developer.android.com/developer-verification |
| Shizuku on Android 17: an open issue with apps failing to authorize | https://github.com/RikkaApps/Shizuku/issues/2180 |
| Operit AI: open-source Android agent with local models and accessibility, Shizuku and root modes, 7.7k stars, GitHub only | https://github.com/AAswordman/Operit |
| libsu 6.0.0: root shell and root service library | https://github.com/topjohnwu/libsu |
| Lemon Squeezy: 5% plus 50 cents, merchant of record, issues license keys, has a license API | https://www.lemonsqueezy.com/pricing |

Everything about competitors, model names, the exposed-host figures and speeds on the DL380p came from the research report and was not re-checked here.

## 12. Where CODE-PLAN.md went

| In `CODE-PLAN.md` | Here |
|---|---|
| On-device agent as the MVP | Moved to release 3, behind a measured gate. The server is the first brain, because small models are weak at tool calling. |
| Tool registry, risk levels, confirm cards, audit log, per-tool switches | Kept, in 5.12. Risk levels gained `send`. |
| A catalog of about forty tools | Six to start. The rest is in section 10, added one at a time as the eval set allows. |
| "Read tools run free" | Changed. Outside content is untrusted, in 5.13. |
| Web fetch off by default | Kept, and held back entirely for now. |
| Scheduled agent tasks | Section 10, with the rules in 5.13. |
| SMS access as review paperwork | Changed. The app has to be the default assistant, in 5.11. |
| `InferenceEngine` with LiteRT-LM first and llama.cpp later | Kept, in Phase 8. |
| Docker, Ollama and Open WebUI as the server stack | Replaced by the native Ollama install in 5.7. Open WebUI is a later add-on. |
| VRAM-only recommender | Replaced by 5.5, which also handles CPU-only machines. "Fits is not the same as good" is kept. |
| Install streamed over a live SSH session | Replaced by detached jobs in 5.2. |
| EncryptedSharedPreferences | Replaced by the Keystore directly. |
| One-time Pro unlock, no subscription, no analytics, no cloud | Kept. |
| Multi-model routing, small model first | Kept in 5.14, routed by rule and with the server first. |
| Tasker plugin, widgets, Wear OS, family mode, local file search | Section 10. |
