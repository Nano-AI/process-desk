# Choosing a Model

This project runs against three providers behind one seam — `dummy`, `ollama`, `gemini` —
selected by `AI_PROVIDER`. This document is about which model goes in the `ollama` slot, and
why that slot is the one that matters.

## The constraint that decides it

Not quality. **Data residency.**

Free-tier AI Studio terms let Google use submitted input for product improvement. That makes
a free-tier Gemini key usable for `assets/loan-recommendation.dmn` — a public file from the
Drools test suite — and unusable for any real production decision logic, permanently, at any
quality level.

So the two providers are not competing:

| | Gemini flash-lite | Local (Ollama) |
|---|---|---|
| Real decision logic | **never** | yes |
| Fixture files | yes | yes |
| Quota | 500/day, 15 RPM | none |
| Latency | ~1–2s | 15–40s |
| Reasoning quality | higher | lower |

Gemini is the development harness: fast iteration on fixtures. Local is the deliverable.
A local model does not need to beat flash-lite — it needs to be good enough on a task
flash-lite is not allowed to do.

## The hardware

Two machines have been used. The M4 is current; the Ryzen numbers are measured and kept
because they are the only real measurements so far.

| | MacBook Air M4 (current) | Ryzen AI 7 PRO 350 |
|---|---|---|
| Memory | 24 GB unified | 32 GB |
| GPU | 10-core (4P+6E) | Radeon 860M iGPU |
| Bandwidth | ~120 GB/s (spec) | lower |
| `ornith:9b` | **16.6 tok/s** | **~16 tok/s**, ~10s per edit |

The two machines measure the same on `ornith:9b`, which is a useful calibration: the Ryzen
figures in this document transfer.

Weights live on an external USB SSD (`OLLAMA_MODELS=/Volumes/Extreme SSD/OLLAMA MODELS`),
because the internal disk sits at 93% full. This costs cold-load time only — 19.5s for
`gpt-oss:20b` versus 7.5s for the smaller `ornith:9b` — and nothing afterwards, since the
weights are resident in unified memory once loaded. Generation speed is identical either way.

Generation is memory-bandwidth bound, not compute bound. Tokens per second is roughly
`bandwidth ÷ active weight bytes`, which is the whole basis of the table below — and the
reason the next section exists.

## Parameter count is the wrong axis

The naive reading of "bandwidth ÷ weight bytes" is that bigger is always slower, so pick the
largest model that clears a latency bar. That was right when every model was dense. It is not
right now, and the difference decides this choice.

A mixture-of-experts model stores 20–30B parameters but activates only 3–4B per token. It
reads 30B-scale knowledge off disk once, then generates at 3B speed. For an agentic loop —
many sequential turns, each emitting a short tool call — that is exactly the shape needed.

Measured on the M4, at Q4_K_M (~0.56 bytes/param), ~120 GB/s. **Bold rows are measured**; the
rest are `bandwidth ÷ active bytes` arithmetic:

| Model | Q4 size | Active | tok/s | prefill |
|---|---|---|---|---|
| **9b dense (`ornith`)** | 5.6 GB | 9B | **16.6** | **110 tok/s** |
| 14b dense (`qwen3:14b`) | 9.3 GB | 14B | ~11 | — |
| **20b MoE (`gpt-oss`)** | 13 GB | 3.6B | **21.6** | **126 tok/s** |
| 30b MoE (A3B) | ~17 GB | 3B | ~25 | — |
| 27b dense | ~16 GB | 27B | ~6 | — |

**The 20B MoE is faster than the 9B dense while holding 2.2× the parameters.** That is the
whole argument, and it is now measured rather than asserted. The dense ceiling on 24 GB is
14B; past that, per-call latency across a loop puts one request over a minute.

**`gpt-oss:20b` is the pick.** 13 GB fits with room, and it was trained for tool calling
rather than adapted to it. 30B-A3B at ~17 GB would need `iogpu.wired_limit_pct` raised and is
only worth trying if 20b fails the benchmark.

### Prefill, not generation, is the loop's cost

The first estimate of this document put a 6-turn loop at ~15s by counting generated tokens
only. That was wrong. At ~126 tok/s, a 1,200-token first turn — system prompt, tool schemas,
and the opening projection — costs ~10s before a single token comes out.

