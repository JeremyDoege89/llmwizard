# Eval set

`commands.jsonl`: one phone command per line with the tool and arguments a correct proposal has
(PLAN.md 5.14). `tool: null` means the right answer is to call no tool.

Argument matching: a plain value must match exactly (case-insensitive, trimmed; `07:00` and
`7:00` are the same time). A value starting with `~` only has to appear inside the proposed
value (case-insensitive), for free text like message bodies. Arguments not listed are not checked.

The eval clock is fixed at Friday 2026-10-09 10:00, so "tomorrow" is 2026-10-10.

Run: `llmwizard eval-tools <target> --model <tag>`. Results are appended to
`~/.config/llmwizard/eval-results.jsonl`.
