# Running This on a Small Model

The proposal on the table is a 7B model with thinking off, plus retrieval, on the grounds that
nothing larger is needed. This document is what that costs, what it buys, and what has to change
in the harness before it is true.

The short version: the size constraint is right and the reason given for it is wrong. Retrieval
would not have fixed a single failure this project has actually measured. What fixes them is
moving capability out of the prompt and into the harness — which also happens to be what makes a
7B viable, so the same work serves both.

**Target, as of this document.** Ollama only; the hosted provider is no longer where this is
going. Laptops and servers that are short of CPU compute and not short of memory, with no GPU
assumed. That combination decides more here than the parameter count does, and Sections 2, 8 and
the CPU settings table are written against it — including one place where it reverses advice an
earlier draft gave.

**Status.** All twelve items of Section 12 have landed. 202 tests pass, up from 183, and 36 of
them are requests typed the way people type rather than the way developers write test fixtures.
What is still unmeasured is the only thing a test cannot settle: whether a small model driving
this loop gets the edit right. Section 11 is the instrument for that and it needs the slowest
machine in the fleet, not this one.

## 1. What the measurements already say

`08-choosing-a-model.md` benchmarked three local models over twenty requests:

| | `gpt-oss:20b` | `ornith:9b` | `qwen3:14b` |
|---|---|---|---|
| Type | MoE, 3.6B active | dense | dense |
| Score | **17/20** | 15/20 | 14/20 |
| EDIT · QUESTION · IMPOSSIBLE | 8/10 · 4/4 · 5/6 | 6/10 · 4/4 · 5/6 | 4/10 · 4/4 · 6/6 |
| Turns, median | 5 | 6 | 6 |
| Latency, median | **49s** | 105s | 72s |
| Hit the turn cap | 1 | **4** | 1 |
| Unknown tool calls | 0 | 0 | 0 |

Three things in that table decide everything below.

**Every model answered every question correctly. 12/12.** Reading a table and explaining it is
not where the difficulty is, and it is the only place a documentation index could help.

**All the separation is in EDIT, and it is one skill: getting a range boundary exactly right.**
`gpt-oss:20b` left a hole at exactly `0.15`. `qwen3:14b` made four boundary errors. None of those
is a gap in DMN knowledge. The model knew what a range was; it moved one edge and not the other.

**The worst failure is silent.** `ornith:9b` hit the turn cap four times in twenty. A cap hit
produces no proposal, which from outside is indistinguishable from a correct refusal — the first
benchmark run scored two timeouts as the assistant rightly declining, and printed 18/20 for a
16/20 result.

So the honest read of the proposal: retrieval is aimed at the one category that is already
perfect. Dropping from a 3.6B-active MoE to a 7B dense moves along the axis where the loop
already fails, and the model that most resembles the proposed one — the 9B dense — is the one
that hangs.

That is an argument about *which* 7B, not against the constraint. Section 2.

## 2. "7B" should mean seven billion **active** parameters

The deployment target is laptops and servers that are short on CPU compute and not short on
memory. That makes the parameter question sharper rather than softer, because on a CPU the two
halves of a turn are bounded by different things:

- **Generation** is memory-bandwidth bound. Tokens per second is roughly
  `bandwidth ÷ active weight bytes`.
- **Prefill** is compute bound. Tokens per second is roughly
  `usable FLOPS ÷ (2 × active parameters)`.

Both denominators say *active*, and neither says *total*. On a machine with plenty of RAM and
little compute, a model that stores a lot and activates a little is not a compromise — it is the
only shape that gets both.

The strongest measured result in this repo already says so, on a GPU-capable machine:

| Model | Q4 size | Active | tok/s (M4) |
|---|---|---|---|
| `ornith:9b` dense | 5.6 GB | 9B | 16.6 |
| `gpt-oss:20b` MoE | 13 GB | 3.6B | **21.6** |

The 20B model is faster than the 9B while holding 2.2× the parameters. **On CPU that gap widens
rather than closes**, because prefill joins generation in scaling with active parameters, and
prefill is what this loop actually spends its time on. A 9B dense costs 18 GFLOP per prefilled
token; a 3.6B-active MoE costs 7.2. Same conversation, less than half the arithmetic.

Read the constraint as **≤7B active** and it is met by models far better at this task than any
7B dense. Nothing about the proposal's intent is violated. The arithmetic just points at a
different model.

### What a CPU-only turn actually costs

Order-of-magnitude, for a modern laptop CPU at a few hundred usable GFLOPS in llama.cpp and
50–70 GB/s of real memory bandwidth. These are estimates, marked as such, and Section 11 is how
they stop being estimates:

| | prefill tok/s | generation tok/s |
|---|---|---|
| 3.6B active (MoE) | ~40–60 | ~15–25 |
| 4B dense | ~35–55 | ~14–22 |
| 9B dense | ~15–25 | ~6–10 |

Put the current 2,000-token preamble through the 9B row and the first turn spends **80 to 130
seconds prefilling before it emits a token.** That is not a slow model; it is a prompt being
read at CPU speed. Section 8 is therefore no longer a tidiness exercise — on this hardware it is
the difference between a usable tool and a timeout.

## 3. The shortlist

Ollama is the only provider this is aimed at now. Sizes are Ollama's Q4 downloads; "active" is
what both prefill and generation are actually charged for.