Realistically, with KV cache reuse across turns so only the delta re-prefills:

| | `gpt-oss:20b` | `ornith:9b` |
|---|---|---|
| Turn 1 (1,200 tok prefill + 80 gen) | ~13s | ~16s |
| Turns 2–6 (~150 tok delta + 80 gen) | ~5s each | ~6s each |
| **6-turn total** | **~38s** | **~47s** |

Two consequences. Keep the system prompt and tool schemas small, because they are paid for on
every cold turn. And **reuse the session** rather than replaying history — replaying a 6-turn
conversation from scratch each call would re-prefill everything and roughly triple these
numbers.

## Why the loop favours local

The tool-loop architecture (see `03-implementation-plan.md`) costs N round trips per request.
That reads as a cost against Gemini and a wash locally, but it is better than a wash:

- **Quota.** 500/day ÷ ~6 calls ≈ 80 conversations/day on flash-lite. Local is unbounded, so
  the loop's main cost does not exist there.
- **Constrained decoding.** Ollama enforces the tool schema at the decoder. The model cannot
  emit malformed JSON or invent a column name — it can only choose wrongly among legal
  options. That erases most of the gap small models have against large ones on structured
  output, which is most of what a turn in this loop is.
- **Latency hides.** 30s of streamed tool calls ("reading Affordability Category… checking
  for gaps…") reads better than 10s of silence. The loop is its own progress bar.

## What to measure

Parameter count is a proxy for the thing that actually decides this, which is loop pathology:
calling the wrong tool, forgetting a previous result, repeating a call, or never terminating.
That does not track parameter count cleanly, so it has to be measured.

Twenty fixture requests against `loan-recommendation.dmn`, four metrics:

| Metric | Why |
|---|---|
| Gate pass rate | the gates already exist and are the definition of correct |
| Turns to completion | loop cost, and the quota math for Gemini |
| Wrong-tool rate | the failure small models actually have |
| Termination failures | hit the iteration cap without concluding — the worst one, because it is silent |

Run across `ornith:9b`, `gpt-oss:20b`, a 30B-A3B, and flash-lite. The resulting table answers
the model question with evidence rather than estimates, and it is the only part of this
document that will not be a guess.

```bash
./gradlew bench -Pllm=gpt-oss:20b
./gradlew bench -Pllm=ornith:9b -Pruns=2
```

Twenty requests, scored by what each one is *for*: refusing is the right answer to "add a column
for postcode" and the wrong answer to "change the DTI". Scoring every request as "did it produce
a proposal" would reward a model that edits indiscriminately and punish one that knows its
limits.

The property is `-Pllm` and not `-Pmodel`, because `model` is a reserved Gradle Project property.
`-Pmodel=` is silently shadowed and the model name arrives as the Project's own toString, so
every request fails identically and the report reads like a finding about the model rather than
a broken invocation. It cost a full benchmark run to notice, and the benchmark now makes one
real call before spending twenty.

## Results

Three local models, 20 requests each, against the current build.

| | `gpt-oss:20b` | `ornith:9b` | `qwen3:14b` |
|---|---|---|---|
| Type | MoE, 3.6B active | dense | dense |
| **Score** | **17/20 (85%)** | 15/20 (75%) | 14/20 (70%) |
| EDIT | **8/10** | 6/10 | 4/10 |
| QUESTION | 4/4 | 4/4 | 4/4 |
| IMPOSSIBLE | 5/6 | 5/6 | **6/6** |
| Turns, median | **5** | 6 | 6 |
| Latency, median | **49s** | 105s | 72s |
| Hit the turn cap | **1** | 4 | 1 |
| Unknown tool calls | **0** | **0** | **0** |
| Wall clock | 62 min | 37 min | 22 min |

**`gpt-oss:20b` wins, and the MoE argument holds.** It is the largest model here and the fastest
per request — 49s median against 105s for a model less than half its size — because it needs
fewer turns to get there, and each turn generates at MoE speed.

**Nobody invented a tool. Not once, across sixty requests.** The failure mode small models were
supposed to have on an agentic loop — hallucinating tools, losing track of prior results — did not
appear at all. Constrained decoding does the work: the schema is enforced at the decoder, so the
model can only choose wrongly among legal options, never emit an illegal one.

**Every model answered every question correctly.** 12/12 across the three. Reading a table and
explaining it is not where the difficulty is.

### Where they actually differ

All the separation is in EDIT, and it is one skill: **getting a range boundary exactly right.**

- `gpt-oss:20b` — 2 misses, both boundary: one left a hole exactly at `0.15`, one overlapped.
- `qwen3:14b` — 4 of its 6 misses are boundary errors, each caught by the coverage gate.
- `ornith:9b` — 4 misses, and **4 turn-cap hits against 1 for the other two**. Its problem is not
  precision but termination: it goes round again rather than concluding.

Two of `qwen3:14b`'s misses are a different and more interesting failure. Asked to change a risk
score, it replied *"I can't change what this decision looks at, only the values it compares
against"* — refusing a request that is exactly what it is for, in wording lifted from the prompt's
own example of how to decline. It has learned the refusal sentence rather than the capability.
That is a prompt-design failure, not a model-capability one, and it is fixable.

Note also `qwen3:14b` scoring **6/6 on IMPOSSIBLE** — the best of the three — while scoring worst
on EDIT. A model that refuses more is better at refusing. That is exactly why the benchmark scores
by category, and why a single overall number would have hidden it.

### What it cost to learn

The gate caught every one of the boundary errors. Not one wrong edit was offered to a user; every
EDIT miss above is a proposal that was *withheld*. The measured failure rate is a rate of refusals,
not of damage.

## Earlier results, kept for the record

`gpt-oss:20b`, 20 requests, 1h36m of wall clock:

| | |
|---|---|
| Score | **16/20** |
| By kind | EDIT 8/10 · QUESTION 4/4 · IMPOSSIBLE 4/6 |
| Turns | median 5, max 12 |
| Latency | median 44s, max 888s |
| Unknown tool calls | **0** |
| Hit the turn cap | 1 |
| Unreachable (timeout) | 2 |

**Zero unknown tool calls across twenty requests** is the number that matters most. The failure
mode small models were supposed to have here — inventing tools, losing track of what a previous
call returned — did not appear once. What did appear is slowness: a median of 44 seconds, and one
request that took nearly fifteen minutes before timing out.

Two things about this table are worth more than the table.

**The first score it printed was 18/20, and that was wrong.** `IMPOSSIBLE` was scored as "produced
no proposal", and a request that times out produces no proposal — so two crashed requests were
counted as the assistant correctly declining to do the impossible. A benchmark that credits a
crash as a pass is worse than no benchmark, because it reads as evidence. Transport failures are
now tracked separately, excluded from the score, and printed as their own line.

**It found a missing capability.** `rename the Reserves Months decision to Cash Reserves` came back
as *"I can't rename the decision"* — true at the time, and it should not have been. The editor has
`renameDecision`, and the one-shot path could reach it; the tool loop simply never exposed it. A
capability had been quietly lost in the rewrite and nothing else had noticed. That is exactly what
a benchmark is for, and it is the only reason the gap was found.

The run also predates `add_rule`, `calculate` and the repeated-call cutoff, so the `add a criteria`
miss and some of the timeouts are already addressed. These numbers are a floor, not a verdict, and
the comparison against `ornith:9b` has not been run.

## Status

- `ornith:9b` — installed, measured on both machines (16.6 tok/s on M4)
- `gpt-oss:20b` — installed, measured (21.6 tok/s)
- Also on hand, untested: `qwen3:14b`, `qwen3.5:9b`, `gemma4:12b`, `qwen2.5-coder:7b`
- `gpt-oss:120b` — present but unusable here; 65 GB against 24 GB of unified memory
- Tool loop — landed, and `gpt-oss:20b` drives it end to end (`09-tool-loop.md`)
- Benchmark — not built, now unblocked

Models live on the external SSD. `OLLAMA_MODELS` is set via `launchctl setenv`, which **does
not survive a reboot** — after one, ollama comes up seeing an empty model list until it is set
again. A LaunchAgent would make it permanent.
