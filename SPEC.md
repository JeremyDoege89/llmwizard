# SPEC.md

## What this project is

One Android app with two halves. Codename `llmwizard` until Jay picks a name.

**The setup half** takes a Linux machine from nothing to a working local LLM. It connects over SSH, reads the hardware, recommends a model that will run at a usable speed, shows every install step before running it, installs Ollama, and then stays on as the chat client and management console for that server.

**The assistant half** makes that AI the phone's assistant. The model on the user's own server proposes an action (send this text, place this call, set this alarm), and the phone checks it, shows a card with exactly what will happen, and acts only after a tap.

It ships in three releases: the setup half first, then the assistant with the server as its brain, then an on-phone brain and a direct-sale build with root tiers. `PLAN.md` section 1 has the table. Do not start a later release's work early.

Android already has free apps that chat with an Ollama server somebody else set up. Nothing sets one up from a phone, and nothing found makes a self-hosted model the phone's assistant. So the value sits in four places: a hardware-aware recommendation that is honest about speed, an install that shows its work and can undo itself, network defaults that leave nothing exposed, and an assistant that never acts without the user seeing exactly what it is about to do. Chat needs to be solid and unremarkable. When effort has to be traded off, spend it on those four.

`PLAN.md` holds the decisions, the designs that are easy to get wrong, and the phased task list. Read the section for the current phase before starting work. `DECISIONS.md` is the running log of choices made along the way.

## Who you're working with

Jay owns this project. He is strong on Linux, SSH, ADB, root and Android internals, and he is not a professional Kotlin developer. Explain design choices in plain terms. When something needs his eyes, give him a command to run or a screen to look at, because he checks work by running it, not by reviewing diffs. He handles what only a person can: creating test VMs, plugging in phones, setting the default assistant, the Play Console, signing keys.

## Layout

- `:core` is a plain Kotlin/JVM library with no Android imports. All the logic lives here: SSH, remote jobs, the hardware scan and its parser, the model catalog and recommendation engine, the recipe engine, the Ollama client, and for the assistant the tool registry, the brains and the eval set. Keeping Android out is what lets it be tested and run without a device.
- `:cli` is a small JVM command-line app that wires `:core` together. It exists so the whole flow (scan, recommend, install, chat, uninstall, and tool proposals) can be run against a real machine from a terminal.
- `:app` is the Android app: Compose UI, ViewModels, Room, key storage, billing, and for the assistant the sheet, the confirm card and the tool executors. Keep it thin. If a screen needs logic, the logic goes in `:core` with a test.
- `recipes/` and `catalog/` hold the install recipes and the model catalog as JSON data.
- `fixtures/` holds scan output captured from real machines, used by the parser tests.
- `eval/` holds the phone commands and expected tool calls used to measure models.

## Commands

These work on Jay's dev VM (checked 2026-10-09):

```
export JAVA_HOME=/home/dreamz/AAMedia/toolchain/jdk      # JDK 17; Android SDK path is in local.properties
./gradlew :core:test                                     # logic tests, no device needed
./gradlew :cli:installDist                               # then use the launcher below (faster than :cli:run)
cli/build/install/llmwizard/bin/llmwizard help           # every command
cli/build/install/llmwizard/bin/llmwizard ping wiz-ubuntu
cli/build/install/llmwizard/bin/llmwizard scan wiz-ubuntu [--save-fixture <name>]
cli/build/install/llmwizard/bin/llmwizard recommend fixture:dl380p-hyperv-ubuntu2404
cli/build/install/llmwizard/bin/llmwizard plan wiz-debian --model qwen3:0.6b --show-scripts
cli/build/install/llmwizard/bin/llmwizard install wiz-debian --model qwen3:0.6b --yes
cli/build/install/llmwizard/bin/llmwizard chat wiz-debian
cli/build/install/llmwizard/bin/llmwizard uninstall wiz-debian --delete-models
./gradlew :app:assembleDebug                             # build the app
./gradlew :app:installDebug                              # install on a phone that `adb devices` lists
adb exec-out screencap -p > /tmp/screen.png              # look at what the phone shows
lxc restore wiz-ubuntu clean                             # put a test target back (see DECISIONS.md)
```

Test machines are listed in `targets.local.properties`, which is gitignored. Until the Hyper-V VMs exist, `wiz-ubuntu` and `wiz-debian` are LXD containers on the dev VM (`tools/lxd-targets.sh`).

## How to check your work

