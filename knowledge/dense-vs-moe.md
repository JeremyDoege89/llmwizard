---
id: dense-vs-moe
title: Dense vs. mixture-of-experts: why MoE models fly on a processor
category: MODELS
difficulty: BEGINNER
tags: moe, cpu, speed, models
related: memory-bandwidth, context-length
summary: A mixture-of-experts model only reads a slice of its weights for each word, which is why a 35B MoE can beat a 27B dense model by seven times on the same machine.
---
To write each word, a model does arithmetic with its weights. On a processor the arithmetic is
cheap. **Reading the weights out of memory is the slow part**, so speed comes down to how many
bytes of weights one word touches.

**Dense** models touch every weight for every word. A 27B dense model at Q4 is about 18 GB, so
each word means streaming roughly 18 GB through the memory bus.

**Mixture-of-experts (MoE)** models are split into many small "experts", and a router picks a
few of them for each word. Qwen3.6 35B-A3B holds 35B parameters but only reads about 3B per word.
The "A3B" in the name means "about 3B active".

Measured on a DL380p (two Xeon E5-2680 v2, DDR3), October 2026:

| Model | Kind | Size on disk | Measured speed |
|---|---|---|---|
| qwen3.6:35b-a3b | MoE, ~3B active | 22.6 GB | 7.1–8.5 tokens/s |
| qwen3.6:27b | dense | 17.8 GB | 1.2 tokens/s |

The MoE is bigger on disk and still about seven times faster, because each word reads a fraction
of it.

The catch: the whole MoE still has to **fit in memory**, because the router can pick any expert
for the next word. MoE trades memory, which old servers have plenty of, for speed. On a 256 GB box
that is a very good trade.

In the model browser, MoE models show "reads only about xB parameters per word". The speed
estimate already accounts for it.
