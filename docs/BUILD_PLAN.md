# LLMWizard Build Plan: The Full-Stack Local AI Toolkit

> **Author:** Jay (jaydoege8969)  
> **Date:** 2026-10-13  
> **Target hardware:** DL380p Gen8 — dual Xeon E5-2680 v2, 256 GB RAM, no GPU  
> **Baseline:** llmwizard repo at Phase 1 gate (2026-10-10)

---

## 0 — What This Plan Is

This document extends llmwizard from "install Ollama on a server from your phone" into **the** local AI power tool — one app that lets you discover, install, configure, train, optimize, and understand uncensored LLMs running on your own hardware, all managed from your Android phone over SSH.

Every new feature plugs into the architecture that already exists: the recipe engine, the catalog/recommender, the hardware scanner, the agent framework, the signed-bundle system. Nothing gets thrown away.

The plan is divided into **five expansion phases** (E1–E5), layered on top of the existing nine-phase roadmap. Each phase ships something usable. Each phase teaches the user what it just did.

---

## 1 — Architecture Snapshot (What Already Works)

Before we build, here's what we're building on:

| Layer | Status | Key Files |
|---|---|---|
| SSH transport (sshj) | Done | `SshSession.kt`, `RemoteJob.kt` |
| Hardware scanner | Done | `ScanScript.kt` → `HardwareProfile` |
| Model catalog (21 models) | Done | `catalog.json`, `Catalog.kt` |
| Bandwidth-based recommender | Done | `Recommender.kt` (MoE-aware, per-core cap) |
| Recipe engine (idempotent) | Done | `Engine.kt`, recipes/*.json |
| Ollama install recipe | Done | `ollama-install.json` |
| Signed bundles (Ed25519) | Done | `Bundle.kt` |
| Agent brain + 6 tools | Done | `Brain.kt`, `Tools.kt` |
| Compose UI (Material 3) | Done | `HomeScreens.kt` + nav |
| Eval framework | Done | `eval/` directory |

**What's not done from the original PLAN.md:** Phases 5–8 (exposure, storage, assistant polish, on-phone brain). Those continue in parallel. This build plan adds entirely new capabilities.

---

## 2 — Expansion Phase E1: Uncensored Model Curation

**Goal:** The catalog knows about uncensored/abliterated models, recommends them when the user wants unrestricted output, and installs them exactly like any other model.

### E1.1 — Catalog Additions

Add these model entries to `catalog.json`. Each one has been verified to exist on the Ollama library. Sizes are for the quantization that fits a CPU-only 256 GB box best.

```
New catalog entries (est. 15–20 models):

UNCENSORED / ABLITERATED CHAT:
- dolphin-mistral:7b-v2.8-q4_K_M     — 4.4 GB, quality 3, tools: no
- dolphin-llama3:8b-v2.9-q4_K_M      — 4.9 GB, quality 3, tools: no
- dolphin-llama3:70b-v2.9-q4_K_M     — 40 GB, quality 5, tools: no
- nous-hermes2:34b-q4_K_M            — 20 GB, quality 4, tools: yes
- nous-hermes-2-mixtral:8x7b-q4_K_M  — 26 GB, quality 4, tools: yes (MoE)
- openhermes:7b-v2.5-q4_K_M          — 4.4 GB, quality 3, tools: yes
- samantha-mistral:7b-q4_K_M         — 4.4 GB, quality 2, tools: no
- wizard-vicuna-uncensored:30b-q4_K_M — 18 GB, quality 3, tools: no
- llama3-chatqa:8b-q4_K_M            — 4.9 GB, quality 3, tools: no
- yi:34b-chat-q4_K_M                 — 20 GB, quality 4, tools: no
- mistral-nemo:12b-q4_K_M            — 7.1 GB, quality 3, tools: yes

CODING-FOCUSED:
- deepseek-coder-v2:16b-q4_K_M       — 9.4 GB, quality 4, tools: no
- codestral:22b-v0.1-q4_K_M          — 13 GB, quality 4, tools: no
- starcoder2:15b-q4_K_M              — 9.0 GB, quality 3, tools: no
- qwen2.5-coder:32b-q4_K_M           — 19 GB, quality 4, tools: yes

REASONING / RESEARCH:
- dolphin3:24b-q4_K_M                — 14 GB, quality 4, tools: yes
- command-r:35b-q4_K_M               — 20 GB, quality 4, tools: yes
```

### E1.2 — Catalog Schema Extension

Add fields to `ModelEntry`:

```kotlin
data class ModelEntry(
    // ... existing fields ...
    /** Tags for filtering: "uncensored", "coding", "reasoning", "chat", "research" */
    val tags: Set<String> = emptySet(),
    /** Whether the model has had safety guardrails removed (abliterated/uncensored). */
    val uncensored: Boolean = false,
    /** License type: "apache-2.0", "llama", "gemma", "proprietary", etc. */
    val license: String? = null,
    /** Recommended context length (override for models that handle more/less than default). */
    val recommendedContext: Int? = null,
    /** URL for model card / documentation. */
    val infoUrl: String? = null,
)
```

### E1.3 — Recommender Filter Extension

Extend `Recommender.recommend()` to accept an optional `ModelFilter`:

```kotlin
data class ModelFilter(
    val tags: Set<String>? = null,          // any of these tags
    val uncensoredOnly: Boolean = false,     // only uncensored models
    val minQuality: Int? = null,             // quality >= N
    val supportsTools: Boolean? = null,      // tool-calling required?
    val maxDownloadBytes: Long? = null,      // bandwidth-limited users
)
```

The recommender already hard-gates on memory and disk; adding tag/uncensored filtering is a `filter {}` call before the sort.

### E1.4 — UI: Model Browser Screen

New Compose screen: `ModelBrowserScreen`

- Grid/list of all catalog models with filter chips at top: `All`, `Uncensored`, `Coding`, `Reasoning`, `Chat`
- Each card shows: name, size, quality stars, speed estimate for current hardware, tags
- Tap to expand: full description, notes, `Install` button, link to model card
- Sort by: quality, speed estimate, size
- Search bar for name/tag

### E1.5 — "Why This Model" Explanations

Every recommendation includes a human-readable explanation string:

```
"dolphin-mistral:7b runs at ~19 tok/s on your box (comfortable speed).
It's a 7B uncensored model based on Mistral — no refusal training,
so it won't argue about what you're asking. 4.4 GB download.
Trade-off: smaller models are less capable at complex reasoning."
```

This is generated in the recommender and displayed on the model card and in the install flow.

### E1.6 — Files to Create/Modify

| Action | File | What |
|---|---|---|
| Modify | `catalog/catalog.json` | Add 15–20 new model entries with tags, uncensored flag |
| Modify | `core/.../Catalog.kt` | Add `tags`, `uncensored`, `license`, `recommendedContext`, `infoUrl` fields |
| Modify | `core/.../Recommender.kt` | Add `ModelFilter` parameter, explanation string generation |
| Create | `app/.../ModelBrowserScreen.kt` | New Compose screen |
| Create | `app/.../ModelCard.kt` | Reusable model card composable |
| Modify | `app/.../Navigation.kt` | Add model browser route |

### E1.7 — Eval Gate

Before shipping: run the existing eval set against at least 3 new uncensored models that claim `supportsTools: true`. Record `toolAccuracy` in catalog. Models below 70% accuracy get `supportsTools: false`.

---

## 3 — Expansion Phase E2: Model Configuration & Optimization

**Goal:** After installing a model, the user can tune it — context length, temperature, system prompt, GPU layers (future), quantization swap — all from the phone, all explained.

### E2.1 — Modelfile Management

Ollama uses `Modelfile` to customize models. LLMWizard needs to generate, deploy, and manage these.

```kotlin
@Serializable
data class ModelConfig(
    val baseModel: String,              // e.g. "dolphin-mistral:7b"
    val customTag: String,              // e.g. "dolphin-research"
    val systemPrompt: String? = null,
    val temperature: Double? = null,    // 0.0 – 2.0
    val topP: Double? = null,           // 0.0 – 1.0
    val topK: Int? = null,              // 1 – 100
    val repeatPenalty: Double? = null,   // 0.0 – 2.0
    val contextLength: Int? = null,      // 2048 – 131072
    val seed: Int? = null,               // reproducibility
    val numGpu: Int? = null,             // GPU layer offload (0 for CPU-only)
    val mirostat: Int? = null,           // 0, 1, or 2
    val mirostatTau: Double? = null,
    val mirostatEta: Double? = null,
    val stopTokens: List<String>? = null,
)
```

### E2.2 — Modelfile Recipe

New recipe: `model-configure.json`

Steps:
1. **Preflight:** Model must be pulled (check `ollama list`)
2. **Generate Modelfile:** Write to `/tmp/llmwizard-modelfile-{tag}`
3. **Create custom model:** `ollama create {customTag} -f /tmp/llmwizard-modelfile-{tag}`
4. **Verify:** `ollama show {customTag}` succeeds
5. **Cleanup:** Remove temp Modelfile

Undo: `ollama rm {customTag}`

### E2.3 — Context Length Advisor

The recommender already estimates speed. Extend it to estimate memory impact of context length changes:

```
Context length affects RAM usage through the KV cache.
Current model: dolphin-mistral:7b at 4096 context = ~4.8 GB total
  → At 8192 context = ~5.4 GB total (+12%)
  → At 16384 context = ~6.6 GB total (+38%)
  → At 32768 context = ~9.0 GB total (+88%)

