# llmwizard

*Codename. Work in progress, not yet released.*

An Android app that takes a Linux machine you own from nothing to a working, private local AI
model, then stays on as the chat client and management console for it. Everything goes over
SSH; nothing passes through anyone else's cloud.

- **Honest recommendation.** A read-only hardware scan, then a model that will actually run at
  a usable speed on that machine, with the speed labeled as an estimate before install and
  measured after.
- **An install that shows its work.** Every command is a step in a recipe (data, not code), shown
  in full before it runs, recorded on the machine, and undoable. Ollama is installed the way its
  own Linux instructions do it, without touching GPU drivers.
- **Private by default.** Ollama listens only on the machine itself; the app reaches it through
  the SSH connection. Sharing with other devices is opt-in, one network at a time, with a
  firewall rule when a firewall is active, and refused on machines with a public address.
- **Survives a sleeping phone.** Long steps run detached on the server and are picked up again.

A second release will make that model the phone's assistant, with a confirm card in front of
every action. See `PLAN.md`.

## Layout

| Path | What |
|---|---|
| `core/` | All logic, plain Kotlin/JVM: SSH (sshj), remote jobs, hardware scan, catalog and recommender, recipe engine, Ollama client, assistant tools and eval |
| `cli/` | The whole flow from a terminal: `scan`, `recommend`, `plan`, `install`, `chat`, `expose`, `uninstall`, `eval-tools`, … |
| `app/` | The Android app (Compose, Hilt, Room) |
| `recipes/`, `catalog/` | Install recipes and the model catalog, as JSON data |
| `fixtures/` | Hardware scans from real machines, used by the tests |
| `eval/` | Phone commands for measuring tool-calling accuracy |

`SPEC.md` is the project guide, `PLAN.md` the plan and phases, `DECISIONS.md` the decision log.

## Build

```
./gradlew :core:test            # logic tests, no device needed
./gradlew :cli:installDist      # cli/build/install/llmwizard/bin/llmwizard help
./gradlew :app:assembleDebug
```

Supported targets for now: Ubuntu 22.04+ and Debian 12+ on x86_64 with systemd.