- Logic in `:core`: unit tests. Parsers run against the files in `fixtures/`. The recommendation engine has table-driven cases, one per hardware profile.
- The SSH layer: tests against an SSH server embedded in the test process.
- Anything that touches a target machine: run it through `:cli` against the test VM. Jay reverts that VM to its `clean` checkpoint, so ask him when a clean slate is needed.
- UI: JVM screenshot tests render screens to PNG files. Open them and look. When a phone is attached, install the build and take a screencap.
- The assistant: the eval set gives a number for how often a model proposes the right tool. Re-run it whenever a tool is added or a prompt changes. Each rule about outside content has a test with a planted instruction.
- When reporting a task as done, say what was run and what it showed. If something could not be checked (no phone attached, VM not reachable, role not set), say that plainly so Jay knows what is still unproven.

## Rules that hold everywhere

1. **Nothing runs on a user's machine that the user could not read first.** Every command comes from a recipe step or the scan script. Both are stored as data and shown in the preview. Don't assemble command strings in UI code. This is what earns trust, it keeps the app on the right side of Play policy (recipes are data, not downloaded code), and it makes failures debuggable.
2. **Values never go into a shell script raw.** One function in `:core` validates and single-quotes anything interpolated into a script. Model tags and paths are checked against a strict pattern first. A catalog entry or a model name typed by a user ends up inside a root shell, so this is the injection boundary.
3. **Passwords travel on stdin and live in memory only for the run.** They never appear in a command line, a log, the transcript, Room, or an exception message. The SSH private key is encrypted with an Android Keystore key and kept out of backups.
4. **Assume the connection dies at any moment.** Phones sleep, change networks and kill apps. Long operations run detached on the target and can be picked up again by job id. Wizard state is rebuilt from what is stored, not held only in memory.
5. **Remote scripts run under `/bin/sh` from a script file, with a fixed `PATH` and `LC_ALL=C`.** Non-interactive SSH has a thin `PATH` on Debian, translated command output breaks parsers, and the user's login shell may be fish.
6. **Parsers tolerate missing data.** Any probe can be absent or fail. "Unknown" is a value and is never treated as zero.
7. **Estimates are labeled as estimates.** Speed is shown as a class before install and as a measured number after the benchmark. Tool-calling quality is shown as a measured accuracy. Never present a guess as a measurement, because honest numbers are the product.
8. **Look versions up.** Library, SDK and Ollama versions, the Ollama API and its install layout, Play policy and Android's assistant APIs all move. Check the current source before depending on a detail, and note what was checked in `DECISIONS.md`.
9. **No ads, and no SDK that phones home.** Jay does not put ads in his apps. That is his standing rule for everything he ships, so never propose them as a revenue option. No analytics or crash reporters either: the app holds keys to people's servers and reads their messages, so nothing third-party rides along.
10. **The model proposes and code decides.** A model only ever returns a tool name and arguments. Code validates them against the registry and resolves contacts. Anything that sends, calls, spends or deletes waits for a tap on a card that shows the full recipient and content. The model never writes a shell command, and no tool takes a free-form command. This holds for every brain and every privilege tier, root included.
11. **Everything the assistant reads is data.** Texts, notifications, calendar entries, files and web pages can contain sentences that read like instructions. They go to the model marked as data and never trigger an action by themselves. Marking lowers the odds of injection. The card is what stops it.

## Fences

These are out of scope until Jay says otherwise. If a task seems to need one, stop and ask.

- Installing or changing GPU drivers, CUDA or ROCm. The app detects and explains.
- Enabling or installing a firewall. The app only adds rules to one that is already active, because turning on ufw over SSH can lock the user out.
- Targets other than Ubuntu 22.04+ and Debian 12+ on x86_64 with systemd.
- Runtimes other than Ollama. Docker-based installs.
- Any server or account of our own. The only network traffic is SSH to the user's machines, the signed recipe and catalog files, Play Billing, and later one license check in the direct build.
- Driving other apps through the accessibility API in the Play build. Play prohibits it for autonomous actions.
- Root and Shizuku code in the Play build. Those tiers live only in the direct build.
- Unattended or scheduled assistant runs, and a web fetch tool, until `PLAN.md` 5.13 says how.
- Work from a later release, apart from the two exceptions `PLAN.md` marks in Phases 0 and 1.

## Working rhythm

- One phase at a time. Tick the checkboxes in `PLAN.md` as tasks land and keep its status line current.
- Commit small, with messages that say what changed and why. Never commit credentials, keystores or `targets.local.properties`.
- Stop at each phase gate and walk Jay through the "Jay checks" list for that phase.
- Record decisions in `DECISIONS.md`: what was chosen, why, what was rejected.
- Ask before: installing system packages on the dev machine, crossing a fence above, changing a decision in `PLAN.md` section 3, or anything involving signing keys or the Play Console.