Your box has 256 GB RAM with 248 GB free. You could run this
model at 131072 context and still have room for two more models.
```

The existing `kvBytesPer1kTokensBySize` constants in the catalog already support this calculation.

### E2.4 — Quantization Swap

Let users re-download a model in a different quantization:

- Show current quant (e.g., Q4_K_M) with explanation of what it means
- Offer available quants with size/quality tradeoffs:
  - Q4_K_M: Smallest, slight quality loss, fastest inference
  - Q5_K_M: Middle ground
  - Q6_K: Near-full quality, larger
  - Q8_0: Minimal quality loss, 2x size of Q4
  - FP16: Full precision, massive, only if you have the RAM for it
- One-tap swap: pull new tag, remove old if desired

### E2.5 — Speed Benchmark Tool

After model install, offer a quick local benchmark:

```bash
# Measure actual tok/s on the user's hardware
curl -s http://127.0.0.1:11434/api/generate \
  -d '{"model":"dolphin-mistral:7b","prompt":"Write a short story about a robot","stream":false}' \
  | jq '.eval_count / (.eval_duration / 1e9)'
```

Compare measured vs. estimated, save to manifest for future reference. Show:

```
Benchmark: dolphin-mistral:7b on your DL380p
  Estimated: 19.2 tok/s (comfortable)
  Measured:  17.8 tok/s (comfortable)
  Estimation error: 7.3% — not bad.

  First-token latency: 2.1s
  Context processing: 142 tok/s
