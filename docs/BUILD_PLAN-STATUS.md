# BUILD_PLAN status (handoff, 2026-10-13)

Where the expansion in `docs/BUILD_PLAN.md` stands. The reasons for each change from the plan are in `DECISIONS.md` (2026-10-13 entries).

## Done
- **E1:** 22 models added to the catalog, every one resolved on the Ollama registry (43 in total). Fields for tags, uncensored, licence, trained context, `hfRepo` and info link. `ModelFilter` (uncensored is opt-in; the default picks are unchanged). A "why this model" explanation for every model. CLI: `recommend --filter uncensored --why`.
- **E2.1–2.3, 2.5:** `ModelConfig` sent through Ollama's `/api/create` (Modelfile preview, no shell), the context-length advisor, and `Benchmark.compare`.
- **E3:** the `training-stack-install` and `training-cleanup` recipes, dataset validation and upload, jobs that run detached and can be resumed (`train.py` is fixed and reads only `config.json`), the time estimator (calibrated), and merge → GGUF → llama-quantize → Ollama. **Verified on the wiz-debian LXD container** (qwen3:0.6b → `qwen3-bigiron:q4` learned the fact).
- **E4:** the `aider-install`, `open-interpreter-install` and `agent-tool-remove` recipes. Profiles stored on the server with approve-each (default) or auto-run. tmux sessions with a log, the key buttons and the terminal view. **Verified: a real Aider session fixed and committed a bug.** Open Interpreter installs and starts, but was not driven through a task.
- **E5.1:** the article format, a loader with search, the help map and 4 seed articles.
- **App:** a per-server tools hub with Models, Train, Agents and Learn tabs, the "?" help sheet, Learn on the home screen, and screenshot tests 20–28 (`ToolsScreenshotTest`).

## Not done
- E5.2: about 26 more articles (`knowledge/*.md` plus `index.txt`).
- E5.4: plain-English "what just happened" summaries after recipe runs. E5.5: tap-to-explain notes on numbers.
- E2.4: quantization swap. It needs the available quant tags per model in the catalog.
- E1.7: an `eval-tools` run on the new tool-capable models (abliterated Qwen3.5/Qwen3, gpt-oss-abliterated, mistral-nemo, command-r, qwen2.5-coder).
- The phone: none of the new screens has been run on a device yet.
- A clean re-run of `open-interpreter-install` with the `setuptools<80` pin. The Ubuntu run already passed with it; Debian was patched by hand first.

## Test targets
LXD `wiz-debian` (10.138.76.29) has Ollama, qwen3:0.6b, Aider, Open Interpreter, the training stack and `qwen3-bigiron:q4`. Snapshot `pre-e3e4` = before these installs; `clean` = original. LXD `wiz-ubuntu` has the three new recipes installed. CLI: `train <target> …`, `agents <target> …`, `recipe <target> <id>`.
