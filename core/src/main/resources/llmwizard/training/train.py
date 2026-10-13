#!/usr/bin/env python3
"""llmwizard fine-tuning job (BUILD_PLAN E3.5/E3.6).

This file is the same for every job. Everything job-specific is read from config.json next to
it, so nothing typed into the app is ever executed as code. Stages, each safe to re-run:

  1. download  - the base model's original weights from Hugging Face (resumes)
  2. train     - LoRA (PEFT) with Transformers' Trainer on the processor; loss on the replies
                 only; checkpoints every saveSteps,
                 resumes from the newest checkpoint after a stop or reboot
  3. merge     - the adapter is merged into the weights (Ollama 0.40 refuses separate LoRA
                 adapters: "LoRA adapters are no longer supported")
  4. convert   - llama.cpp's convert_hf_to_gguf.py, then llama-quantize to the chosen size
  5. register  - uploads the GGUF to Ollama's API and creates the model

progress.json is rewritten after every step and stage. Touch a file named "cancel" in the job
folder to stop after the current step (a checkpoint is saved first).
"""
import hashlib, json, os, subprocess, sys, time, traceback, urllib.error, urllib.request
from pathlib import Path

JOB = Path(__file__).resolve().parent
CFG = json.loads((JOB / "config.json").read_text())
DATA = Path(os.environ.get("LLMWIZARD_DATA", str(Path.home() / ".local/share/llmwizard")))
TRAINING = DATA / "training"
STARTED = time.time()
state = {"stage": "STARTING", "currentStep": 0, "totalSteps": 0, "currentEpoch": 0.0, "totalEpochs": CFG["epochs"], "elapsedSeconds": 0}


def progress(**kw):
    state.update(kw)
    state["elapsedSeconds"] = int(time.time() - STARTED)
    state["updatedAt"] = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
    tmp = JOB / "progress.json.tmp"
    tmp.write_text(json.dumps(state))
    os.replace(tmp, JOB / "progress.json")


def log(msg):
    print(time.strftime("%H:%M:%S"), msg, flush=True)


def cancelled():
    return (JOB / "cancel").exists()


def download():
    from huggingface_hub import snapshot_download
    progress(stage="DOWNLOADING", message="Downloading %s from Hugging Face" % CFG["hfRepo"])
    target = TRAINING / "hf" / CFG["hfRepo"].replace("/", "--")
    log("downloading %s to %s" % (CFG["hfRepo"], target))
    path = snapshot_download(CFG["hfRepo"], local_dir=str(target),
                             allow_patterns=["*.json", "*.safetensors", "*.model", "*.txt", "*.jinja", "tokenizer*"])
    return Path(path)


def load_dataset_rows():
    from datasets import load_dataset
    path = TRAINING / "datasets" / CFG["dataset"] / "data.jsonl"
    ds = load_dataset("json", data_files=str(path), split="train")
    log("dataset: %d samples, columns %s" % (len(ds), ds.column_names))
    return ds


def tokenize_rows(ds, tok, max_len):
    """Chat rows: loss only on the final assistant reply (the prompt is masked with -100).
    Text rows: loss on everything. Rows are cut at max_len tokens."""
    def one(row):
        msgs = row.get("messages")
        if msgs:
            full = tok.apply_chat_template(msgs, tokenize=False)
            prompt = tok.apply_chat_template(msgs[:-1], tokenize=False, add_generation_prompt=True) if msgs[-1]["role"] == "assistant" else ""
            ids = tok(full, add_special_tokens=False)["input_ids"][:max_len]
            cut = len(tok(prompt, add_special_tokens=False)["input_ids"]) if prompt and full.startswith(prompt) else 0
            labels = [-100] * min(cut, len(ids)) + ids[cut:]
        else:
            ids = tok(row["text"] + (tok.eos_token or ""), add_special_tokens=False)["input_ids"][:max_len]
            labels = list(ids)
        return {"input_ids": ids, "attention_mask": [1] * len(ids), "labels": labels}
    out = ds.map(one, remove_columns=ds.column_names)
    return out.filter(lambda r: any(l != -100 for l in r["labels"]))


def collate(tok):
    pad = tok.pad_token_id if tok.pad_token_id is not None else (tok.eos_token_id or 0)
    def fn(rows):
        import torch
        n = max(len(r["input_ids"]) for r in rows)
        def padded(key, value):
            return torch.tensor([r[key] + [value] * (n - len(r[key])) for r in rows])
        return {"input_ids": padded("input_ids", pad), "attention_mask": padded("attention_mask", 0), "labels": padded("labels", -100)}
    return fn


