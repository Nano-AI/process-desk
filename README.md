# Process Desk

Describe a change to a business process or a decision table in plain language, see it on the
canvas, and approve it. Kogito validates and runs every asset, so an edit that reaches disk is
one the engine can still execute.

```
"stick a quality check right after the submit step"
"change the DTI for affordable to be under 0.15, and Marginal from 0.15 to 0.36"
"why would a $30 refund be automatic?"
```

The first two become validated, previewable edits. The third gets answered from the file rather
than from whatever the model happens to know about refunds.

Editing BPMN or DMN normally takes someone who reads XML. The person who knows the actual rule,
that refunds over $50 need manager approval, usually is not that person, so every change joins a
queue. Process Desk puts a language model between the two and then declines to trust it. The
model reads the file as business vocabulary and replies with a coordinate: which rule, which
column, what it should say. Deterministic Java turns that into XML, and Kogito's own parser and
decision-table analyser decide whether the result is worth showing a human at all.

MIT licensed. Commercial use and forks are fine, keep the copyright notice.

## Running it

You need JDK 21 and Node 18+. The default provider needs nothing else installed.

```bash
git clone https://github.com/Nano-AI/process-desk.git
cd process-desk
cp .env.example .env          # gitignored; holds keys and machine-local settings
```

Two terminals, from the repository root:

```bash
cd backend && ./gradlew bootRun     # http://localhost:4000
npm --prefix frontend run dev       # http://localhost:5173
```

Open http://localhost:5173, pick an asset, and type a request into the panel on the right. If
Gradle cannot find your JDK, `export JAVA_HOME=/opt/homebrew/opt/openjdk@21`.

Five assets ship in `backend/assets/`:

| File | What it is |
|---|---|
| `member-refund.bpmn` | 10 steps, two gateways, two branches |
| `refund-request.bpmn` | two steps, linear |
| `refund-approval.dmn` | one decision table, three rules |
| `dinner-decisions.dmn` | 3 decisions, 15 rules across 3 tables |
| `loan-recommendation.dmn` | 11 decisions, 44 rules, from the Drools test suite |

The lending model is the interesting one. It arrived with twelve overlapping-rule errors already
in it, which is why the coverage gate compares against the original instead of judging a file on
its own. [`docs/07-working-with-real-dmn.md`](docs/07-working-with-real-dmn.md) covers what a
genuinely messy model breaks.

## Choosing a provider

One setting, `AI_PROVIDER` in `.env`. The choice has a data-governance answer as well as a
technical one, and the governance answer already decided it: free-tier hosted terms permit
training on submitted input, which makes a hosted key usable for the fixture files in this repo
and unusable for real production decision logic at any quality level. So the local model is not
the cheap option here. It is the only one.

**`ollama`** runs the model on your own machine and nothing leaves it. This is what the
architecture is aimed at, and the target is a laptop or a server that is **short on CPU compute
and not short on memory** — no GPU assumed.

```bash
brew install ollama && ollama pull gpt-oss:20b
```

```bash
AI_PROVIDER=ollama
OLLAMA_MODEL=gpt-oss:20b
```

Which model goes in that slot is decided by **active** parameters, not by size on disk. On a CPU
both halves of a turn are charged for the parameters a token actually touches — generation is
`bandwidth ÷ active bytes`, prefill is `FLOPS ÷ (2 × active params)` — so a mixture-of-experts
model that stores a lot and activates a little wins twice. That makes the ordering come out
backwards from the intuitive one:

| Model | Size | Active | Where it fits |
|---|---|---|---|
| `qwen3.5:35b` | 24 GB | ~3B | a server with RAM and no GPU |
| `gpt-oss:20b` | 13 GB | 3.6B | the laptop default, and the only one benchmarked here — 17/20 |
| `qwen3.5:9b` | 6.6 GB | 9B | the "7B-class" pick, and the slowest of these on a CPU |
| `qwen3.5:4b` | 3.4 GB | 4B | a weak CPU, and the floor test for the harness |