| Model | Size | Active | Where it fits |
|---|---|---|---|
| `qwen3.5:35b` (A3B) | 24 GB | ~3B | **A server with RAM and no GPU.** The most capability per unit of CPU compute available at this shape: 35B of knowledge, prefilled and generated as if it were 3B. Memory is the resource you have. |
| `gpt-oss:20b` | 13 GB | 3.6B | **The laptop default, and the control.** 17/20 measured here already. Fits where the 35B will not, and costs about the same compute per token. |
| `qwen3.5:9b` | 6.6 GB | 9B | **The model the proposal asks for.** On hand, never benchmarked. Native tool calling, thinking off with `think:false`. On CPU it is the slowest of these four despite being the second smallest — 9B of arithmetic per prefilled token against 3.6B. |
| `qwen3.5:4b` | 3.4 GB | 4B | **The weak-CPU pick, and the floor test.** If Section 4 works, 4b should still clear QUESTION and single-cell EDIT. It is how you find out whether capability moved into the harness or only moved around. |

The ordering is worth stating plainly, because it is the opposite of the intuitive one: on a
CPU-bound machine with spare memory, **the 24 GB model is faster than the 6.6 GB model.** Size on
disk is a memory question and you are not short of memory. Active parameters are a compute
question and that is what you are short of.

Avoid, with reasons:

- **DeepSeek-R1 7B.** Thinking cannot be turned off. This project already measured that cost:
  25s against 9s for the same answer with reasoning disabled. A model whose reasoning is
  mandatory is the opposite of the proposal.
- **Gemma 3 at any size.** No native tool calling. Gemma 4 has it; Gemma 3 does not.
- **Base rather than instruct variants.** Tool calling lives in the instruct chat template.
- **Any dense model above about 9B.** On CPU a 27B dense costs 54 GFLOP per prefilled token —
  seven times a 3.6B-active MoE — for capability an MoE of the same footprint already has.
- **`qwen2.5-coder:7b`**, which is on hand — coder-tuned, not tool-tuned. Worth one benchmark
  row as a free data point, not a candidate.

Worth exactly one run, then a decision: the BFCL function-calling specialists —
`watt-tool-8B`, `ToolACE-8B` (both Llama-3.1-8B finetunes), `Hammer2.1-7b`. They report
state-of-the-art small-model scores on the Berkeley Function Calling Leaderboard, which is a
benchmark of short calls against many shallow tools. This loop is nine deep tools, long tool
results, and multi-turn self-correction. Those are not the same skill and the transfer is
unproven. They are on Hugging Face rather than in the Ollama library, so each needs a Modelfile
before it can be measured at all.

## 4. Move capability out of the prompt and into the harness

This is the section that matters. Everything else is configuration.

`Prompts.DECISION_TOOLS` is **4,421 characters, roughly 1,105 tokens, 687 words** of prose. Every
paragraph is there because a request failed without it, which is the right way to have written
it — but the mechanism is hope. The prompt asks the model to remember nine rules, in order, on
every turn, under a context window that also holds the conversation.

A 20B MoE mostly complies. A 7B mostly does not, and this project has already caught the exact
failure mode: `qwen3:14b`, asked to change a risk score, replied *"I can't change what this
decision looks at, only the values it compares against"* — refusing a request that is precisely
what it is for, **in wording lifted verbatim from the prompt's own example of how to decline.**
It learned the sentence rather than the capability. Small models copy salient strings out of long
prompts. That is not a bug you fix with more prose.

The rule that follows: **every sentence in the prompt that could be a gate should be a gate.** A
model can ignore an instruction. It cannot ignore a refused tool call.

| In the prompt today | Becomes |
|---|---|
| "Look before you change. Call show_decision before every set_cell." | `set_cell` refuses a decision not shown in this conversation, and returns the table with the refusal. |
| "Call check after changing a boundary." | `set_cell` runs `check` itself and returns the result together with the re-rendered table. |
| "One request can need more than one change. Make all of them before finishing." | `done` refuses while `check` reports a gap or an overlap, and returns the gap. |
| "In done and answer, never mention tools, rule numbers, columns, schemas, tables or DMN." | `done` takes no argument. The summary is built from the deterministic edit log. |
| "Do not do arithmetic yourself. Call calculate…" (109 words) | Moved into `calculate`'s own description, where it is read at the moment the choice is made. Folding numeric-literal arithmetic into `set_cell` was considered and **not** done — see below. |
| "stop and use answer to say plainly that you cannot do it" + an example sentence | `panic` takes a reason **code**. Java writes the sentence. Section 7. |
| "Write conditions in the table's own notation: …" | A notation card attached to the first `show_decision` result. Section 8. |
| "A name can mean two things…" (49 words) | `list_decisions` already says which entries are formulas. Say it once more in the `show_decision` result for a formula, where it is needed, and delete the paragraph. |

Four of these are worth spelling out.

**Auto-check after `set_cell` is the largest single win.** The documented successful path is eight
turns: `list, show, set, check, show, set, check, done`. Half of them exist because the model has
to remember to verify and then re-read what it changed. Fold both into the write:

```
turn 1  list_decisions
turn 2  show_decision Affordability Category  → 1. >0.36  2. [0.33..0.36]  3. <0.33
turn 3  set_cell rule 3  <0.33 → <0.15
        → done, and: nothing covers [0.15..0.33), so those cases get no answer.
          The table now reads: 1. >0.36  2. [0.33..0.36]  3. <0.15
turn 4  set_cell rule 2  [0.33..0.36] → [0.15..0.36]
        → done, all checks pass. 1. >0.36  2. [0.15..0.36]  3. <0.15
turn 5  done
```

