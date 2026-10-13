# Decisions

The running log of choices made along the way: what was chosen, why, and what was rejected.
Newest at the bottom. Entries marked **(Jay: please confirm)** were made during the overnight
autonomous run of 2026-10-09/10 without a chance to ask, and are easy to reverse.

## 2026-10-09: Versions checked (rule 8)

Looked up on Maven Central, Google Maven and GitHub on 2026-10-09:

| Thing | Version | Note |
|---|---|---|
| sshj | 0.41.1 (2026-09-21) | Newer than the plan's 0.40.0. Pulls bcprov/bcpkix 1.84 and asn-one at runtime. |
| BouncyCastle | 1.86 | Declared directly so the version is ours, not sshj's transitive one. |
| Apache MINA SSHD (tests only) | 2.20.0 | 3.0.0 is still milestone builds. |
| OkHttp / mockwebserver3 | 5.5.0 | |
| Kotlin | 2.4.20 | 2.4.21 exists; 2.4.20 is what the other projects on this machine build with. |
| kotlinx.coroutines / serialization | 1.11.0 / 1.11.0 | |
| JUnit | 6.1.3 | |
| AGP / Gradle | 9.4.1 / 9.8.0 | Same pair SpeedEQ ships with on this machine. |
| Compose BOM | 2026.09.00 | |
| Ollama | v0.40.2 (2026-10-08) | `ollama-linux-amd64.tar.zst`, 1.44 GB, GitHub publishes its sha256. |

## 2026-10-09: Ollama install layout (PLAN.md 5.7 open questions)

Read the live `https://ollama.com/install.sh` and `https://docs.ollama.com/linux`.

- **Archive format: `.tar.zst`.** The script now tries `.tar.zst` first and only falls back to
  `.tgz` for old versions; the docs use `.tar.zst`. So `zstd` is a prerequisite and the
  `prereqs` step installs it when missing.
- **Prefix: `/usr/local`**, as the plan said. The script picks the first of `/usr/local/bin`,
  `/usr/bin`, `/bin` on `PATH` (normally `/usr/local`); the docs say `/usr`. Machines we set up
  look like script-installed ones. An existing install is adopted wherever it is.
- **Unit:** same as the script/docs (`User=ollama`, `Restart=always`, `RestartSec=3`), with a
  fixed `PATH` instead of the installer's `$PATH`, and `WantedBy=multi-user.target` (docs) rather
  than `default.target` (script).
- **Download URL:** `https://github.com/ollama/ollama/releases/latest/download/…` is resolved
  once on the target (`curl -w '%{redirect_url}'`) to a versioned URL, so a resumed download
  can never mix two versions. The version and sha256 are recorded in the manifest. Pinning
  version + sha256 in the recipe waits for signed remote recipes (Phase 5), as planned; the
  recipe already accepts `ollamaVersion` and `ollamaSha256` and refuses a mismatch.
- The app never contacts GitHub itself; only the target does (fence: no other network traffic
  from the phone).

## 2026-10-09: Test targets are LXD containers for now **(Jay: please confirm)**

The wiz-* Hyper-V VMs did not exist yet, and the work was meant to go on unattended. This dev
VM already had the LXD snap installed (and the user in the `lxd` group), so I ran `lxd init --auto`
and launched two system containers:

- `wiz-ubuntu`: Ubuntu 24.04, user `wiz`, sudo **asks for a password**.
- `wiz-debian`: Debian 12, user `wiz`, **passwordless** sudo, no curl preinstalled.

Each has a `clean` snapshot (`lxc restore wiz-ubuntu clean`). `tools/lxd-targets.sh` rebuilds
them. They run systemd and sshd, so the whole install/uninstall flow is exercised for real.
`lxd init --auto` created a `default` dir storage pool and an `lxdbr0` bridge (10.138.76.0/24, NAT).
To undo all of it: `lxc delete -f wiz-ubuntu wiz-debian`, then `lxc network delete lxdbr0` and
`lxc storage delete default`.