Run every one of them with thinking off. Measured here: 9 seconds against 25, same answers.

**`dummy`** is the default, and stays the default for one reason: it needs nothing installed. It
answers from the file itself, with no model and no network, which is enough to watch the editor,
the gates and the approval flow work end to end on a fresh clone.

**`gemini`** is still in the source and is no longer a target. The provider seam is what makes
two models comparable, so the class is kept; the hosted path is not coming back, and the
governance paragraph above is why.

[`docs/08-choosing-a-model.md`](docs/08-choosing-a-model.md) covers which models can drive this,
with the measurements. [`docs/12-small-model-adaptation.md`](docs/12-small-model-adaptation.md)
covers running it on a small model on a CPU: what moved out of the prompt to make that possible,
and the settings table for the machine.

## How it works

A request passes through four stages, and the model is only present for the second.

First the file becomes business vocabulary. A process turns into its name and the order work
moves through it; a decision model turns into what each decision needs to know and the rules it
applies. Around forty times smaller than the source, with no XML, identifiers, or geometry.

Then the model reads it. For a process, one call returns an intent (rename this step, add one
after that one) constrained by a JSON schema whose enums are the file's real step names. Naming
a step that does not exist is not an error caught later. It is an answer the decoder cannot
produce.

For a decision model, a provider that supports tool calling gets a bounded conversation instead.
It can list the decisions, read one, change a cell, do arithmetic, check its own work, and fix
what it broke:

```
turn 1  list_decisions                       → "DTI" — a formula, not a table of rules
                                               "Affordability Category" — 3 rules, looks at: DTI
turn 2  show_decision Affordability Category → 1. >0.36  2. [0.33..0.36]  3. <0.33
turn 3  set_cell rule 3   <0.33 → <0.15
turn 4  check    → "nothing covers [0.15..0.33), so those cases would get no answer at all"
turn 5  show_decision Affordability Category
turn 6  set_cell rule 2   [0.33..0.36] → [0.15..0.36]
turn 7  check    → All checks pass
turn 8  done
```

Turns 4 through 6 are the reason the design looks like this. Nobody planned the second edit. The
model was told what it had broken while it could still fix it.

The edit itself is deterministic Java. `StepResolver` turns a step name into an identifier by
exact match, then case and spacing, then a bounded edit distance for typos, and never by asking
the model. Identifiers are generated by the harness and diagram geometry is computed by it, so a
proposal cannot corrupt references or layout. Decision tables have one write operation, a single
cell addressed by decision, rule number, and column, plus adding a rule and renaming a decision:

```
Income Risk Category:  1. >70 → "Low"   2. (10..70] → "Medium"   3. <=10 → "High"

"set the category to High when the score is 20 or less"
   → rule 3, column Income Risk Score, expect "<=10", to "<=20"
```

`expect` is what makes that safe. The caller states what it believes the cell holds, and the
edit is refused if it holds anything else, so a miscounted rule changes nothing instead of
changing the wrong thing.

Last come the gates. A change that fails any of them is never offered to a human.

[`docs/11-agentic-architecture.md`](docs/11-agentic-architecture.md) is the full technical
account: every tool, the loop's control flow, and where each guard sits.

## The safety model

A process edit passes three gates. Kogito's BPMN parser must load the file, every sequence flow
must point at a step that exists, and every step needs a way in and a way out.

A decision edit passes four. Kogito's DMN compiler must load the model without errors, every
rule must have one condition per column and an outcome, no decision may be left with nothing to
apply, and coverage must hold: every case still matches exactly one rule.

The fourth catches changes that are structurally perfect and semantically wrong. Move a band's
boundary from `<=10` to `<=20` while its neighbour still reads `(10..70]` and a score of 15
matches both rules; move the neighbour instead and 15 matches nothing. The first three gates pass
either version. Kogito's own decision-table analyser finds them, reporting overlaps at ERROR and
gaps at WARN, which is why filtering on severity alone caught one problem and missed the other.