Eight turns to five, with the self-correction still intact and no longer optional. At the
measured six seconds a turn on a 9B that is 48s to 30s, and the twelve-turn cap stops being
something a slow model can hit by accident. The cost is a Kogito compile and analyser pass per
write instead of per `check` call, which is cheaper than the model turn it removes — measure it,
but the ordering is not close.

**`done` should take no argument.** It currently asks for a summary, which produces three
problems at once: eighty tokens of schema, the "never mention DMN" paragraph that polices it, and
a live bug where `gpt-oss:20b` double-encoded its own argument and the panel showed
`{"summary": "{\"summary\": \"Added a new category…\"}"}` as the description of a change awaiting
approval. `DecisionToolLoop.finish` already falls back to `String.join(" ", workspace.edits())`.
Promote the fallback to the only path. The edit log is what actually happened; the model's account
of it is a second, unchecked source for the same fact.

**Arithmetic in `to` would be safe to evaluate, and was still not done.** Across the 1,241 DMN
files in `corpora/`, **138 of 11,465 decision-table cells contain an arithmetic operator —
1.20%** — and nearly all of them reference variables rather than literals
(`(Principal*Rate/12)/(1-(1+Rate/12)**-Term)+Fees`). Guarded on *every operand being a numeric
literal*, the false-positive rate would be indistinguishable from zero.

It was left alone because Section 6 solves the same problem more cheaply. `calculate` is not in
the default tool set any more — it loads only when the request mentions arithmetic — so it costs
nothing on the requests that would never have used it, and the reason it exists now lives in its
own description, read at the moment the model is choosing. Adding a second, quieter arithmetic
path inside `set_cell` would buy a turn on a minority of requests in exchange for two places
where a number can be computed, and this project's rule is that there is one way to write a
cell. Worth revisiting only if the benchmark shows conversions costing turns.

**The escalation ladder replaces the silent cap.** At `maxTurns - 2`, inject a tool result: *"Two
turns left. Finish, or call panic."* Four of `ornith:9b`'s twenty requests ended in a cap hit with
no explanation. Every one of those should have been an explicit refusal the benchmark can count
and a user can read.

## 5. The system prompt, rewritten

**Landed.** 1,105 tokens to **122**. This is the whole of it, verbatim:

```
You change decision tables for someone who does not read DMN.

Everything you say about this file comes from what the tools showed you. You
know a great deal about lending; none of it is in this file. An answer that
sounds right and names a rule this file does not have is the worst thing you
can produce, because nobody can tell it is wrong.

Call a tool every turn. Finish with done, answer, or panic.
```

Three deliberate choices.

The hallucination paragraph stays, in full, because it is the one instruction with no gate behind
it — nothing in the harness can tell that a fluent answer about missing documentation and recent
bankruptcies describes a file that mentions neither.

There is **no example refusal sentence anywhere**, which is what `qwen3:14b` copied. There are
no examples at all. The done-versus-answer distinction used to live here, with a worked pair, and
Section 6 made it redundant: the router settles that one turn earlier, and on a question the tool
set does not contain `done` at all. A paragraph teaching a distinction the decoder can no longer
express is ninety tokens of prefill buying nothing.

Nothing describes the tools. Tool descriptions are already prefilled on every turn; describing
them again in prose pays twice for one fact and gives the model two wordings to reconcile.

### The 14B "Explain this file" hang has a specific cause

`num_predict` is **not set anywhere in this codebase** — `OllamaAiProvider` sends `temperature`
and `num_ctx` and nothing else. Ollama's default is unbounded generation. `Prompts.EXPLAIN` asks
for one sentence and at most three bullets, and that is a request, not a limit. A 14B dense model
generates at roughly 11 tok/s, so the 120-second client timeout arrives at about 1,300 tokens —
well within rambling distance for a question as unanchored as "explain this file." The request
then fails as `IllegalStateException` and the panel reports *"I couldn't reach the local model"*
about a model that was answering the whole time. It is the same misdiagnosis
`09-tool-loop.md` records for the 321-second missing-capability hang.

Two fixes, both small, the first of them **landed**:

- ✅ Send `num_predict`, sized to the shape the prompt already specifies. 200 for prose, where
  nothing else bounds the reply, and 512 for anything with a schema, which is already bounded.
  An answer that stops mid-sentence is a visible fault; an answer that never stops is reported
  as a network error. The 120-second client timeout went to 600 in the same change, because on
  a CPU machine it was short enough to manufacture the same misdiagnosis on its own.
- Route "explain this file" through `list_decisions` rather than free prose. It has no target,
  so there is nothing for the model to be accurate about — which is exactly the condition under
  which small models generate confident filler.

## 6. Route before you load

The benchmark's own categories are the argument. QUESTION was 12/12 across three models; every
loss was in EDIT. So do not pay for the write tools on a question — and do not give a 7B the
chance to pick one.

```
request
  │
  ├─ route                    one constrained turn, a two-value enum
  │                           no tool schemas loaded yet: ~55 tokens of prompt
  │
  ├─ QUESTION → 4 tools       list_decisions, show_decision, answer, panic
  ├─ EDIT     → 7 tools       those, plus set_cell, add_rule, done
  └─ UNKNOWN  → every tool    a provider that cannot classify loses nothing
```