What containers cannot stand in for: they share the host kernel, see the host's CPU and memory
(so the "fixed 64 GB" and "8 GB" VM shapes are not reproduced), and Hyper-V-specific behaviour
(compatibility mode) is only covered by fixtures. The real VMs are still wanted for the gate.

The dev VM itself turned out to be a Hyper-V guest on the DL380p (2x E5-2680 v2, AVX without
AVX2, 2 NUMA nodes, 122 GB), so its scan is the first real DL380p fixture.

## 2026-10-09: SSH library: sshj 0.41.1, behind `SshTransport`

Works from the JVM against the embedded MINA server and the LXD targets (Spike A). The Android
side (Spike B) is recorded separately below. sshj's blocking reads are wrapped so cancellation
and timeouts close the channel (`closingOnCancel`), which is the only thing that unblocks them.

Host keys: trust on first use with the SHA256 fingerprint computed exactly as OpenSSH does
(checked against `ssh-keygen -lf` on the target). The stored key type is fed back to sshj's
`findExistingAlgorithms`, so a server offering several key types cannot make the app compare a
different one and raise a false "key changed".

## 2026-10-09: App key format

Ed25519, generated with BouncyCastle's lightweight API (no JCA provider involved, so it does not
depend on what Android registers), written in OpenSSH's `openssh-key-v1` format so sshj, `ssh -i`
and `ssh-keygen` all read it. Tested: the generated key logs in to the embedded server.

## 2026-10-09: How scripts reach the target

- Read-only probes (scan, `skipIf`, `verify`, `facts`, preflight checks) go to `/bin/sh -s` on
  stdin with the standard preamble. Nothing is written to the target for them.
- Anything that changes the machine is a file in the job directory, run as `/bin/sh <file>`,
  or `sudo -k -S -p '' /bin/sh <file>` with the password as the only thing on stdin. `-k`
  forces sudo to read the password every time, so a cached credential can never leave the
  password sitting on the script's stdin. The job wrapper also runs each step with
  `< /dev/null`.
- The command line sent to the login shell is always a fixed string plus quoted absolute
  paths, so bash, dash, zsh and fish all handle it.
- Detached steps: `setsid nohup /bin/sh run-NN.sh &`, wrapper writes `step-NN.pid` and,
  atomically, `step-NN.exit`. Status = exit file, else `/proc/<pid>` alive, else "lost".
  Tested: kill the CLI mid-download, run `install` again, it attaches to the running step.

## 2026-10-09: Parameter values in scripts

Recipes never contain `{{placeholders}}` inside scripts. The engine prepends
`P_<name>='<value>'` lines built by `Shell.quote` after the type validator accepted the value,
and scripts use `"$P_name"`. That keeps rule 2's injection boundary to one function, and the
preview shows the values plainly. A unit test runs `sh -n` over every script in every bundled
recipe and checks that none uses a placeholder.

## 2026-10-09: Manifest and uninstall

`~/.local/state/llmwizard/installed.json` on the target lists what the app created, recorded
only when the step that creates it actually ran (a skipped step means it was already there, so
it is not ours). Uninstall steps declare `requires: created:<kind>:<name>` and only appear in the
plan when the manifest has that entry. Verified on wiz-debian: install, uninstall
`--delete-models`, then no binary, libraries, unit, user, group or model directory remain.
An Ollama the user installed (no manifest entries) gets only the cache cleanup.

## 2026-10-09: Catalog contents and speed constants

21 entries, every tag, size, parameter count and quantization fetched from the registry
manifests and config blobs on 2026-10-09 (`catalog/catalog.json` says `checked`). `quality` is
an editorial 1-5 judgement. Constants (k = 0.5 for CPUs, 0.35 for GPUs, the bandwidth table,
the class boundaries) are starting points from the plan and research; per-core caps were added
so a 4-vCPU VM is not credited with the whole host's bandwidth.