```

### E2.6 — Files to Create/Modify

| Action | File | What |
|---|---|---|
| Create | `core/.../config/ModelConfig.kt` | Config data class + Modelfile generator |
| Create | `recipes/model-configure.json` | Modelfile recipe |
| Create | `core/.../benchmark/Benchmark.kt` | Speed benchmark over SSH |
| Modify | `core/.../Recommender.kt` | Context length memory estimator |
| Create | `app/.../ConfigureModelScreen.kt` | Model tuning UI |
| Create | `app/.../BenchmarkScreen.kt` | Benchmark results UI |

---

## 4 — Expansion Phase E3: Training & Fine-Tuning

**Goal:** The user can fine-tune a model on their own data, on their own CPU, from their phone. Slow as hell on CPU, but it works, and the app tells you exactly how long it'll take.

### E3.1 — Reality Check: CPU Training

Let's be upfront about what CPU training means on a DL380p:

| Method | What It Does | Time for 1000 samples on DL380p | Notes |
|---|---|---|---|
| **QLoRA** | Trains a small adapter on top of a frozen model | ~8–24 hours for a 7B model | The practical choice for CPU |
| **LoRA** | Same but no quantization of base | ~12–36 hours for a 7B | More RAM, slightly better quality |
| **Full fine-tune** | Retrains all weights | Days to weeks for 7B | Don't even think about it on CPU |
| **GGUF adapter merge** | Merges a trained LoRA into a GGUF | ~30 min for 7B | Post-training step |

The app will be honest: "This will take approximately 14 hours on your hardware. A $400 RTX 3090 would do it in 18 minutes. Your call."

### E3.2 — Training Stack Installation

New recipe: `training-stack-install.json`

Steps:
1. **Preflight:** Check Python 3.10+, pip, 50 GB free disk
2. **Create venv:** `/opt/llmwizard/training/venv`
3. **Install PyTorch CPU:** `pip install torch --index-url https://download.pytorch.org/whl/cpu`
4. **Install training deps:** `pip install transformers datasets peft bitsandbytes accelerate trl`
5. **Install llama.cpp:** Build from source for GGUF conversion
6. **Install Unsloth (optional):** If hardware supports it, 2x speedup on training
7. **Verify:** Run a smoke test (1 sample, 1 step)

Undo: `rm -rf /opt/llmwizard/training`

### E3.3 — Dataset Management

Support three dataset formats (what people actually have):

1. **Chat format (JSONL):**
```json
{"messages": [{"role": "user", "content": "..."}, {"role": "assistant", "content": "..."}]}
```

2. **Instruction format (JSONL):**
```json
{"instruction": "...", "input": "...", "output": "..."}
```

3. **Plain text (one doc per line or file):**
```
Just raw text for continued pretraining
```

Upload flow:
- User selects files from phone → SCP to server
- App validates format, counts samples, estimates training time
- App shows preview of first 3 samples

```kotlin
@Serializable
data class TrainingDataset(
    val id: String,
    val name: String,
    val format: DatasetFormat,
    val path: String,            // on the server
    val sampleCount: Int,
    val sizeBytes: Long,
    val uploadedAt: String,
    val validated: Boolean,
)

@Serializable
enum class DatasetFormat { CHAT_JSONL, INSTRUCT_JSONL, PLAIN_TEXT }
```

### E3.4 — Training Job Configuration

```kotlin
@Serializable
data class TrainingJob(
    val id: String,
    val baseModel: String,           // Ollama tag of the base model
    val dataset: String,             // Dataset ID
    val method: TrainingMethod,
    val outputName: String,          // Name for the resulting model

    // Hyperparameters (with sane defaults)
    val learningRate: Double = 2e-4,
    val epochs: Int = 3,
    val batchSize: Int = 1,          // CPU reality: batch size 1
    val gradientAccumulationSteps: Int = 4,
    val maxSeqLength: Int = 2048,
    val loraRank: Int = 16,          // r: 8, 16, 32, 64
    val loraAlpha: Int = 32,
    val loraDropout: Double = 0.05,
    val warmupSteps: Int = 10,
    val weightDecay: Double = 0.01,
    val optimizer: String = "adamw_torch",

    // Status tracking
    val status: TrainingStatus = TrainingStatus.PENDING,
    val progress: TrainingProgress? = null,
    val startedAt: String? = null,
    val completedAt: String? = null,
    val error: String? = null,
)

@Serializable
enum class TrainingMethod { QLORA, LORA }

@Serializable
enum class TrainingStatus { PENDING, RUNNING, COMPLETED, FAILED, CANCELLED }

@Serializable
data class TrainingProgress(
    val currentStep: Int,
    val totalSteps: Int,
    val currentEpoch: Int,
    val totalEpochs: Int,
    val loss: Double,
    val learningRate: Double,
    val tokensPerSecond: Double,
    val estimatedRemainingSeconds: Long,
)
```

### E3.5 — Training Execution

The training job runs as a detached process on the server (the existing `RemoteJob` infrastructure handles this):

1. **Generate training script:** Python script from the `TrainingJob` config
2. **Upload script + dataset reference**
3. **Launch detached:** `nohup python train.py > /opt/llmwizard/training/jobs/{id}/log.txt 2>&1 &`
4. **Monitor:** Tail the log file over SSH, parse progress lines
5. **On completion:**
   - Convert LoRA adapter to GGUF format using llama.cpp
   - Create Ollama Modelfile pointing to the merged GGUF
   - Register with `ollama create`
   - Record in manifest

The key insight: the user's phone can disconnect at any time. The training script saves checkpoints every N steps and writes a progress file. When the phone reconnects, the app reads the progress file and resumes monitoring.

### E3.6 — Training Script Template

The app generates this Python script (filled with values from `TrainingJob`):