Almost any single boundary change on a range table is an incomplete edit. The tool loop's `check`
surfaces that mid-conversation, which is how the model comes to make the second edit without
anything resembling a planner.

Coverage is comparative. It compares the proposal against the file the change came from and
reports only what the change introduced, because a gate that failed on "the analyser reports
errors" would refuse every correct edit to a real model, and a gate nobody can satisfy gets
switched off.

Saving re-runs the gates instead of trusting the client, since a proposal validated at
propose-time can still arrive modified. When a change is withheld the user is told why in the
words of the check that withheld it. Every proposal is recorded in `backend/audit.log`.

## What runs on Kogito

Everything that reads or executes an asset. BPMN parsing is `XmlProcessReader` with the BPMN
semantic modules; DMN reading, validation, and execution are `DMNRuntime` and `DMNValidator`;
the canvas is the standalone KIE BPMN and DMN editors. The application never interprets BPMN or
DMN semantics itself.

The execution engines are not exposed over HTTP, but the test suite exercises them, because they
back the strongest guarantee here: an edit that passes the gates is one Kogito can still run,
verified on a process with branches. Without that the gates would be a syntax check.

Process definitions are read at runtime rather than generated by build-time codegen, so a file
the user just edited can be executed immediately. Preview runs complete every work item at once,
showing the path work takes rather than what the work does.

## Layout

```
backend/                      Spring Boot + Gradle
  src/main/java/com/processdesk/
    assets/                   read and write the files on disk
    harness/                  BpmnDocument, ProcessEditor, ValidationGates, DecisionEditor,
                              DecisionGates, DecisionWorkspace, Formula, Bm25, AuditLog
    kogito/                   ProcessRuntime, DecisionRuntime (exercised by tests)
    ai/                       AiProvider (the seam), the three providers, DecisionToolLoop,
                              Tools, Prompts, StepResolver
  assets/                     editable BPMN and DMN files
frontend/                     React + Vite
  src/editor/                 editorApi.ts, the only place that touches KIE packages
  src/assistant/              panel, activity trail, change-order card
  src/graph/                  step catalog parsed from the open file
docs/                         01 through 11, see below
```

## API

| Endpoint | Purpose |
|---|---|
| `GET /api/health` | provider name, and what it has spent — prefill tokens and seconds for the local model |
| `GET /api/assets` · `GET /api/assets/{name}` | list and read files |
| `PUT /api/assets/{name}` | save, after re-running the gates |
| `POST /api/ai/ask` | the entry point: returns an answer or a proposed change |
| `POST /api/ai/ask/stream` | the same, newline-delimited JSON, one line per step |
| `POST /api/ai/chat` | explanation only |
| `POST /api/ai/propose` | proposed edit plus gate results |

`/ask` is the single entry point because deciding whether a sentence is a question or an
instruction is a judgement about language, which is the model's job. Splitting it across two
endpoints meant "stick a quality check after submit" got answered as a question instead of
offered as an edit.

## Configuration

`.env` at the project root, gitignored, loaded into `bootRun` by `backend/build.gradle`. Start
from `.env.example`.

| Variable | Default | What it does |
|---|---|---|
| `AI_PROVIDER` | `dummy` | `ollama` for real use; `dummy` needs nothing installed |
| `TOOL_LOOP` | `true` | set `false` to force the one-shot path everywhere |
| `TOOL_LOOP_MAX_TURNS` | `12` | conversation cap for decision edits |
| `OLLAMA_MODEL` | `gpt-oss:20b` | any tool-capable local model |
| `OLLAMA_BASE_URL` | `http://localhost:11434` | |
| `OLLAMA_TIMEOUT_SECONDS` | `600` | raised from 120 for CPU machines |

Deeper settings live in `backend/src/main/resources/application.yml`, each carrying the
measurement that produced it. Every provider runs at temperature 0, because choosing which tool
to call is a classification and sampling variance on a classification is pure downside.

## Tests and tools