Ten tools become seven or four. Fewer legal options is the most reliable thing you can do for a
small model's tool choice, and it costs one cheap turn: a two-value enum under constrained
decoding is the easiest classification a model can be asked to make. `rename_decision` and
`calculate` load only when the request mentions renaming or arithmetic; they are real
capabilities that are rarely the answer, and a rarely-correct tool in the default set is a
standing invitation to a wrong call.

**Landed.** `AiProvider.classify` is a default method returning `UNKNOWN`, so only Ollama pays
for routing and every other provider behaves exactly as before. The reading is recorded on the
`Outcome` and broken out by expected category in the benchmark, which is what will say whether
the extra turn was worth it.

A wrong reading costs one line rather than a failure. When the model asks for a real tool that
was not advertised, the loop widens the set **and dispatches the call anyway** — the tool exists,
and nothing about it was unsafe, only unadvertised.

**That repair does not cover the case it was written for, and finding out why changed the
design.** Constrained decoding is the reason this whole section works, and it is also the reason
the widen-on-demand path can never fire for a withheld tool: Ollama enforces the schema at the
decoder, so a model given seven tools **cannot emit the eighth**. It has no way to tell us the
narrowing was wrong. A Spanish request saying `renombrar` sails past an English word list, and
the model that needed `rename_decision` is simply never able to ask for it.

So the signal had to be something the model *can* say. It panics. A conversation that had tools
withheld treats its **first** panic as a question rather than an answer: it gets every tool and
one more turn, with a tool result saying so. A second panic is taken at face value, which keeps
the repair from becoming a loop.

This is what makes the word lists in `RENAMES` and `ARITHMETIC` acceptable rather than fragile.
They do not have to be right. A miss costs one extra turn, and the turn is only spent on
requests that were going to be refused anyway.

Constrained decoding does the rest, and this project has the strongest possible evidence for it:
**zero unknown tool calls across sixty benchmark requests.** The failure mode small models were
supposed to have on an agentic loop never appeared, because Ollama enforces the schema at the
decoder. The model can only choose wrongly among legal options. Shrinking the legal set is
therefore the entire lever.

## 7. `panic`

```
panic(what, why)

  what   the part of the request you cannot do, in the words the person used
  why    one of:
           NO_SUCH_DECISION      nothing in this file is called that
           NOT_A_TABLE           it is a formula; there are no rules to change
           NEEDS_NEW_COLUMN      the table would need a new column
           NEEDS_NEW_DECISION    this would need a decision that does not exist
           NEEDS_RULE_REMOVAL    a rule would have to be removed
           AMBIGUOUS             two things in this file could be meant
           CHECK_KEEPS_FAILING   the change is made but check will not pass
           UNSURE                none of the above
```

The reason is a **code, not a sentence**, and that is the whole design. `qwen3:14b` refused a
valid request by reciting the prompt's example refusal. A model that emits `NEEDS_NEW_COLUMN`
cannot recite anything; deterministic Java turns the code into the sentence the user reads, in
the register `10-capability-gap.md` already establishes. The model's job shrinks to a
classification, which is what small models are for.

Three things fall out of it for free.

The benchmark gains a **distribution of refusal reasons**. "Refused 6 of 20" is a number.
"Refused 6, of which 4 were `NEEDS_NEW_COLUMN`" is a roadmap — that is `10-capability-gap.md`
written from live traffic instead of a corpus scan.

The UI gains a next action. `NEEDS_NEW_COLUMN` can offer the editor; `AMBIGUOUS` can offer the
two candidates as buttons.

And a refusal stops being a loop failure. Both the escape-hatch literature and the "I don't
know" filter work on function calling report the same thing: models guess because training
rewards a confident guess over an admission, and giving them a structured way to abstain
recovers a large part of the difference. Here it also converts the worst measured failure —
four silent cap hits — into something with a name.

`panic` is always in the tool set, in every mode, including QUESTION.

**Landed.** Eight codes, the sentences in `DecisionToolLoop.PANIC_REASONS`, and
`Outcome.panicked` recording the code so a benchmark can count it. A reason outside the list
falls back to `UNSURE` rather than producing nothing. The two-turns-left nudge is in the loop.

## 8. The context budget

Measured, on this repository:

| | tokens |
|---|---|
| `Prompts.DECISION_TOOLS` | ~1,105 |
| Nine tool schemas, serialized | ~900 |
| **Fixed preamble, every conversation** | **~2,000** |
| Every rule of every table in `loan-recommendation.dmn` | **~670** |

**The instructions cost three times the entire file they are instructions about.** At the 126
tokens/second measured on an M4's GPU that is sixteen seconds before the first token of the
first turn. On the CPU-only machines this is now aimed at, at the 15–25 tok/s a 9B dense
prefills at, the same 2,000 tokens is **80 to 130 seconds** — spent on every request, before the
model has looked at anything.

That is the whole argument for this section. On a GPU the preamble is an inefficiency. On a CPU
it is the product.

After Sections 4 through 6:

**Measured after the work, not predicted:**

| | tokens |
|---|---|
| System prompt (`DECISION_TOOLS`) | **122** — was 1,105 |
| QUESTION tool set — `list_decisions`, `show_decision`, `answer`, `panic` | 254 |
| EDIT tool set — those, plus `set_cell`, `add_rule`, `done` | 459 |
| Every tool, when the router abstains | 647 |
| **QUESTION preamble** | **376** |
| **EDIT preamble** | **581** |
| Router prompt, paid once on its own turn | 55 |
| Notation card, once, on the first table opened | ~50 |