```python
#!/usr/bin/env python3
"""Generated by LLMWizard — QLoRA training job {id}"""
import json, sys, time
from pathlib import Path
from datasets import load_dataset
from transformers import (
    AutoModelForCausalLM, AutoTokenizer,
    TrainingArguments, BitsAndBytesConfig,
)
from peft import LoraConfig, get_peft_model, prepare_model_for_kbit_training
from trl import SFTTrainer

# --- Config (generated, do not edit) ---
BASE_MODEL = "{huggingface_model_id}"  # resolved from Ollama tag
DATASET_PATH = "{dataset_path}"
OUTPUT_DIR = "/opt/llmwizard/training/jobs/{id}/output"
PROGRESS_FILE = "/opt/llmwizard/training/jobs/{id}/progress.json"
# ... all hyperparameters from TrainingJob ...

# --- Progress callback ---
class ProgressCallback:
    def on_log(self, args, state, control, logs=None, **kwargs):
        progress = {
            "currentStep": state.global_step,
            "totalSteps": state.max_steps,
            "currentEpoch": int(state.epoch) if state.epoch else 0,
            "totalEpochs": {epochs},
            "loss": logs.get("loss", 0) if logs else 0,
            "learningRate": logs.get("learning_rate", 0) if logs else 0,
            "tokensPerSecond": logs.get("train_tokens_per_second", 0) if logs else 0,
            "estimatedRemainingSeconds": ...,  # calculated from rate
        }
        Path(PROGRESS_FILE).write_text(json.dumps(progress))

# ... BitsAndBytesConfig, LoraConfig, training setup ...
# (full template in core/src/main/resources/llmwizard/training/train_template.py)
```

### E3.7 — Training Time Estimator

Before the user commits to training, show them what they're in for:

```kotlin
fun estimateTrainingTime(
    profile: HardwareProfile,
    model: ModelEntry,
    dataset: TrainingDataset,
    job: TrainingJob,
): TrainingEstimate {
    // CPU training throughput: ~2-5 tokens/sec for 7B QLoRA on Xeon
    // Scale by model size, batch size, sequence length
    val tokensPerSample = job.maxSeqLength // worst case
    val totalTokens = dataset.sampleCount.toLong() * tokensPerSample * job.epochs
    // ... bandwidth-based estimation similar to inference ...
}

data class TrainingEstimate(
    val totalSeconds: Long,
    val tokensPerSecond: Double,
    val explanation: String,      // human-readable
    val gpuComparison: String,    // "An RTX 3090 would do this in 18 min"
)
```

### E3.8 — Post-Training Pipeline

After training completes:

1. **Merge adapter:** LoRA weights merged into base model
2. **Convert to GGUF:** Using llama.cpp's `convert.py` + `quantize`
3. **Choose quantization:** User picks Q4_K_M, Q5_K_M, Q8_0 etc.
4. **Register with Ollama:** `ollama create {outputName} -f Modelfile`
5. **Benchmark:** Run the speed benchmark against the new model
6. **Compare:** Show before/after on a few sample prompts

### E3.9 — Files to Create/Modify

| Action | File | What |
|---|---|---|
| Create | `core/.../training/TrainingJob.kt` | Job data classes |
| Create | `core/.../training/TrainingDataset.kt` | Dataset management |
| Create | `core/.../training/TrainingEstimator.kt` | Time/resource estimation |
| Create | `core/.../training/TrainingScriptGen.kt` | Python script generator |
| Create | `core/.../training/TrainingMonitor.kt` | Progress file polling |
| Create | `core/src/main/resources/llmwizard/training/train_template.py` | Training script template |
| Create | `recipes/training-stack-install.json` | Training deps recipe |
| Create | `app/.../training/TrainingScreen.kt` | Training job UI |
| Create | `app/.../training/DatasetScreen.kt` | Dataset upload/manage UI |
| Create | `app/.../training/TrainingProgressScreen.kt` | Live progress monitoring |
| Modify | `core/.../recipe/Manifest.kt` | Track training artifacts |

---

## 5 — Expansion Phase E4: Agent Framework Integration

**Goal:** The user can set up and use Aider (AI pair programmer) and Open Interpreter (autonomous coding agent) on their server, managed through LLMWizard with their local uncensored models.

### E4.1 — Aider Integration