First calibration point, measured on this hardware (DL380p host, LXD container): qwen3:0.6b
estimated 38-48 tok/s, measured 41-45 tok/s. More pairs are kept in
`~/.config/llmwizard/benchmarks.jsonl` by the CLI.

## 2026-10-09: applicationId **(Jay: please confirm)**

`dev.dreamz.llmwizard`, following the standing rule that every app uses `dev.dreamz.<app>`.
PLAN.md question 1 is still open: the name, and therefore the final applicationId, is Jay's call
before the first Play upload.

## 2026-10-09: Spike B passed: sshj works on Android in a minified release build

On boston (moto g stylus 5G 2024, Android 15 / API 35), `app-release.apk` (R8 on, shrinkResources on,
debug-signed) generated an Ed25519 key, connected with the test password, ran the
`authorized-key-add` recipe, reconnected with its own key and ran `uname -a` (logcat tag `Spike`,
output `SPIKE OK`). The target was wiz-debian, reached through `adb reverse tcp:2222` and an LXD
proxy listening on 127.0.0.1 only, so nothing was exposed on any network.

What it took:
- Replace Android's cut-down "BC" provider with the full `BouncyCastleProvider` at app start
  (`LlmWizardApp.installBouncyCastle`), as Android Password Store did.
- R8 keep rules for `net.schmizz.**`, `com.hierynomus.**`, `org.bouncycastle.**`, plus
  `-dontwarn` for JVM-only classes sshj references but never uses on Android
  (`javax.security.auth.login`, GSSAPI, slf4j bindings).
- One real bug found and fixed in `:core`: part of `exec` ran on the caller's thread, which is the
  main thread on Android (NetworkOnMainThreadException). The whole exec body now runs on the IO
  dispatcher.

Not yet measured: APK size impact of keeping all of BouncyCastle (release APK is 9.4 MB). The keep
rule can be narrowed later by testing which classes the provider needs.

**Decision:** keep sshj. No need for the ConnectBot sshlib or JSch fallbacks.

## 2026-10-10: First eval numbers (measured on the DL380p, LXD container, CPU only)

`cli eval-tools` on the first 50 commands, eval clock fixed at 2026-10-09 10:00:

| Model | Right | Median time per command | Notes |
|---|---|---|---|
| qwen3:0.6b | 19/50 (38%) | 1.4 s | Often answers in words or writes the call as text; clock_set 0/12 |
| qwen3.6:35b-a3b | 45/50 (90%) | 28.7 s (p90 36.8 s) | All 5 misses: relational names ("mom", "dad", "the dentist") where it called contacts_find first |