Against a starting point of ~1,827. **A question now prefills 4.9× less than every request used
to, and an edit 3.1× less.** On the 9B-dense CPU row that is roughly 100 seconds of turn-one
prefill becoming 20.

Most of the second pass came from deleting descriptions that were defending against bugs the code
had since fixed. `set_cell`'s `expect` carried a paragraph explaining that the cell holds `>0.36`
and not `DTI >0.36`, written when `gpt-oss:20b` got that wrong — but the editor now strips a
leading column name before comparing, so the paragraph was arguing with a version of the harness
that no longer exists. `add_rule` warned that an overlapping rule would be refused, which is what
the auto-check returns anyway, in the gate's own words, at the moment it happens. Both are the
same mistake: a prompt that keeps a note about a failure after the failure has been engineered
out.

**What deliberately did not shrink**, because each is load-bearing and saying so is more useful
than a smaller number:

- **`panic`, 136 tokens, the largest schema in both sets.** Forty of those are the eight enum
  values, which are the design rather than prose. Its "first ask whether this can be done by
  changing values" clause is paid even on questions, where it is useless, and it stays because
  the benchmark measured models *over*-refusing: `qwen3:14b` scored best of three on IMPOSSIBLE
  and worst on EDIT.
- **`show_decision`'s reminder to look before writing**, even though the precondition now refuses
  anyway. The refusal costs a turn; the reminder costs fifteen tokens.
- **The hallucination paragraph**, which is most of what is left of the system prompt. It is the
  one instruction with no mechanism behind it.
- **The router's extra turn**, which on a CPU is not free. Section 11 measures whether the
  tool-set reduction pays for it. If it does not, the router is the first thing to delete, and
  the code is arranged so that deleting it is one default method away.

Three further settings follow from it.

**`num_ctx` stays at 8192, and that is the second reversal in this section.** The plan was to
cut it to 4096 now that the preamble is a third of what it was. Item 2 made that unsafe by
succeeding: every write returns the re-rendered table, so a conversation is now *shorter in
turns and fatter per turn*, and a four-edit run against a fifteen-rule table passes 3,000 tokens
of tool results on its own. Truncation is silent and would land exactly on the oldest thing in
the window, which is the request. Memory is the resource this deployment has; context is where
to spend it. If the KV cache is what hurts, `OLLAMA_KV_CACHE_TYPE=q8_0` halves it without
touching the window.

**Set `num_predict`.** Section 5.

**Do not compact the history. Append only.** An earlier draft of this document proposed
replacing a superseded `show_decision` render with a stub, on the grounds that stale tables in
the history are how a model ends up naming a rule number that was correct two turns ago. On a
GPU that is a reasonable trade. **On CPU it is not, and the reversal is worth recording rather
than quietly dropping.**

Ollama reuses the KV cache for the longest prefix it has already seen. Rewriting any earlier
message invalidates the cache from that point, so the next turn re-prefills everything after it.
At GPU speed a 3,000-token re-prefill is twenty-odd seconds. At 15–25 tok/s it is two to three
minutes, to save a few hundred tokens. The arithmetic is not close.

The stale-render problem is real and is solved elsewhere, for free: since Section 4, every write
returns the freshly re-rendered table, so the most recent render in the history is always the
current one. The model does not need old ones removed — it needs the newest one to be last, and
it is.

**Prefix stability is now a design constraint, not an optimisation.** Anything that edits the
conversation retroactively — reordering, summarising, trimming the middle — costs a full
re-prefill on this hardware. Everything this loop injects (tool results, the two-turns-left
nudge) is appended at the end, and it has to stay that way.

### Ollama settings for a CPU-only machine

None of these are in the repository yet beyond `num_predict` and the timeout. They are the list
to work through with a stopwatch, and every one of them is a `-Pllm` run away from being a fact.

| Setting | Where | Why it matters on CPU |
|---|---|---|
| `num_batch` | request option | Prefill batch size, default 512. Larger batches raise arithmetic intensity, which is exactly what a compute-bound prefill wants, and cost memory, which you have. 1024 and 2048 are the two to measure. |
| `num_thread` | request option | Defaults to physical cores. On a many-core server prefill scales with threads until memory bandwidth saturates; on a laptop, going past physical cores makes it worse. Set it explicitly and measure rather than inheriting a default that was chosen for a different machine. |
| `num_gpu: 0` | request option | On a box with a weak iGPU, partial offload can be slower than staying on the CPU and is harder to reason about. Pin it, then unpin it deliberately if a run says otherwise. |
| `OLLAMA_NUM_PARALLEL=1` | server env | Parallel slots divide `num_ctx` between them and split the KV cache. A single-user desk tool wants the whole window and one stable prefix. |
| `OLLAMA_FLASH_ATTENTION=1`, `OLLAMA_KV_CACHE_TYPE=q8_0` | server env | Less KV traffic per token. Generation is bandwidth-bound; the KV cache is part of that bandwidth. |
| `OLLAMA_KEEP_ALIVE=-1` | server env | On a server, never unload. Reloading 13–24 GB from disk is a cold start nobody is waiting through, and it happens silently. |
| Quantisation | the tag you pull | `Q4_K_M` is the sane default. On ARM and AVX2, llama.cpp repacks `Q4_0` into layouts that prefill measurably faster. It is one `ollama pull` to find out. |