[Aider](https://aider.chat) is a terminal-based AI pair programmer. It works with local Ollama models.

**Install recipe:** `aider-install.json`

```
Steps:
1. Preflight: Python 3.10+, pip, Ollama running
2. Install: pip install aider-chat (in llmwizard venv)
3. Configure: Write ~/.aider.conf.yml pointing to Ollama
4. Verify: aider --version
```

**Configuration:**

```yaml
# ~/.aider.conf.yml (generated by LLMWizard)
ollama-base-url: http://127.0.0.1:11434
model: ollama/{model_tag}
edit-format: diff      # or whole, udiff — depends on model capability
auto-commits: true
map-tokens: 2048
```

**From the phone UI:**
- Start/stop Aider sessions (detached tmux)
- Select which model Aider uses (from installed models)
- Point Aider at a git repo on the server
- View Aider's conversation log in real-time
- Send commands to Aider's stdin

### E4.2 — Open Interpreter Integration

[Open Interpreter](https://github.com/OpenInterpreter/open-interpreter) lets an LLM run code on your machine.

**Install recipe:** `open-interpreter-install.json`

```
Steps:
1. Preflight: Python 3.10+, pip, Ollama running
2. Install: pip install open-interpreter
3. Configure: Set up profiles for local models
4. Verify: interpreter --version
```

**Configuration:**

```python
# Generated by LLMWizard
from interpreter import interpreter
interpreter.offline = True
interpreter.llm.model = "ollama/{model_tag}"
interpreter.llm.api_base = "http://127.0.0.1:11434"
interpreter.auto_run = False  # safety default, user can override
```

### E4.3 — Agent Session Manager

Both Aider and Open Interpreter run as persistent terminal sessions. LLMWizard manages them through tmux:

```kotlin
@Serializable
data class AgentSession(
    val id: String,
    val agent: AgentType,          // AIDER or OPEN_INTERPRETER
    val model: String,             // Ollama model tag
    val workDir: String,           // Working directory on server
    val tmuxSession: String,       // tmux session name
    val startedAt: String,
    val status: SessionStatus,
)

@Serializable
enum class AgentType { AIDER, OPEN_INTERPRETER }

@Serializable
enum class SessionStatus { RUNNING, STOPPED, ERROR }
```

**Session operations (via SSH):**
- `tmux new-session -d -s llmw-{id} -c {workDir} '{command}'` — start
- `tmux send-keys -t llmw-{id} '{input}' Enter` — send input
- `tmux capture-pane -t llmw-{id} -p` — read output
- `tmux kill-session -t llmw-{id}` — stop

### E4.4 — Custom Agent Profiles

Let users create purpose-built agent configurations:

```kotlin
@Serializable
data class AgentProfile(
    val id: String,
    val name: String,              // "MediaTek Research Assistant"
    val agent: AgentType,
    val model: String,
    val systemPrompt: String?,     // Custom system prompt
    val workDir: String?,          // Default working directory
    val autoRun: Boolean = false,  // Allow auto-execution (Open Interpreter)
    val extraArgs: List<String> = emptyList(),
)
```

Example profile for the user's use case:

```
Name: "MediaTek Bootloader Research"
Agent: Open Interpreter
Model: dolphin-mistral:7b
System Prompt: "You are a reverse engineering research assistant
  specializing in MediaTek SoC bootloader security. Your job is to
  analyze firmware dumps, identify potential security mechanisms,
  research known CVEs, and help write proof-of-concept code for
  bootloader unlock methods. Work methodically: examine the binary,
  identify key functions, trace execution flow, and document findings."
Auto-run: true
Work Dir: /home/jay/research/mtk
```

### E4.5 — Files to Create/Modify

| Action | File | What |
|---|---|---|
| Create | `recipes/aider-install.json` | Aider install recipe |
| Create | `recipes/open-interpreter-install.json` | Open Interpreter install recipe |
| Create | `core/.../agents/AgentSession.kt` | Session management |
| Create | `core/.../agents/AgentProfile.kt` | Profile data classes |
| Create | `core/.../agents/TmuxManager.kt` | tmux session control over SSH |
| Create | `app/.../agents/AgentHubScreen.kt` | Agent session list + launch |
| Create | `app/.../agents/AgentTerminalScreen.kt` | Live terminal view |
| Create | `app/.../agents/AgentProfileScreen.kt` | Profile editor |

---

## 6 — Expansion Phase E5: Knowledge Base & Education

**Goal:** The app teaches you what it's doing. Every screen has context. There's a dedicated learning section. Users go from "what the fuck is a LoRA" to "I just trained one" in one session.

### E5.1 — Knowledge Base Architecture

The knowledge base is a set of Markdown articles bundled in the app and updateable through signed bundles (same system as catalog updates).

```kotlin
@Serializable
data class Article(
    val id: String,
    val title: String,
    val category: ArticleCategory,
    val tags: Set<String>,
    val difficulty: Difficulty,
    val readMinutes: Int,
    val summary: String,           // 1–2 sentences
    val markdown: String,          // full article content
    val relatedArticles: List<String> = emptyList(),
    val version: Int = 1,
)

@Serializable
enum class ArticleCategory {
    GETTING_STARTED,    // Basics
    MODELS,             // Understanding models
    TRAINING,           // Fine-tuning & training
    OPTIMIZATION,       // Performance tuning
    AGENTS,             // Agent frameworks
    HARDWARE,           // Hardware & specs
    SECURITY,           // Security & uncensoring
    REFERENCE,          // Quick reference cards
}

@Serializable
enum class Difficulty { BEGINNER, INTERMEDIATE, ADVANCED }
```

### E5.2 — Initial Article Set (30+ articles)

**Getting Started:**
- "What Is a Large Language Model?" — No buzzwords, just what it actually does
- "Your First Local AI: From Zero to Chat in 10 Minutes"
- "LLMWizard Quick Start: Your Phone, Your Server, Your AI"
- "Understanding Ollama: The Engine Under the Hood"

**Models:**
- "Model Sizes Explained: What Does '7B Parameters' Mean?"
- "Quantization Demystified: Q4, Q8, FP16 and Why It Matters"
- "Dense vs. Mixture-of-Experts: Why MoE Models Fly on CPU"
- "How to Pick the Right Model for Your Hardware"
- "Context Length: What It Is and When to Crank It Up"
- "Speed vs. Quality: The Eternal Tradeoff"

**Training:**
- "Fine-Tuning 101: Teaching an AI Your Language"
- "QLoRA Explained: Training Big Models on Small Hardware"
- "Preparing Your Training Data: Formats and Best Practices"
- "Hyperparameters for Humans: What Each Knob Does"
- "When Training Goes Wrong: Debugging Common Issues"
- "CPU Training: Yes It's Slow, Here's How to Make It Less Painful"

**Optimization:**
- "Measuring Inference Speed: Tokens Per Second Explained"
- "Memory Management: How Much RAM Does Your Model Really Need?"
- "System Prompts That Actually Work"
- "Temperature, Top-P, Top-K: Controlling Model Creativity"
- "Batching and Concurrency: Running Multiple Requests"

**Agents:**
- "What Is an AI Agent? Beyond Chat Into Action"
- "Aider: Your AI Pair Programmer"
- "Open Interpreter: When Your AI Can Run Code"
- "Tool Calling: How Models Learn to Use Software"
- "Building Custom Agent Profiles"

**Hardware:**
- "CPU Inference: Getting the Most from What You Have"
- "Memory Bandwidth: The Real Bottleneck for Local AI"
- "GPU vs. CPU for AI: When the Upgrade Matters"
- "Xeon E5 v2 for AI: A Practical Guide" (user's specific hardware)

**Security & Uncensoring:**
- "Uncensored Models: What They Are and Aren't"
- "Abliteration: How Safety Training Gets Removed"
- "Running AI Offline: Privacy and Security Considerations"

**Reference:**
- "Ollama CLI Cheat Sheet"
- "Common Error Messages and What They Mean"
- "Glossary: AI Terms in Plain English"

### E5.3 — Contextual Help System

Every screen in the app has relevant articles linked:

```kotlin
/** Maps screens to relevant article IDs. */
val contextualHelp = mapOf(
    "model_browser" to listOf("model-sizes", "quantization", "dense-vs-moe"),
    "model_configure" to listOf("system-prompts", "temperature-top-p", "context-length"),
    "training_setup" to listOf("finetuning-101", "qlora-explained", "preparing-data"),
    "training_progress" to listOf("cpu-training", "hyperparameters", "when-training-goes-wrong"),
    "agent_hub" to listOf("what-is-agent", "aider-guide", "open-interpreter-guide"),
    "benchmark" to listOf("measuring-speed", "memory-management"),
    "install" to listOf("understanding-ollama", "cpu-inference"),
)
```

UI: A small `?` FAB (floating action button) on every screen → slides up a bottom sheet with relevant article cards. Tap to read full article.

### E5.4 — "What Just Happened" Explainers

After every recipe run, the app shows a plain-English summary:

```
What just happened:
✓ Downloaded dolphin-mistral:7b (4.4 GB quantized model)
  → This is a 7-billion parameter language model based on Mistral,
    with safety restrictions removed ("uncensored"). The Q4_K_M
    quantization means each weight is stored in ~4.5 bits instead of
    16, which cuts the size by 70% with minimal quality loss.

✓ Created custom Modelfile with your system prompt
  → A Modelfile tells Ollama how to load the model: what system
    prompt to use, what temperature (creativity level), and how
    much context to keep in memory.

✓ Registered as 'mtk-research' with Ollama
  → You can now use this model by name. It'll auto-load when you
    send it a message and stay in RAM for 30 minutes after.
```

### E5.5 — Inline Tooltips

Key numbers and terms throughout the app have tap-to-explain tooltips:

```
Speed: 19.2 tok/s ⓘ
  └─ "Tokens per second: how fast the model generates text.
      A token is roughly 3/4 of a word. At 19 tok/s, this model
      writes about 14 words per second — comfortable reading speed."

Memory: 4.8 GB / 256 GB ⓘ
  └─ "This model uses 4.8 GB of your server's 256 GB RAM.
      That includes the model weights (4.4 GB) plus the KV cache
      for tracking conversation context (0.4 GB at 4096 tokens)."

Quality: ★★★☆☆ ⓘ
  └─ "Quality 3/5: Good for most tasks. Will follow instructions
      well and write decent code. Won't match GPT-4 on complex
      reasoning, but it's running on YOUR hardware, uncensored,
      and nobody sees your prompts."
```

### E5.6 — Files to Create/Modify

| Action | File | What |
|---|---|---|
| Create | `core/.../knowledge/Article.kt` | Article data classes |
| Create | `core/.../knowledge/KnowledgeBase.kt` | Article index + search |
| Create | `core/src/main/resources/llmwizard/knowledge/` | Directory of .md articles |
| Create | `core/.../knowledge/ContextualHelp.kt` | Screen → article mapping |
| Create | `core/.../knowledge/Explainer.kt` | Post-action explanation generator |
| Create | `app/.../knowledge/LearnScreen.kt` | Knowledge base browser |
| Create | `app/.../knowledge/ArticleScreen.kt` | Article reader (Markdown) |
| Create | `app/.../components/HelpFab.kt` | Contextual help FAB |
| Create | `app/.../components/Tooltip.kt` | Inline tooltip composable |
| Modify | `app/.../Navigation.kt` | Add learn/article routes |

---

## 7 — UI: The "Tabs" Layout

The main app navigation becomes a bottom nav with these destinations:

```
┌─────────────────────────────────────────────┐
│                  LLMWizard                   │
├─────────────────────────────────────────────┤
│                                             │
│          (current screen content)            │
│                                             │
├──────┬──────┬──────┬──────┬──────┬──────────┤
│ Home │Models│Train │Agents│ Learn│  Server  │
│  🏠  │  🤖  │  🧠  │  ⚡  │  📚  │   🖥️    │
└──────┴──────┴──────┴──────┴──────┴──────────┘
```

| Tab | What's There |
|---|---|
| **Home** | Server status, quick actions, recent activity, recommendations |
| **Models** | Browse catalog, installed models, model configs, benchmarks |
| **Train** | Datasets, training jobs, training history, adapter library |
| **Agents** | Agent sessions, profiles, Aider/Open Interpreter management |
| **Learn** | Knowledge base, articles by category, search, bookmarks |
| **Server** | Server connection, hardware info, disk/memory usage, Ollama status, logs |

---

## 8 — Server-Side Directory Layout

Everything LLMWizard manages on the server lives under `/opt/llmwizard/`:

```
/opt/llmwizard/
├── state/                  # Existing: manifest.json, job state
├── cache/                  # Existing: downloaded bundles
├── training/
│   ├── venv/              # Python virtualenv for training
│   ├── datasets/          # Uploaded training datasets
│   │   └── {id}/
│   │       ├── meta.json
│   │       └── data.jsonl
│   ├── jobs/              # Training job directories
│   │   └── {id}/
│   │       ├── config.json
│   │       ├── train.py
│   │       ├── progress.json
│   │       ├── log.txt
│   │       ├── output/    # Checkpoints + final adapter
│   │       └── merged/    # Merged GGUF output
│   └── adapters/          # Completed LoRA adapters
│       └── {name}/
├── agents/
│   ├── profiles/          # Agent profile configs
│   └── sessions/          # Session logs
├── configs/
│   └── modelfiles/        # Generated Modelfiles
└── knowledge/
    └── articles/          # Cached knowledge base articles
```

### 8.1 — Manifest Extensions

The existing `Manifest` tracks what recipes created. Extend it for training and agents:

```kotlin
// New Created kinds:
// "training-venv"     → /opt/llmwizard/training/venv
// "dataset"           → dataset ID
// "training-job"      → job ID
// "adapter"           → adapter name
// "custom-model"      → Ollama model tag
// "agent-profile"     → profile ID
// "agent-tool"        → aider or open-interpreter install
```

---

## 9 — Implementation Order & Dependencies

```
E1 (Models)  ──────────────────────────►  can start immediately
  E1.1 catalog entries                    no code deps
  E1.2 schema extension                  modify Catalog.kt
  E1.3 recommender filter                modify Recommender.kt
  E1.4 model browser UI                  after E1.1–E1.3
  E1.5 explanations                      after E1.3
  E1.6 eval gate                         after E1.1

E2 (Config)  ──────────────────────────►  after E1.2
  E2.1 ModelConfig                        uses new catalog fields
  E2.2 configure recipe                   needs ModelConfig
  E2.3 context advisor                    extends Recommender
  E2.4 quant swap                         uses catalog
  E2.5 benchmark                          standalone

E3 (Training) ─────────────────────────►  after E2.1
  E3.2 training stack install             new recipe, standalone
  E3.3 dataset management                 standalone
  E3.4 job config                         uses ModelConfig concepts
  E3.5 training execution                 depends on E3.2–E3.4
  E3.6 script template                    part of E3.5
  E3.7 time estimator                     uses HardwareProfile
  E3.8 post-training pipeline             depends on E3.5

E4 (Agents)  ──────────────────────────►  after E1 (needs model selection)
  E4.1 Aider install                      new recipe, needs Ollama
  E4.2 Open Interpreter install           new recipe, needs Ollama
  E4.3 session manager                    standalone
  E4.4 custom profiles                    after E4.3

E5 (Knowledge) ────────────────────────►  can start immediately
  E5.1 article schema                     standalone
  E5.2 write articles                     standalone (content work)
  E5.3 contextual help                    after all screens exist
  E5.4 post-action explainers             after recipes run
  E5.5 inline tooltips                    after all screens exist
```

**Recommended build sequence:**

1. **E1.1 + E1.2 + E5.1** — Catalog entries + schema + article schema (data-only, low risk)
2. **E1.3 + E1.5** — Recommender filter + explanations (core logic)
3. **E2.1 + E2.2 + E2.5** — Model config + recipe + benchmark (first new user feature)
4. **E5.2** — Write the articles (can happen in parallel with everything)
5. **E1.4 + E2.3 + E2.4** — Model browser UI + context advisor + quant swap
6. **E3.2 + E3.3** — Training stack install + dataset management
7. **E3.4–E3.8** — Training pipeline (the big one)
8. **E4.1–E4.4** — Agent framework integration
9. **E5.3–E5.5** — Contextual help + explainers + tooltips (polish pass)
10. **E1.7** — Eval gate (final validation)

---

## 10 — Testing Strategy

### 10.1 — Core Tests (JVM)

Every new data class and algorithm gets unit tests in `:core`:

```
core/src/test/kotlin/dev/dreamz/llmwizard/core/
├── catalog/
│   ├── CatalogFilterTest.kt        # Tag/uncensored filtering
│   └── RecommenderFilterTest.kt    # ModelFilter integration
├── config/
│   ├── ModelConfigTest.kt          # Modelfile generation
│   └── ContextAdvisorTest.kt       # KV cache estimation
├── training/
│   ├── TrainingEstimatorTest.kt    # Time estimates
│   ├── TrainingScriptGenTest.kt    # Script generation
│   ├── DatasetValidatorTest.kt     # Format validation
│   └── ProgressParserTest.kt       # progress.json parsing
├── agents/
│   ├── TmuxManagerTest.kt          # Command generation
│   └── AgentProfileTest.kt         # Config generation
└── knowledge/
    ├── KnowledgeBaseTest.kt        # Article index + search
    └── ExplainerTest.kt            # Post-action explanations
```

### 10.2 — Integration Tests

- LXD containers (existing infrastructure) for recipe testing
- Smoke test: install Ollama → pull model → configure → benchmark → run agent
- Training smoke: install stack → upload 10-sample dataset → train 1 epoch → verify output

### 10.3 — Eval Set Extension

Extend the existing eval set for new tool-calling models:

```
eval/
├── existing cases...
├── uncensored-models/     # Tool accuracy on dolphin, nous-hermes, etc.
├── coding-models/         # Tool accuracy on coding models
└── agent-profiles/        # Does the model follow custom system prompts?
```

---

## 11 — New File Inventory (Complete)

Total new files: **~45 Kotlin source files + 30 Markdown articles + 5 recipe JSONs + 1 Python template**

### Core Module (`:core`)

```
core/src/main/kotlin/dev/dreamz/llmwizard/core/
├── catalog/
│   └── ModelFilter.kt                        # NEW
├── config/
│   ├── ModelConfig.kt                        # NEW
│   ├── ModelfileGenerator.kt                 # NEW
│   └── ContextAdvisor.kt                     # NEW
├── benchmark/
│   ├── Benchmark.kt                          # NEW
│   └── BenchmarkResult.kt                    # NEW
├── training/
│   ├── TrainingJob.kt                        # NEW
│   ├── TrainingDataset.kt                    # NEW
│   ├── TrainingEstimator.kt                  # NEW
│   ├── TrainingScriptGen.kt                  # NEW
│   ├── TrainingMonitor.kt                    # NEW
│   ├── DatasetValidator.kt                   # NEW
│   ├── ProgressParser.kt                     # NEW
│   └── AdapterMerger.kt                      # NEW
├── agents/
│   ├── AgentSession.kt                       # NEW
│   ├── AgentProfile.kt                       # NEW
│   ├── TmuxManager.kt                        # NEW
│   └── AgentConfigGen.kt                     # NEW
└── knowledge/
    ├── Article.kt                            # NEW
    ├── KnowledgeBase.kt                      # NEW
    ├── ContextualHelp.kt                     # NEW
    └── Explainer.kt                          # NEW

core/src/main/resources/llmwizard/
├── training/
│   └── train_template.py                     # NEW
└── knowledge/
    └── articles/                             # NEW (30+ .md files)
```

### App Module (`:app`)

```
app/src/main/kotlin/dev/dreamz/llmwizard/app/
├── models/
│   ├── ModelBrowserScreen.kt                 # NEW
│   ├── ModelCard.kt                          # NEW
│   ├── ConfigureModelScreen.kt               # NEW
│   └── BenchmarkScreen.kt                    # NEW
├── training/
│   ├── TrainingScreen.kt                     # NEW
│   ├── DatasetScreen.kt                      # NEW
│   ├── TrainingProgressScreen.kt             # NEW
│   ├── TrainingConfigScreen.kt               # NEW
│   └── AdapterLibraryScreen.kt               # NEW
├── agents/
│   ├── AgentHubScreen.kt                     # NEW
│   ├── AgentTerminalScreen.kt                # NEW
│   └── AgentProfileScreen.kt                 # NEW
├── knowledge/
│   ├── LearnScreen.kt                        # NEW
│   └── ArticleScreen.kt                      # NEW
└── components/
    ├── HelpFab.kt                            # NEW
    └── Tooltip.kt                            # NEW
```

### Recipes

```
recipes/
├── model-configure.json                      # NEW
├── training-stack-install.json               # NEW
├── training-cleanup.json                     # NEW
├── aider-install.json                        # NEW
└── open-interpreter-install.json             # NEW
```

### Modified Files

```
catalog/catalog.json                          # MODIFY: add 15-20 models
core/.../catalog/Catalog.kt                   # MODIFY: new fields
core/.../catalog/Recommender.kt               # MODIFY: filter + explanations
core/.../recipe/Manifest.kt                   # MODIFY: new created kinds
app/.../Navigation.kt                         # MODIFY: new routes
app/.../HomeScreens.kt                        # MODIFY: bottom nav
```

---

## 12 — Hardware Reality Check: Your DL380p

Just so everything is grounded in what your box can actually do:

| Capability | What You Can Do | What You Can't (Yet) |
|---|---|---|
| **Run uncensored 7B models** | ~19 tok/s, comfortable speed | — |
| **Run uncensored 34B models** | ~4 tok/s, usable but slow | — |
| **Run 70B models** | ~2 tok/s, painful but works | Real-time conversation |
| **Run MoE 35B-A3B** | ~8.5 tok/s, measured | — |
| **QLoRA train 7B** | 8–24 hours per 1000 samples | Fast iteration |
| **QLoRA train 13B** | 16–48 hours per 1000 samples | Fast iteration |
| **Run Aider with 7B** | Works, moderate code quality | Complex refactoring |
| **Run Open Interpreter** | Works with 7B+ models | — |
| **Multiple models loaded** | Yes, 256 GB is a lot of room | More than ~5 large models |
| **Add a GPU later** | RTX 3090 = 50x training speed | — |

**The single best upgrade you could make:** An RTX 3090 (24 GB VRAM, ~$400 used). It would turn 14-hour training jobs into 18-minute ones and push inference speed on 7B models to 80+ tok/s. But the DL380p Gen8 PCIe slots are Gen3 x16 — the 3090 works but runs at Gen3 bandwidth. Still a massive improvement over CPU-only.

---

## 13 — What This Plan Does NOT Cover

Keeping it honest about what's outside scope:

1. **GPU driver installation** — Existing SPEC.md fence. If you add a GPU, that's a separate recipe.
2. **Docker/container deployment** — SPEC.md fence. Ollama runs natively.
3. **Multi-node distributed training** — Your box is one machine. One machine is fine.
4. **Cloud API fallback** — This is a LOCAL AI tool. No phoning home.
5. **iOS app** — Android only, as designed.
6. **Model creation from scratch** — Pre-training a new model needs a datacenter. Fine-tuning existing models is the practical path.
7. **Web UI** — The phone app IS the UI. The server is headless.

---

## 14 — Summary

What you end up with after executing this plan:

```
LLMWizard: Your local AI command center

  📱 Phone app that manages everything over SSH
  🤖 35+ models in the catalog, including uncensored/abliterated
  🎯 Smart recommendations based on YOUR actual hardware
  ⚙️  One-tap model configuration (system prompt, temperature, context)
  📊 Real benchmarks, not guesses
  🧠 QLoRA fine-tuning from your phone (with honest time estimates)
  ⚡ Aider + Open Interpreter integration
  📚 30+ educational articles that actually explain what's going on
  ❓ Contextual help on every screen
  🔒 Everything local, nothing leaves your network
  ♻️  Every action is idempotent, resumable, and undoable
```

You asked for the most badass in-depth LLM configuration tool there is. This is the plan. It builds on 75 source files of solid Kotlin that already work, extends every major subsystem, and adds four entirely new capabilities — without turning the app into a PhD thesis to use.

Let's fucking build it.
