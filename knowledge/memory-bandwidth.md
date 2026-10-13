---
id: memory-bandwidth
title: Memory bandwidth: the real bottleneck for local AI
category: HARDWARE
difficulty: INTERMEDIATE
tags: cpu, bandwidth, speed, xeon, hardware
related: dense-vs-moe
summary: Tokens per second on a processor is roughly memory bandwidth divided by the bytes each word reads. That one rule explains almost every speed you will see.
---
The rule of thumb LLMWizard uses for processors:

> tokens per second ≈ 0.5 × memory bandwidth ÷ bytes of weights read per word

The 0.5 is a measured fudge factor: real code never reaches the bus's theoretical peak.

**What this means in practice**

- **More cores stop helping early.** On the DL380p, speed stopped improving at about 5 cores per
  model, because memory is the limit and not arithmetic. Twenty cores did not make one model
  four times faster.
- **Memory channels matter more than clock speed.** A quad-channel server from 2013 keeps up with
  a dual-channel desktop from 2020.
- **Quantization is a speed knob.** Q4 reads about half the bytes of Q8, so it runs about twice as fast.
- **Small models beat the rule.** A model that fits in the CPU caches runs faster than the
  formula predicts. qwen3:0.6b was measured at 41–45 tokens/s on the DL380p.
- **No AVX is a big penalty.** A Hyper-V VM with "processor compatibility mode" switched on hides
  AVX from the guest. The same model measured 6.3 instead of 19.8 tokens/s. The hardware scan
  calls this out.

**Why the app shows estimates first.** Before anything is installed, speed is a class
("comfortable", "slow") with a range. After the benchmark, the measured number replaces it. The
gap between the two is shown so you can see how good the guess was.