**The client timeout has been raised from 120 seconds to 600.** It was sized for a GPU. A CPU
machine loading a 13 GB model cold and then prefilling two thousand tokens can spend longer than
120 seconds before it has done anything wrong, and the failure it produces is the same
misdiagnosis Section 5 describes: *"I couldn't reach the local model"* about a model that was
working. The container-level `request-timeout` at 900 seconds was already generous enough to
cover it; only the HTTP client was short.

## 9. Retrieval, scoped to what it can actually fix

Retrieval belongs here. Documentation retrieval does not, and the difference is worth being
precise about, because a wrong retrieval spends the context budget that Section 8 just recovered.

The DMN 1.5 specification is a few hundred pages. The part that governs what this tool can write
— unary tests and hit policies — is about forty lines. Distil it by hand, once, and there is
nothing left to index. Chunking a specification to retrieve forty lines you could have pasted is
the expensive way to get a worse version of the same forty lines, and it would have improved
none of the twelve questions all three models already answered correctly.

Four layers, cheapest first, and **only the last two retrieve anything**.

**Layer 0 — the notation card. Static, ~50 tokens, attached to the first `show_decision`
result.** This is the "Write conditions in the table's own notation" paragraph, moved to where it
is used:

```
Notation:
  <=20        20 or less              >70         over 70
  [18..35]    18 to 35, both ends     (10..70]    over 10, up to and including 70
  "Poor"      a word, always quoted   -           this column does not matter
```

Attached to a tool result rather than the system prompt, it costs nothing on a request that never
looks at a table, and it arrives on the turn before the model has to write one.

**Layer 1 — the hit-policy card. Static, conditional on the file.** Rule order is meaningless
under UNIQUE and is priority under FIRST, which changes what a correct `add_rule` looks like.
Injected only when the shown table's policy is not UNIQUE. Across `corpora/`:

| Hit policy | Tables |
|---|---|
| UNIQUE (incl. 15 implicit) | 414 |
| PRIORITY | 87 |
| COLLECT | 85 |
| ANY | 32 |
| FIRST | 26 |
| RULE ORDER · OUTPUT ORDER | 14 |

**229 of 643 tables, 36%, are not UNIQUE.** Frequent enough that the card must exist; rare enough
that paying for it on every request wastes it two times in three. Conditional injection keyed on
a parsed attribute — no index, no embeddings, no similarity, and never wrong.

**Layer 2 — worked examples from `dmn-tck`, retrieved on failure. Built.**
`corpora/dmn-tck` holds models the specification says must produce particular answers, which
makes it the only body of DMN here that is known correct rather than merely real. From 162 files
it yields **19 tables** that are small enough to read, wide enough to matter, and built out of
ranges: `DecisionExamples` indexes those and returns one when a decision's coverage check has
failed **twice**.

Twice, not once. A single failure is the ordinary half-finished boundary edit that the loop
already recovers from on its own turn, so fetching then would charge almost every successful edit
for an example it did not need. Two failures in a row on one decision means the corrections are
not converging, and that is the only state where a few hundred tokens of worked example is worth
what it costs to prefill.

**Building it inverted the retrieval.** The plan was BM25 over the corpus, ranked by similarity to
the failing decision. Measured, a query of `Affordability Category DTI` against the TCK matches
**no document at all** — a lending table and a shipping table have the same boundary lesson and
no vocabulary in common — so a similarity-first implementation would have silently never fired.
What it retrieves on is structure: same hit policy, within one column of the same width, and
ranked by how much of the table is ranges rather than equalities. Words only break ties. That is
backwards from ordinary retrieval and correct here, because the example is not fetched for what
it says. It is fetched for the shape it demonstrates.

For the two-part DTI failure it returns `Order Discount`, whose five rules tile a number line end
to end: `<500`, `[500..999]`, `[1000..1999]`, `[2000..4999]`, `>=5000`. That is the lesson, in a
table about nothing to do with lending.

The example is framed rather than dropped in: it says which file it came from, that it is not the
open file, and that there is nothing in it to change. It stops short of claiming the example
passes this project's coverage gate, because nothing here has run that gate against it.

`corpora/` is gitignored and rebuilt by `scripts/fetch-corpora.sh`, so a clone that has never run
it has no examples and behaves exactly as before. Indexing is lazy, capped, and skipped entirely
when the directory is absent.

**Layer 3 — a gate-message to remedy table. Already built, and this is how that was found.**
The plan was a lookup keyed by gate class, turning an overlap report into *"move the edge of the
rule beside the one you changed."* Going to write it turned up `DecisionGates.describe`, which
has said exactly that since the coverage gate was written: *"After this change, nothing in
\"Affordability Category\" covers ( 0.15 .. 0.33 ), so those cases would get no answer at all.
Moving one boundary usually means moving the one next to it."* The remedy was never missing. It
was in the gate, where it belongs, and a second copy keyed by gate class would have been a
worse-maintained duplicate of a sentence that already exists. What was added instead is one
clause naming the way out — *"Fix it with another set_cell, or call panic if you cannot"* —
because the gate could say what was wrong but had nothing to point at when the model could not
fix it.

The rule underneath all of it: **retrieval fires on failure, not on entry.** Something that runs
on every request spends the budget before the model has done anything wrong, and this loop's
first turn is the one that already costs the most.