```bash
cd backend
./gradlew test                                                   # 202 tests
./gradlew bench -Pllm=ornith:9b                                  # score a model over 38 requests
./gradlew bench -Pllm=ornith:9b -Ponly=QUESTION                  # or one category, or one word
./gradlew focus -Pq="change the income risk category to high"    # what a request ranks to
./gradlew inspect                                                # examine a corpus file
./gradlew surveyCorpora                                          # stats over fetched corpora
```

The tool loop's own tests use a scripted provider rather than a live model, covering the turn
cap, an invented tool name, a reply with no tool call at all, a verbatim repeat, and the
half-finished edit that must not be offered. What a real model chooses varies per run, so that
question belongs in the benchmark.

`RequestConformanceTest` is the same idea widened: 36 requests that are terse, misspelt,
indirect, or in one of six languages, run through the parts of the loop that are decided by the
words rather than by the model. It prints expected against actual for every row and writes the
table to `build/reports/conformance.txt`; `-Pverbose` puts it on the console instead. It runs in
about a second because nothing in it is a model, which is also the limit of what it proves.

The benchmark is where a model actually answers. 38 requests, scored by what each one is *for*,
because refusing is the right answer to "add a column for postcode" and the wrong answer to
"change the DTI". `-Ponly` runs a subset and says so in the output, since a score over nine
requests that reads like a score over thirty-eight is the same mistake as counting a timeout as
a correct refusal.

`scripts/fetch-corpora.sh` pulls 1241 DMN and 418 BPMN files from other open-source projects.
They are gitignored and reproducible rather than vendored.

## Documentation

| Document | What's in it |
|---|---|
| [`01-proposal.md`](docs/01-proposal.md) | the original case for building it |
| [`02-architecture.md`](docs/02-architecture.md) | the whole system, editor and save path included |
| [`03-implementation-plan.md`](docs/03-implementation-plan.md) | phased scope, and what's done |
| [`04-demo-script.md`](docs/04-demo-script.md) | a walkthrough of the interesting parts |
| [`05-future-work.md`](docs/05-future-work.md) | what's next, and what was left out on purpose |
| [`06-test-corpora.md`](docs/06-test-corpora.md) | how the corpora are fetched and measured |
| [`07-working-with-real-dmn.md`](docs/07-working-with-real-dmn.md) | what a genuinely messy model breaks |
| [`08-choosing-a-model.md`](docs/08-choosing-a-model.md) | which models drive this, with measurements |
| [`09-tool-loop.md`](docs/09-tool-loop.md) | why the loop replaced the one-shot protocol |
| [`10-capability-gap.md`](docs/10-capability-gap.md) | what the assistant still cannot do |
| [`11-agentic-architecture.md`](docs/11-agentic-architecture.md) | the agentic layer, end to end |
| [`12-small-model-adaptation.md`](docs/12-small-model-adaptation.md) | running it on a 7B, and what has to move out of the prompt |

## Contributing

Issues and pull requests are welcome. Run `./gradlew test` before opening one.

Two things to know before changing the AI layer. Prompts, reply parsing, and schemas are shared
across providers deliberately, because if two providers were given different instructions a
difference in their answers would tell you nothing about the models. And a provider translates
transport and nothing else: it does not run tools, decide when to stop, or touch a file. Adding
a tool, a provider, or a gate is three short checklists in
[`docs/11-agentic-architecture.md`](docs/11-agentic-architecture.md#15-extending-it).

## Licence

MIT, see [LICENSE](LICENSE). Use it commercially, modify it, redistribute it. The one condition
is attribution: keep the copyright notice and licence text in any substantial portion you
distribute.

Copyright (c) 2026 Aditya Bankoti.

The BPMN and DMN editors are [KIE Tools](https://github.com/apache/incubator-kie-tools) and the
execution engines are [Kogito](https://github.com/apache/incubator-kie-kogito-runtimes), both
Apache 2.0. `loan-recommendation.dmn` comes from the Drools test suite, also Apache 2.0.