def train(base):
    import torch
    from peft import LoraConfig, get_peft_model
    from transformers import AutoModelForCausalLM, AutoTokenizer, Trainer, TrainerCallback, TrainingArguments
    from transformers.trainer_utils import get_last_checkpoint

    adapter = JOB / "output" / "adapter"
    if (adapter / "adapter_model.safetensors").exists():
        log("adapter already trained, skipping training")
        return adapter
    threads = CFG.get("threads") or max(1, (os.cpu_count() or 2) // 2)
    torch.set_num_threads(threads)
    log("torch %s, %d threads, cpu capability %s" % (torch.__version__, threads, torch.backends.cpu.get_cpu_capability()))
    progress(stage="TRAINING", message="Loading the base model")
    tok = AutoTokenizer.from_pretrained(str(base))
    if tok.pad_token is None:
        tok.pad_token = tok.eos_token
    model = AutoModelForCausalLM.from_pretrained(str(base), dtype=torch.float32)
    if CFG["gradientCheckpointing"]:
        model.gradient_checkpointing_enable(gradient_checkpointing_kwargs={"use_reentrant": False})
        model.enable_input_require_grads()
    model = get_peft_model(model, LoraConfig(r=CFG["loraRank"], lora_alpha=CFG["loraAlpha"], lora_dropout=CFG["loraDropout"],
                                             target_modules="all-linear", task_type="CAUSAL_LM"))
    model.print_trainable_parameters()
    ds = tokenize_rows(load_dataset_rows(), tok, CFG["maxSeqLength"])
    log("tokenized: %d rows, %d tokens" % (len(ds), sum(len(r) for r in ds["input_ids"])))

    class Progress(TrainerCallback):
        def on_train_begin(self, args, st, control, **kw):
            self.t0, self.first = time.time(), st.global_step
            self.tokens0 = getattr(st, "num_input_tokens_seen", 0) or 0
            progress(stage="TRAINING", message="Training", totalSteps=st.max_steps, currentStep=st.global_step)

        def on_step_end(self, args, st, control, **kw):
            done, total = st.global_step, st.max_steps
            spent = time.time() - self.t0
            per = spent / max(1, done - self.first)
            tokens = getattr(st, "num_input_tokens_seen", 0) or 0
            progress(stage="TRAINING", message=None, currentStep=done, totalSteps=total,
                     currentEpoch=round(st.epoch or 0.0, 3),
                     tokensPerSecond=round((tokens - self.tokens0) / spent, 2) if spent > 0 and tokens else None,
                     estimatedRemainingSeconds=int(per * (total - done)))
            if cancelled():
                log("cancel requested; saving a checkpoint and stopping")
                control.should_save = True
                control.should_training_stop = True

        def on_log(self, args, st, control, logs=None, **kw):
            if logs and "loss" in logs:
                progress(loss=logs.get("loss"), learningRate=logs.get("learning_rate", state.get("learningRate")))
                log("step %d/%d loss %.4f" % (st.global_step, st.max_steps, logs["loss"]))

    out = JOB / "output"
    args = TrainingArguments(
        output_dir=str(out), num_train_epochs=CFG["epochs"],
        per_device_train_batch_size=CFG["batchSize"], gradient_accumulation_steps=CFG["gradientAccumulationSteps"],
        learning_rate=CFG["learningRate"], warmup_steps=CFG["warmupSteps"], weight_decay=CFG["weightDecay"],
        optim=CFG["optimizer"], logging_steps=1, save_steps=CFG["saveSteps"], save_total_limit=2,
        report_to="none", use_cpu=True, bf16=False, dataloader_num_workers=0,
        include_num_input_tokens_seen=True, remove_unused_columns=False,
    )
    trainer = Trainer(model=model, args=args, train_dataset=ds, data_collator=collate(tok), callbacks=[Progress()])
    resume = get_last_checkpoint(str(out)) if out.exists() else None
    if resume:
        log("resuming from " + resume)
    trainer.train(resume_from_checkpoint=resume)
    if cancelled():
        return None
    model.save_pretrained(str(adapter))
    tok.save_pretrained(str(adapter))
    log("adapter saved to %s" % adapter)
    return adapter


def run(cmd):
    log("$ " + " ".join(cmd))
    subprocess.run(cmd, check=True)


def merge(base, adapter):
    """The adapter merged into the base weights, saved as safetensors with the tokenizer."""
    progress(stage="EXPORTING", message="Merging the adapter into the model")
    merged = JOB / "output" / "merged"
    if not (merged / "config.json").exists():
        import torch
        from peft import PeftModel
        from transformers import AutoModelForCausalLM, AutoTokenizer
        log("merging the adapter into the base weights")
        m = AutoModelForCausalLM.from_pretrained(str(base), dtype=torch.float16)
        m = PeftModel.from_pretrained(m, str(adapter)).merge_and_unload()
        m.save_pretrained(str(merged), safe_serialization=True)
        AutoTokenizer.from_pretrained(str(base)).save_pretrained(str(merged))
        for extra in ("generation_config.json", "chat_template.jinja"):
            if (base / extra).exists() and not (merged / extra).exists():
                (merged / extra).write_bytes((base / extra).read_bytes())
        del m
    return merged


def to_gguf(merged):
    """llama.cpp's converter writes F16 (or Q8_0 directly); llama-quantize makes the K-quants.
    Ollama 0.40 imports GGUF as-is: it neither quantizes GGUF nor imports safetensors on Linux
    ("MLX runtime is not available"), so the file Ollama gets is already the final size."""
    progress(stage="EXPORTING", message="Converting for Ollama")
    q = CFG.get("quantize") or "q4_K_M"
    llama = TRAINING / "llama.cpp"
    final = JOB / "output" / ("model-%s.gguf" % q)
    if final.exists():
        return final
    direct = q in ("f16", "q8_0")
    first = final if direct else JOB / "output" / "model-f16.gguf"
    if not first.exists():
        run([sys.executable, str(llama / "convert_hf_to_gguf.py"), "--outtype", q if direct else "f16", "--outfile", str(first), str(merged)])
    if not direct:
        run([str(llama / "bin" / "llama-quantize"), str(first), str(final), q.upper()])
        first.unlink()
    return final


def api(path, body=None, data=None, method=None, timeout=3600):
    req = urllib.request.Request(CFG["ollamaApi"] + path, method=method,
                                 data=data if data is not None else (json.dumps(body).encode() if body is not None else None),
                                 headers={"Content-Type": "application/json"} if body is not None else {})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.read().decode()
    except urllib.error.HTTPError as e:
        detail = e.read().decode(errors="replace")[:300]
        try:
            detail = json.loads(detail).get("error", detail)
        except ValueError:
            pass
        raise RuntimeError("Ollama %s: %s" % (path, detail)) from None


def upload(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    digest = "sha256:" + h.hexdigest()
    try:
        api("/api/blobs/" + digest, method="HEAD")
    except Exception:
        log("uploading %s (%.2f GB) to Ollama" % (path.name, path.stat().st_size / 1e9))
        with open(path, "rb") as f:
            req = urllib.request.Request(CFG["ollamaApi"] + "/api/blobs/" + digest, data=f, method="POST",
                                         headers={"Content-Length": str(path.stat().st_size)})
            urllib.request.urlopen(req, timeout=7200).read()
    return digest


def create(files, quantize):
    body = {"model": CFG["outputName"], "files": files, "stream": False}
    if quantize:
        body["quantize"] = quantize
    # Keep the base model's chat template and system prompt, so the result talks the same way.
    try:
        show = json.loads(api("/api/show", {"model": CFG["baseModel"]}))
        if show.get("template"):
            body["template"] = show["template"]
        if show.get("system"):
            body["system"] = show["system"]
    except Exception as e:
        log("could not read the base model's template (%s); Ollama will use the model's own" % e)
    log("creating %s (%s)" % (CFG["outputName"], quantize or "no quantization"))
    log(api("/api/create", body, timeout=7200).strip()[-300:])
    api("/api/show", {"model": CFG["outputName"]})


def register(gguf):
    progress(stage="REGISTERING", message="Adding %s to Ollama" % CFG["outputName"])
    create({gguf.name: upload(gguf)}, None)


def main():
    (JOB / "pid").write_text(str(os.getpid()))
    progress()
    try:
        base = download()
        if cancelled():
            return progress(stage="CANCELLED", message="Stopped before training")
        adapter = train(base)
        if adapter is None:
            return progress(stage="CANCELLED", message="Stopped; a checkpoint was saved and the job can be resumed")
        register(to_gguf(merge(base, adapter)))
        progress(stage="DONE", message="Ready in Ollama as " + CFG["outputName"], ollamaModel=CFG["outputName"], estimatedRemainingSeconds=0)
        log("done")
    except Exception as e:
        traceback.print_exc()
        progress(stage="FAILED", error="%s: %s" % (type(e).__name__, str(e)[:500]))
        sys.exit(1)
    finally:
        try:
            (JOB / "pid").unlink()
        except FileNotFoundError:
            pass


if __name__ == "__main__":
    main()