Sources, for the record. The two static cards are distilled by hand from
[Camunda's DMN tutorial](https://camunda.com/dmn/) (the fifteen-minute one), the
[Camunda FEEL reference](https://docs.camunda.io/docs/components/modeler/feel/language-guide/),
the OMG DMN specification's unary-test grammar, and the Kogito DMN documentation. They are
written once into source, reviewed like code, and never fetched at runtime. The retrieval corpus
is `corpora/`, already reproducible via `scripts/fetch-corpora.sh` and already gitignored.

## 10. The loop, end to end

```
request
  │
  ├── route · 1 turn · enum{QUESTION,EDIT} · ~55 tok of prompt, no tool schemas yet
  │             a provider that cannot classify returns UNKNOWN and everything loads,
  │             which is what this did before the router existed
  │
  ├── QUESTION  4 tools: list_decisions, show_decision, answer, panic
  │             read → answer. Nothing that writes is on the list at all.
  │
  └── EDIT      7 tools: those, plus set_cell, add_rule, done
                        (+ rename_decision, calculate, when the request implies them)
        │
        ├── show_decision  → table + notation card (once) + hit-policy card (if not UNIQUE)
        │
        ├── set_cell       → refuses a decision this conversation never opened
        │                  → writes, runs check, returns the check result AND the new table
        │                  → on that decision's second failed check, attaches a dmn-tck example
        │
        ├── at maxTurns-2  → "two turns left. Finish, or call panic."
        │
        ├── done           → refused while check reports a gap or an overlap
        │                  → takes no argument; the summary is the edit log
        │
        └── panic(what, why) → if tools were withheld, the FIRST panic buys them all
                               plus one more turn; the second is taken as final
                             → deterministic sentence, reason recorded
        │
        ▼
   the four Kogito gates, unchanged, with the last word
```

The harness does not change. The editor still writes the XML, `expect` still refuses a cell
holding something else, and the four gates still decide what may be offered to a human. What
changes is that fewer of the loop's invariants are sentences a model has to remember.

## 11. What to measure

Keep the twenty requests and the per-category scoring in `Benchmark.java`. Scoring by category is
what revealed that `qwen3:14b` was best at refusing and worst at editing, and a single number
would have hidden it. Add five columns:

| Metric | Why |
|---|---|
| Cap hits | Must go to zero. Section 4's ladder is the intervention; this is the reading. |
| `panic` rate, and the reason distribution | A refusal that names its reason is a roadmap. |
| Turns to completion | Section 4 predicts 8 → 5 on the two-part DTI request. It is the cheapest claim here to falsify. |
| Prefill tokens per conversation, and seconds spent on them | Section 8 predicts ~2,000 → ~490 tokens. On CPU the seconds are the number that matters, and Ollama returns `prompt_eval_count` and `prompt_eval_duration` for free. |
| Wrong-tool rate, by tool-set size | Section 6's whole premise. If nine tools and five tools produce the same rate, drop the router and keep the tokens. |
| Panics that came after a narrowed set, and whether the retry recovered them | The price of the word lists, stated as a number instead of an argument. |

### Two suites, and only one of them involves a model

`RequestConformanceTest` runs 36 requests through the loop in about a second, because there is no
model in it: a scripted provider returns canned tool calls, and what is under test is the part
decided by the request text alone. Which tools get advertised, and what a refusal reads like.
It runs on every build, prints expected against actual for every row, and writes the table to
`build/reports/conformance.txt`.

The corpus is written to be typed rather than composed. Terse (`dti affordable <0.15`), sloppy
(`pls change income risk score for under 18 to -150 thx`), rambling, indirect, and in Spanish,
German, French, Portuguese, Hindi and Chinese, because that is what arrives when the person who
knows the rule is not the person who wrote the tool.

**It found four defects on its first run**, all in the word lists that gate the rare tools:

| Request | Went wrong |
|---|---|
| `pls change income risk score for under 18 to -150 thx` | `-150` matched an arithmetic operator. A negative number is not a subtraction, and the pattern now needs a digit on both sides. |
| `runde die Schwellenwerte auf zwei Nachkommastellen` | German rounding missed an English-only stem. |
| `rename it to Debt Ratio and scale the thresholds by 1.1` | `rescale` was listed and `scale` was not. |
| `shift everything down 0.05` | Still misses, and stays missing. Adding `shift` would drag in every request that shifts a step, and the panic retry is the cheaper repair. |

The last row is the point of the table. Two rows are recorded as known misses with the reason
written next to them, rather than being made to pass by growing the word list until the suite is
green. A test that documents what it cannot do is worth more than one that hides it.

**What none of this establishes is whether a model can drive the loop.** No request in that suite
was ever read by one. That question belongs to `./gradlew bench`, which is a model answering 38
requests one at a time, and it is the only instrument that can answer it.

Run order, one variable at a time, `gpt-oss:20b` as the control on every row:

1. `gpt-oss:20b`, current build — the baseline that already exists, re-run.
2. `qwen3.5:9b think=false`, current build — how far a 9B falls without any of this.
3. `qwen3.5:9b think=false`, with Sections 4–6 — the number the proposal actually turns on.
4. `qwen3.5:4b think=false`, with Sections 4–6 — whether capability moved into the harness.
5. `gpt-oss:20b`, with Sections 4–6 — confirms none of this cost the strong model anything.
6. `qwen3.5:35b` (A3B), with Sections 4–6, on the server — the ceiling available for the
   compute budget, if the machine has the memory for it.

Row 4 is the interesting one. If a 4B clears QUESTION and single-cell EDIT, the harness is
carrying the work and the model is a component. If it collapses, the prose was doing more than
this document credits it with, and that is worth knowing too.

**Run it on the slowest machine in the fleet, not the fastest.** Every latency figure in
`08-choosing-a-model.md` was measured on an M4 with a GPU, and a benchmark run on the
development laptop answers a question nobody is asking. The wall clock will be unpleasant — 20
requests at CPU speed is hours, not the 22–62 minutes recorded there — and that is itself the
finding.

## 12. Order of work

Ranked by measured payoff per unit of effort. **All of it has landed** — 202 tests pass, up
from 183.

1. **`num_predict`, and the client timeout.** ✅ `processdesk.ollama.num-predict` (512 for
   structured replies, which a schema already bounds) and `num-predict-prose` (200, for
   `EXPLAIN`, which nothing bounded). Timeout raised 120s → 600s for CPU machines. Between them
   these are the answer to "the 14B keeps crashing": both failures were unbounded work reported
   as a network error.
2. **Auto-check and re-render inside `set_cell` and `add_rule`.** ✅ Every write now returns what
   it broke and the table as it now stands, so the documented eight-turn success path is five.
   Verification stopped being an instruction a model can skip. Also what makes Section 8's
   append-only rule sufficient: the newest render is always the last one.
3. **`done` with no argument.** ✅ The summary is built from the edit log. Deleted a schema, a
   prompt paragraph, and the double-encoding bug in one edit. `done` is also refused **once**
   while the gates report a gap, returning the gap — a refusal it cannot satisfy would only
   spend turns arriving where the gates were going to put it anyway.
4. **`panic` with a reason enum.** ✅ Eight codes; the sentence the user reads is written in
   Java. `Outcome.panicReason()` carries the code out for the benchmark, and a panic is recorded
   distinctly from a cap hit. The loop also asks for an ending with two turns left, which is the
   direct intervention against four silent cap hits in twenty.
5. **The rewritten system prompt.** ✅ 1,105 tokens to **122**. Every paragraph went somewhere
   it cannot be skipped: "call show_decision before every set_cell" is a precondition in
   `DecisionWorkspace`; "call check after changing a boundary" is what a write returns; "write
   conditions in the table's notation" is a card attached to the first table opened; the refusal
   example is `panic`'s enum. What is left is the part with no mechanism behind it — nothing in
   the harness can tell that a fluent answer describes a file it never read.
6. **The router and tool subsetting.** ✅ `AiProvider.classify` reads the request as QUESTION,
   EDIT or UNKNOWN in one constrained turn before any schema loads. Ten tools become four or
   seven. `rename_decision` and `calculate` load only when the request implies them; `check`
   loads for nobody, because every write already runs it. A wrong reading widens the set and
   runs the call anyway.
7. **`num_ctx`, and append-only history.** ✅ Both **reversed** on the CPU constraint, and both
   recorded in Section 8 rather than quietly dropped. 8192 stays, because item 2 made
   conversations fatter per turn. History is never rewritten, because on a CPU a cache-invalidating
   edit costs a full re-prefill — minutes, to save a few hundred tokens.
8. **The two static cards.** ✅ Notation, spent once on the first table the model opens; hit
   policy, only when the table is not UNIQUE — 229 of 643 tables in `corpora/`, so it fires on
   about a third of them and costs nothing on the rest. Neither is retrieved. Both are attached
   to a tool result rather than the system prompt, so a request that never opens a table never
   pays.
9. **Benchmark instrumentation.** ✅ Prefill tokens and seconds per request, counted from what
   Ollama reports rather than estimated, and surfaced through `usage()` so `/api/health` shows
   them too. Panic reasons as a distribution. Routing broken out against the expected category.
   Cap hits were already there and are the number to drive to zero.
10. **Retrieval over the corpus.** ✅ `DecisionExamples` indexes the 19 usable range tables in
    `corpora/dmn-tck` and returns one when a decision's coverage check has failed twice. It
    retrieves on structure and only breaks ties on words, because a similarity-first version was
    measured returning nothing at all. Absent corpus, absent examples, unchanged behaviour.

## Where it stands

Everything above is mechanism, and mechanism is testable without a model: **202 tests, up from
183**, of which 36 are requests typed the way people type. What none of it establishes is
whether a 4B or a 9B can now drive the loop, because that is a question about a model and the
only honest instrument for it is Section 11 run on the slowest machine in the fleet. A suite
that runs in a second runs in a second because no model was asked anything.

Three claims in this document are predictions until that runs. The eight-turn path becomes five.
The preamble reduction is 3.5× for a question and 2.1× for an edit — measured as tokens, not yet
as seconds on the target hardware. And the router's extra turn pays for the tools it removes; if
it does not, it is the first thing to delete, and the code is arranged so that deleting it is
one default method away.

## What this does not change

The model reads business vocabulary and names a coordinate. Deterministic Java writes the XML.
Kogito's own compiler and decision-table analyser decide whether the result may be offered to a
human. A smaller model widens neither what can be written nor what can be approved — it only
changes how often the loop reaches a proposal at all, which is the one thing the benchmark
measures and the one thing this document is trying to move.