Recorded as `toolAccuracy` in the catalog. Speed of the same MoE: 8.5 tok/s generation (estimate was 10-13, within the plan's 5-10 prediction). The 28 s per command is mostly prompt processing of the system prompt and tool list on a CPU; PLAN.md 5.14's latency work (prefix reuse, keep_alive, shorter tool descriptions) is where this needs to go before release 2.

Tool names use underscores (`sms_send`) rather than the plan's dots (`sms.send`): some chat templates and small models handle dotted function names badly. **(Jay: please confirm)**

Small models sometimes write a tool call as text instead of using Ollama's `tool_calls`. `TextToolCall` accepts exactly one `{"name", "arguments"}` JSON object from the text; it still goes through the validator and, for sends, the card.

## 2026-10-10: Android regex and main-thread findings

Two bugs only showed up on the phone: `:core` called the transcript/resume stores on the caller's
thread (Room forbids the main thread), and a regex with an unescaped `}}` that the JVM accepts but
Android's ICU engine rejects. Both fixed in `:core`. Lesson: anything in `:core` that touches a store
hops to the IO dispatcher itself, and new regexes escape braces.

## 2026-10-10: Exposure (Phase 4)

- Exposure is read without sudo: interfaces from the scan, ufw state from `/etc/ufw/ufw.conf`
  (`ENABLED=yes`), the bind from llmwizard's settings. Listing ufw's rules would need root and is
  not needed to decide anything.
- Addresses are classed loopback / private (RFC 1918) / CGNAT (100.64/10, which is Tailscale's
  range) / link-local / public. "Every network" (0.0.0.0) is refused when any interface has a
  public address; CGNAT addresses are not offered as LAN binds.
- With ufw active, `expose-lan` adds `ufw allow from <that network> to any port 11434 proto tcp
  comment llmwizard`; `expose-private` and uninstall delete it. Without a firewall nothing is
  enabled (fence) and the screen says plainly that anything on the network can connect.
- Verified on both containers: before sharing the port is closed from this VM; after sharing this
  VM lists models over `/v1/models`; after "go back to private" it is closed again and the ufw
  rule is gone.
- Snippets for other apps are data (`catalog/snippets.json`), display only, never run anywhere.
- QR codes: zxing core 3.5.4 (no runtime dependencies, no network code).
- Not built: the Tailscale wizard (needs Pro and an interactive login) and the phone-side check
  over mobile data (it needs the server's public address, which only a third-party "what is my
  IP" service would give; that conflicts with the no-other-traffic fence, so it needs Jay's call).

## 2026-10-13: BUILD_PLAN expansion (E1, E2.1-2.3/2.5, E5.1) — first slice

Jay's `docs/BUILD_PLAN.md` adds five expansion phases. This slice is the plan's own steps 1–3
(catalog + schema + article schema, filter + explanations, model config + benchmark). Training
(E3) and agents (E4) are not started; see "Needs Jay" below.

**Catalog (E1.1).** Every tag in the plan was resolved on registry.ollama.ai (manifest, config
blob, template, licence) on 2026-10-13. 10 of the plan's 17 tags do not exist as written. They were
corrected where a real tag exists:
`nous-hermes2:34b-yi-q4_K_M`, `nous-hermes2-mixtral:8x7b-dpo-q4_K_M` (not `nous-hermes-2-mixtral`),
`openhermes:7b-mistral-v2.5-q4_K_M`, `llama3-chatqa:8b-v1.5-q4_K_M`,
`mistral-nemo:12b-instruct-2407-q4_K_M`, `deepseek-coder-v2:16b-lite-instruct-q4_K_M`,
`qwen2.5-coder:32b-instruct-q4_K_M`, `command-r:35b-08-2024-q4_K_M`,
`starcoder2:15b-instruct-v0.1-q4_K_M` (the plain tag is a base completion model).
`dolphin3:24b` does not exist (Ollama has only `dolphin3:8b`), so `dolphin3:8b-llama3.1-q4_K_M` is used.
- Dropped: `samantha-mistral` (a companion persona, not uncensored, outclassed) and `yi:34b-chat`
  (same Yi 34B base as Nous Hermes 2 34B, which is the better tune).
- Added beyond the plan: `dolphin-mixtral:8x7b` and six abliterated community builds of current
  models (`huihui_ai/qwen3.5-abliterated` 4B/9B/35B-A3B, `qwen3-abliterated` 30B-A3B,
  `qwen3-coder-abliterated` 30B-A3B, `gpt-oss-abliterated` 20B). The plan's list is mostly
  2023–24 dense models; on a CPU-only box the MoE abliterated ones are both smarter and faster.
- `uncensored: true` only for models that really are (Dolphin, Wizard Vicuna Uncensored,
  abliterated). The plan marked Nous Hermes, OpenHermes, ChatQA, Yi, Mistral Nemo and Command R as
  uncensored-adjacent; they are lightly aligned and are tagged `chat`/`research` instead.
- `supportsTools` comes from the registry, not the plan: true only when the template has `.Tools`
  or the config names a built-in renderer/parser (the huihui qwen3.5 uploads carry Ollama's
  `qwen3.5` renderer). That makes Nous Hermes 2 34B and the Mixtral tunes false, though the plan
  said true. `toolAccuracy` stays null until the E1.7 eval run.
- Fixes to the plan's metadata: DeepSeek Coder V2 Lite is MoE (2.4B active), Codestral is
  non-production licensed, Command R is CC-BY-NC. These show on the card.
- `recommendedContext` is set where a model's trained window is below the app's 8K default
  (Wizard Vicuna 2K). It also caps the KV estimate. Llama 3 entries carry 8K for the advisor.

**Uncensored is opt-in.** `ModelFilter.DEFAULT` leaves uncensored models out, so the plain setup
flow recommends exactly what it did before (a test checks this). `Recommendation.candidates` is the
filtered list. The new `allCandidates` keeps every model, so an installed uncensored model still
gets its estimate.

**Speed reality vs. the plan's section 12.** The plan expects ~19 tok/s for a dense 7B and ~2 tok/s
for 70B on the DL380p. The calibrated model, built from measurements on that box (dense 27B at
1.2 tok/s), predicts ~3–4 tok/s for a dense 7B and ~0.3–0.4 for a dense 70B. Not yet measured
directly: benchmark `dolphin-mistral` on wiz-ubuntu to settle it.

**Model configuration (E2.1/E2.2) goes through `/api/create`, not a Modelfile recipe.** The plan's
recipe writes a temp Modelfile and runs `ollama create` in a shell. Ollama's API takes
`from`/`system`/`parameters` as JSON, so the free-text system prompt never passes through a shell
(rule 2), no temp file or sudo is needed, and it matches how delete already works (API). The
Modelfile form is still generated, for the preview. Undo = delete the custom tag.

**Benchmark (E2.5)** already existed. Added `Benchmark.compare` (measured vs. estimated, with error).

**Knowledge base (E5.1).** Articles are Markdown files with a `---` header in `knowledge/` at the
repo root, bundled like the catalog, listed in `index.txt`. Four seed articles, written from the
DL380p measurements. The other ~26 (E5.2) are content work still to do.

**Needs Jay before E3/E4:**
1. **Open Interpreter with auto-run (E4.2/E4.4) breaks SPEC rule 10** ("the model never writes a
   shell command"). The example profile sets `autoRun: true`. Options: (a) Open Interpreter only
   with `auto_run = False`, so every command waits for approval in its terminal, shown on the phone;
   (b) rule 10 covers the phone assistant only, and server-side agents are a separate,
   clearly labelled tier; (c) Aider only.
2. **Fence: "runtimes other than Ollama".** The training stack (PyTorch, llama.cpp build), Aider
   and Open Interpreter are pip/venv installs on the target. Is that inside or outside the fence?
3. **E3 facts that need correcting before building:** Unsloth needs a GPU (no CPU path);
   bitsandbytes 4-bit QLoRA on CPU is experimental at best, so CPU training should be plain LoRA
   in bf16/fp32. llama.cpp's converter is `convert_hf_to_gguf.py` (not `convert.py`), and
   `convert_lora_to_gguf.py` lets Ollama load the adapter with `ADAPTER` without merging.
   Fine-tuning also needs the Hugging Face weights, so every trainable catalog entry needs an
   `hfRepo`. Ollama's GGUF cannot be trained.
4. Used RTX 3090 prices are about $700–900 (2026), not $400.

## 2026-10-13: Jay's answers, and E3 (fine-tuning) / E4 (agents) as built

**Jay's answers** to the "Needs Jay" questions above:
1. Agents: the user chooses, per profile, between **approve each command** and **auto-run
   unattended**. Built as `ApprovalMode`, default `APPROVE_EACH`. Auto-run maps to Aider
   `--yes-always` and Open Interpreter `--auto_run`, and it is shown in red wherever the profile
   appears, in the start preview and in the launcher's header comment. SPEC rule 10 still covers
   the phone assistant. Server-side agents are a separate tier the user configures.
2. The fence on "runtimes other than Ollama" does not stop the tools from installing what they
   need. Recipes may apt-install prerequisites (python3-venv, tmux, git, libgomp1) and pip-install
   the tools.

**Where things live:** `~/.local/share/llmwizard/` on the target (not `/opt/llmwizard` as the plan
said), next to the existing `~/.local/state/llmwizard`. Only the apt step needs sudo; everything
else runs as the login user.

**Python:** uv (pinned 0.13.0, installed into its own venv) fetches its own CPython. Aider 0.86.2
needs Python < 3.13, but Debian 13 and Ubuntu 26.04 ship newer. Aider gets 3.12, Open Interpreter
3.11, and training 3.12.

**Open Interpreter 0.4.3** (last release 2024-10) imports `pkg_resources`, which setuptools 80+
no longer has, so the recipe pins `setuptools<80`. The verify step runs `interpreter --version`,
because checking the package metadata had passed on a broken install.

**Agents in tmux:** `AgentSessions` starts `llmw-<tool>-<id>` sessions running a launcher file. The
launcher holds only fixed text plus `Shell.quote`d values, and the preview shows it. The pane is
piped to `log.txt` so a returning phone can catch up. Input goes in with `send-keys -l`, which is
literal. Privacy: Aider `--analytics-disable --no-check-update`, LiteLLM's local cost map, and Open
Interpreter `--offline --disable_telemetry`. The tests drive real tmux through the embedded SSH
server with a fake agent.

**Fine-tuning, what changed from the plan after running it for real** (wiz-debian LXD, qwen3:0.6b,
16-sample dataset, 8 epochs):
- **TRL dropped.** TRL 1.15's SFTTrainer always uses a Triton "fused LM head", and Triton is not in
  the CPU build of PyTorch (`'NoneType' object has no attribute 'apply'`). Training now uses plain
  Transformers `Trainer` + PEFT LoRA, with our own tokenization that masks the prompt (loss on the
  final assistant reply only).
- **No separate adapters.** Ollama 0.40.2 answers "LoRA adapters are no longer supported". The
  adapter is merged into the weights.
- **No quantize-on-import on Linux.** Ollama refuses to quantize GGUF, and its safetensors import
  needs Apple's MLX runtime ("MLX runtime is not available"). So llama.cpp does it: the
  `convert_hf_to_gguf.py` script at tag b11306, then `llama-quantize` from the prebuilt
  `llama-b11306-bin-ubuntu-x64.tar.gz` (sha256 pinned). The prebuilt binary needs `libgomp1`.
  Nothing is compiled. The quantize options are q4_K_M (default), q5_K_M, q6_K, q8_0 and f16.
- **QLoRA refused** on CPU servers (bitsandbytes 4-bit needs a GPU). LoRA in fp32 instead.
- **Result:** the base model doesn't know the trained fact. The fine-tuned
  `qwen3-bigiron:q4` (396 MB) answers "Jay's server is called Bigiron…" to a paraphrased question.
- **Estimator calibrated:** 24–25 trained tokens/s measured at 20 threads against 50 predicted, so
  efficiency went from 0.25 to 0.125.
- **The job script is fixed** (`core/src/main/resources/llmwizard/training/train.py`) and reads
  only `config.json`, so nothing the user types becomes code. It writes `progress.json` after every
  step, checkpoints every `saveSteps`, resumes from the newest checkpoint, and stops cleanly (saving
  a checkpoint) when a `cancel` file appears. Every stage is safe to re-run.
- **Trainable models** are catalog entries with an ungated `hfRepo` (Qwen3 0.6B/14B, Dolphin 3/2.8/
  2.9, OpenHermes, Mistral Nemo). The base must also be installed in Ollama under the same tag, for
  the chat template.

**App:** a per-server tools hub (Models / Train / Agents / Learn in a bottom bar), opened from the
server's "Ready" card. Learn is also on the home screen's top bar. The plan's app-wide six-tab
layout was not used, because everything except Learn belongs to one server. Chat and the server
console stay where they were.
