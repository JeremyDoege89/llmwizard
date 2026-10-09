# Overnight run, 2026-10-09 → 10

Jay asked for as much progress as possible while he slept, without waiting for review. This is
what got done, what was checked and how, and what is still unproven. Decisions are in
`DECISIONS.md`; anything marked **(Jay: please confirm)** there was my call and is easy to change.

## Where things stand

| Phase | State |
|---|---|
| 0 | Done. Both spikes pass. MX desktop fixture still missing (needs Jay's desktop). |
| 1 | Done against LXD stand-ins for the VMs. Gate items checked below. |
| 2 | Most screens built and device-tested up to the plan preview; a full install from the phone is not yet done (see "Not proven"). |
| 3–9 | Not started. |

## Check it yourself (commands)

```
cd ~/llmwizard
export JAVA_HOME=/home/dreamz/AAMedia/toolchain/jdk
./gradlew :core:test                         # 68 tests, all pass
./gradlew :cli:installDist
B=cli/build/install/llmwizard/bin/llmwizard
$B ping wiz-ubuntu                           # compare with: lxc exec wiz-ubuntu -- ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub
$B recommend fixture:dl380p-hyperv-ubuntu2404
lxc restore wiz-debian clean                 # fresh target
$B plan wiz-debian --model qwen3:0.6b --show-scripts
$B install wiz-debian --model qwen3:0.6b --yes      # Ctrl-C during "Download Ollama", run again: it picks up
$B install wiz-debian --model qwen3:0.6b --yes      # second time: "Nothing to do"
lxc exec wiz-debian -- ss -ltn | grep 11434  # 127.0.0.1 only
$B chat wiz-debian
$B agent wiz-ubuntu "text Mike I'm running late" --model qwen3.6:35b-a3b
$B uninstall wiz-debian --delete-models
./gradlew :app:recordRoborazziDebug          # PNGs in app/build/outputs/roborazzi/
./gradlew :app:installDebug                  # then add a server in the app
```

The test targets are LXD containers on this VM (`wiz-ubuntu`: sudo asks for `test-password`;
`wiz-debian`: passwordless sudo). Details and how to delete them are in DECISIONS.md.

## Phase 1 gate, item by item

- Install from clean ends with a running service and a pulled model, and chat streams a reply:
  **yes**, on wiz-debian and wiz-ubuntu (password sudo), via the CLI.
- Interrupting during the download and running install again resumes: **yes** (killed the CLI
  at 45 s; the second run printed "picking up the step already running").
- Second install changes nothing and says so: **yes** ("Nothing to do").
- Uninstall removes everything in the manifest: **yes** on wiz-debian (binary, libs, unit,
  user, group, models gone).
- wiz-debian passes; wiz-small gets a small model (fixture test); MX desktop reported correctly
  (synthetic fixture only, **not on the real desktop**).
- Recommendation on wiz-ubuntu matches section 6: **yes for a 64 GB VM** (test); the container
  sees all 128 GB so it picks gpt-oss-120b and offers the 3B-active MoE as faster.
  Measured on this hardware: qwen3.6:35b-a3b **8.5 tok/s** (section 6 predicted 5–10).
- Agent core: `cli agent "text Mike I'm running late"` gives an `sms_send` proposal;
  eval numbers for two models: qwen3.6:35b-a3b **45/50**, qwen3:0.6b **19/50**.

## Phase 0 Spike B

Passed on boston in a **minified release build**: generated an Ed25519 key, installed it with
the password once, reconnected with the key, ran `uname -a`. Reached wiz-debian over
`adb reverse` + an LXD proxy on 127.0.0.1 only (exposing it on the tailnet was blocked, rightly).

## Phase 2 on the phone (debug build, boston)

Walked through: first-run screen → add server (127.0.0.1:2222 via adb reverse) → host key dialog
(fingerprint matched the container's) → scan → recommendation → "add this phone's key" (the key
landed in authorized_keys) → full model list → install plan. Two Android-only bugs found and
fixed on the way (see DECISIONS.md). I stopped driving the phone at about 10:17 when it rotated to
landscape, since it looked like it was being picked up.

## Not proven yet

- A complete install **from the app** (Run → live log → benchmark). Every piece runs through the
  same `:core` code the CLI proved, but the screen flow itself has not been run end to end.
- App killed mid-install and reopened ("Pick it up" banner), airplane mode mid-download.
- The real Hyper-V VMs, the MX desktop, any NVIDIA machine.
- `ACCESS_LOCAL_NETWORK` flow: the app targets SDK 36 for now, so it is not needed yet.
- Release build of the full app (only the spike was built minified).

## Things to look at first

1. `DECISIONS.md` entries marked "please confirm": LXD stand-ins, applicationId
   `dev.dreamz.llmwizard`, underscore tool names.
2. The 28 s per assistant command on the DL380p (Phase 6 latency work).
3. The phone has the debug app installed (`dev.dreamz.llmwizard`) and an `adb reverse tcp:2222`.
   Uninstall with `adb uninstall dev.dreamz.llmwizard` if you want it gone.
4. Nothing is pushed to GitHub. The repo is local at `~/llmwizard` (commits authored as you).

## Continued (2026-10-10, daytime)

- **In-app install proven end to end** without the phone's screen: `InstallFlowTest` drives the
  real `SetupViewModel`, Room and SSH against wiz-debian (password login, host key trust, scan,
  choose qwen3:0.6b, install, benchmark: 47.1 tok/s measured vs 32–41 estimated). Run it with
  `lxc restore wiz-debian clean && ./gradlew :app:testDebugUnitTest -Ptarget=wiz-debian`.
  It also resumed a half-finished install left by an earlier run.
- **Phase 3 built**: chat (streaming, stop, model picker, conversations in Room, "loading model"
  shown separately, measured tok/s per reply, copy on long-press, light markdown), server screen
  (status, start/stop/restart, recent log, settings, update, models with pull/unload/delete,
  uninstall with the model list, the optional visudo-checked sudo rule).
  `ManageFlowTest` runs chat → status → restart → settings → sudo rule → restart without
  password → uninstall against wiz-debian, all passing, password never in the transcript.
- Fixed: chat and server screens no longer ask for the password when a session is already live.
- Still not done on the phone's actual screen: the install, chat and manage flows (the phone was
  in use). Phase 3's gate is Jay using the app for a week.

