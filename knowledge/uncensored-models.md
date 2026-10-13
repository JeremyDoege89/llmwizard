---
id: uncensored-models
title: Uncensored models: what they are and aren't
category: SECURITY
difficulty: BEGINNER
tags: uncensored, abliterated, dolphin, refusals, models
related: dense-vs-moe
summary: Uncensored models have had their refusal training removed or never received it. They answer more, but they are not smarter, not more accurate, and not more private than any other local model.
---
Most chat models are trained to refuse some requests. "Uncensored" models remove that layer in
one of two ways:

- **Fine-tuned without refusals.** The Dolphin family takes a base model and trains it on
  instruction data with the refusals filtered out. It follows whatever system prompt you give it.
- **Abliterated.** Researchers found that refusing shows up as one direction inside the model's
  activations. Abliteration measures that direction and subtracts it from the weights. Nothing
  is retrained, so the model keeps the original's knowledge and skills. The `huihui_ai/...`
  models in the catalog are made this way from current Qwen and gpt-oss releases.

**What does not change**

- **Accuracy.** An uncensored model hallucinates just as confidently as the original, sometimes
  a bit more. Abliteration can cost a little quality.
- **Knowledge.** It only knows what the base model knew.
- **Privacy.** Every model in LLMWizard runs on your own server. An uncensored one sends nothing
  anywhere, and neither does a regular one.
- **The app's own guardrails.** As the phone assistant, any model only proposes actions. Nothing
  is sent, called or deleted until you tap the card. That holds for uncensored models too.

**Picking one.** Use the "Uncensored" chip in the model browser. On a processor-only server,
prefer the mixture-of-experts options (Qwen3.5 35B-A3B Abliterated, Qwen3 30B-A3B Abliterated):
they are both the smartest and the fastest. The older Dolphin 7B and 8B models are quick to
download but weaker. Dolphin Llama 3 70B is smart, but it manages well under one word a second on
a processor.

Community uploads (anything with a `name/` prefix) come from individuals, not the original lab.
LLMWizard checks that the tag exists and how big it is, not what is inside.
