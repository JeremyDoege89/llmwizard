---
id: context-length
title: Context length: what it is and when to raise it
category: OPTIMIZATION
difficulty: BEGINNER
tags: context, memory, kv cache, num_ctx
related: memory-bandwidth, dense-vs-moe
summary: Context length is how much of the conversation the model can see at once. Raising it costs memory for the KV cache, not speed per word, until the window starts to fill up.
---
A model only "remembers" what fits in its context window, which is counted in tokens (about ¾ of a
word each). Anything older falls off the front.

**What it costs.** For every token in the window, the model keeps a key and a value in memory: the
**KV cache**. Double the window and the cache doubles. The model's weights stay the same size.

**What it does not do.** A bigger window does not make short chats slower. Speed drops once a
conversation actually fills it, because every new word has to look back over more tokens.

**When to raise it**

- Pasting long documents or code files: yes.
- Long role-play or research sessions where early details matter: yes.
- Quick questions: no, the default (8K tokens) is plenty.

**When raising it does nothing.** Each model was trained for a maximum length. Llama 3 models
stop at 8K and the 2023 Wizard Vicuna at 2K. Setting more wastes memory, so the advisor marks
those rows "no benefit".

In **Configure model**, the advisor shows the memory each length would need on your server and
the longest one that fits.