## Continued: Phases 4 and 5 (2026-10-10)

- **Phase 4 (share with other apps):** guardrails (public address refuses 0.0.0.0, CGNAT not
  offered), `expose-lan` / `expose-private` with a network-scoped ufw rule when a firewall is on,
  connect section with URLs, QR code and snippets. Verified for real on both containers, with and
  without ufw: closed → shared (this VM listed models over `/v1`) → private and closed again.
  CLI: `llmwizard expose <target> [lan|private]`. Not built: Tailscale wizard, phone-side probe.
- **Phase 5 (what doesn't need Jay):** Ollama pinned to 0.40.2 + sha256; signed recipe/catalog
  bundle with current+next keys and rollback protection, and `llmwizard bundle keygen|make|sign|verify`
  (tested with a throwaway key; the real key is Jay's to make); Pro unlock behind one
  `Entitlements` interface with Play Billing 9.1.0 (product id placeholder `llmwizard_pro`, free
  tier = one server, debug override); diagnostic report with redaction; draft privacy policy in
  `docs/privacy-policy.md`. The full app builds minified (11 MB) but has not been run on a phone.
- **Phone incident, 11:26:** after a slow 45 MB install over Tailscale, my fixed-delay taps went
  in blind and opened Google Calendar's new-event screen (not saved) while Jay was also using the
  phone. I stopped touching the phone. From now on: check the foreground app before every tap.

## Waiting on Jay

Play Console (listing, product id, testing track), the real bundle signing key and where the
bundle is hosted (question 5), the app's name and applicationId (question 1), Pro contents and
price (question 3), an NVIDIA machine, the real Hyper-V VMs and the MX desktop, and walking the
phase gates. Release 2 (the assistant) is not started, per the plan's gates.
