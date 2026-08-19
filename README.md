# Process Desk

Describe a change to a business process or a decision table in plain language. See it on the
canvas. Approve it, or don't. Kogito validates and runs every asset, so an edit that reaches
disk is one the engine can still execute.

```
"stick a quality check right after the submit step"
"change the DTI for affordable to be under 0.15, and Marginal from 0.15 to 0.36"
"why would a $30 refund be automatic?"
```

The first two become validated, previewable edits. The third gets answered from the file
rather than from what the model happens to know about refunds.

An open-source project, MIT licensed. Use it commercially, fork it, build on it — keep the
copyright notice and you're square. See [Licence](#licence).

---

## Table of contents

- [What it is](#what-it-is)
- [Running it](#running-it)
- [Choosing a provider](#choosing-a-provider)
- [How it works](#how-it-works)
- [The safety model](#the-safety-model)
- [What runs on Kogito](#what-runs-on-kogito)
- [Layout](#layout)
- [API](#api)
- [Configuration](#configuration)
- [Tests and tools](#tests-and-tools)
- [Documentation](#documentation)
- [Contributing](#contributing)
- [Licence](#licence)

---

## What it is

Editing a BPMN process or a DMN decision table normally requires someone who reads XML. The
person who actually knows the rule — that refunds over $50 need manager approval — is not
usually that person, so every change goes through a queue.

Process Desk puts a language model between the two, and then refuses to trust it. The model
reads a projection of the file in business vocabulary and replies with a coordinate: which
rule, which column, what it should say. Deterministic Java turns that into XML. Kogito's own
parser and decision-table analyser decide whether the result is offered to a human at all.

Nothing is written until a person approves it, and nothing reaches a person unless every
validation gate passed.

---

## Running it

**Requirements:** JDK 21, Node 18+, and one of the three providers below. The default provider
needs nothing installed.

```bash
git clone https://github.com/<you>/process-desk.git
cd process-desk
cp .env.example .env          # gitignored; holds keys and machine-local settings
```

Two terminals, from the repository root:

```bash
# Backend — http://localhost:4000
cd backend && ./gradlew bootRun

# Frontend — http://localhost:5173
npm --prefix frontend run dev
```

Open http://localhost:5173, pick an asset, and type a request into the panel on the right.

If Gradle cannot find your JDK:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21   # brew install openjdk@21
```

The assets in `backend/assets/` are the starting point:

| File | What it is |
|---|---|
| `member-refund.bpmn` | 10 steps, two gateways, two branches |
| `refund-request.bpmn` | two steps, linear — the simplest thing that works |
| `refund-approval.dmn` | one decision table, three rules |
| `dinner-decisions.dmn` | 3 decisions, 15 rules across 3 tables |
| `loan-recommendation.dmn` | 11 decisions, 44 rules, from the Drools test suite — a real, messy file |

`loan-recommendation.dmn` is the interesting one. It ships with twelve overlapping-rule errors
that were there before this project touched it, which is why the coverage gate is comparative
rather than absolute. See [`docs/07-working-with-real-dmn.md`](docs/07-working-with-real-dmn.md).

---

## Choosing a provider

One setting, `AI_PROVIDER` in `.env`. The choice has a data-governance answer as well as a
technical one.

### `dummy` (default)

Answers from the file itself. No model, no network, no install. Enough to see the editor, the
gates, and the approval flow work end to end.

```bash
AI_PROVIDER=dummy
```

### `ollama` — local

Runs a 9B model on your own machine. Nothing leaves it. Verified end to end: plain requests
become validated, previewable edits in about 7 seconds on an M4 laptop for a process edit, and
65 to 150 seconds for a decision edit that goes through the full tool loop.

```bash
brew install ollama          # or https://ollama.com/download
ollama pull ornith:9b
```

```bash
AI_PROVIDER=ollama
OLLAMA_MODEL=ornith:9b
OLLAMA_BASE_URL=http://localhost:11434
```

This is the provider the architecture is aimed at. [`docs/08-choosing-a-model.md`](docs/08-choosing-a-model.md)
covers which models can actually drive it and what was measured.

### `gemini` — hosted

Faster and stronger, and it sends the projection off the machine. About 150 tokens of business
vocabulary, carrying no identifiers and nothing that would let a reader reconstruct the asset —
but off the machine is off the machine, and that is a decision to make deliberately rather than
by leaving a default in place.

```bash
AI_PROVIDER=gemini
GEMINI_API_KEY=            # https://aistudio.google.com/apikey
GEMINI_MODEL=gemini-3.5-flash
GEMINI_DAILY_LIMIT=500     # https://ai.dev/rate-limit
```

Two things worth knowing before you rely on it. Free-tier terms permit the provider to use
your input to improve its products; paid-tier terms do not. And models get retired: a key
issued after a retirement gets a 404 rather than a warning. List what yours can reach:

```bash
curl -H "x-goog-api-key: $GEMINI_API_KEY" \
  https://generativelanguage.googleapis.com/v1beta/models
```

Each turn of the tool loop is one billed request, so an eight-turn conversation costs eight of
the daily allowance. `GET /api/health` reports what this application has spent today.

---

## How it works

A request travels through four stages, and the model is only present for the second.

**1. Projection.** The file becomes business vocabulary. A process becomes its name and the
order work moves through it; a decision model becomes what each decision needs to know and the
rules it applies. Around forty times smaller than the source, with no XML, identifiers, or
geometry.

**2. The model.** For a process, one call returns an intent — rename this step, add one after
that one — constrained by a JSON schema whose enums are the file's real step names. Naming a
step that does not exist is not an error caught later; it is an answer the decoder cannot
produce.

For a decision model, a provider that supports tool calling gets a bounded conversation
instead. It can list the decisions, read one, change a cell, do arithmetic, check its own work,
and fix what it broke:

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

Turns 4 through 6 are the reason this design exists. Nobody planned the second edit — the
model was told what it had broken while it was still in a position to fix it.

**3. The edit.** Deterministic Java. `StepResolver` turns a step name into an identifier by
exact match, then case and spacing, then a bounded edit distance for typos — never by asking
the model. Identifiers are generated by the harness and diagram geometry is computed by it, so
a proposal cannot corrupt references or layout.

For decision tables there is one operation — write one cell, addressed by decision, rule
number, and column — plus adding a rule and renaming a decision:

```
Income Risk Category:  1. >70 → "Low"   2. (10..70] → "Medium"   3. <=10 → "High"

"set the category to High when the score is 20 or less"
   → rule 3, column Income Risk Score, expect "<=10", to "<=20"
```

`expect` is what makes that safe. The caller states what it believes the cell holds, and the
edit is refused if it holds anything else, so a miscounted rule changes nothing rather than
changing the wrong thing.

**4. The gates.** Covered below. A change that fails any of them is never offered.

The full technical account is [`docs/11-agentic-architecture.md`](docs/11-agentic-architecture.md).

---

## The safety model

The assistant proposes; deterministic code disposes.

**For a process:**

1. **Structure** — Kogito's own BPMN parser loads the file
2. **Connections** — every sequence flow points at a step that exists
3. **Rules** — every step has a way in and a way out

**For a decision model:**

1. **Structure** — Kogito's DMN compiler loads the model with no errors
2. **Shape** — every rule has one condition per column and an outcome
3. **Rules** — no decision is left with nothing to apply
4. **Coverage** — every case still matches exactly one rule: no gaps, no overlaps

The fourth catches changes that are structurally perfect and semantically wrong. Moving a
band's boundary from `<=10` to `<=20` while its neighbour still reads `(10..70]` means a score
of 15 matches both rules; moving the neighbour instead leaves 15 matching nothing. Gates 1
through 3 pass both. Kogito's own decision-table analyser finds them — overlaps at ERROR, gaps
at WARN, which is why filtering on severity alone caught one and missed the other.

Almost any single boundary change on a range table is an incomplete edit. The tool loop's
`check` surfaces that mid-conversation, which is how the model comes to make the second edit
without anything resembling a planner.

Coverage is comparative, not absolute: it compares the proposed file against the one the change
came from and reports only what the change introduced. A gate that failed on "the analyser
reports errors" would refuse every edit to a real model, and a gate nobody can satisfy gets
switched off.

Saving re-runs the gates rather than trusting the client — a proposal validated at propose-time
could still arrive modified. When a change is withheld, the user is told why in the words of
the check that withheld it, not with a generic refusal. Every proposal is recorded in
`backend/audit.log`.

---

## What runs on Kogito

Everything that reads or executes an asset:

| Concern | Kogito component |
|---|---|
| Reading BPMN | `XmlProcessReader` + BPMN semantic modules (`org.kie.kogito:jbpm-bpmn2`) |
| Running BPMN | `BpmnProcess` on the Kogito process engine (test-only) |
| Reading and validating DMN | `DMNRuntime`, `DMNValidator` (`org.kie:kie-dmn-core`, `kie-dmn-validation`) |
| Running DMN | `DMNRuntime` (test-only) |
| Editing | `@kie-tools/bpmn-editor-standalone`, `@kie-tools/dmn-editor-standalone` |

The application never interprets BPMN or DMN semantics itself. The first validation gate hands
each proposed change to Kogito's own parser, so passing it means Kogito can load the file.

The execution engines are not exposed over HTTP, but they are kept and exercised by the test
suite, because they back the strongest guarantee here: **an edit that passes the gates is one
Kogito can still run**, verified on a process with branches. Losing that would reduce the gates
to a syntax check.

Process definitions are read at runtime rather than generated by build-time codegen, so a file
the user just edited can be executed immediately. Preview runs register handlers that complete
every work item at once — they show the path work takes, not what the work does.

---

## Layout

```
backend/                      Spring Boot + Gradle
  src/main/java/com/processdesk/
    assets/                   read and write the files on disk
    harness/                  BpmnDocument, ProcessEditor, ValidationGates,
                              DecisionEditor, DecisionGates, DecisionWorkspace,
                              Formula, Bm25, AuditLog
    kogito/                   ProcessRuntime, DecisionRuntime — Kogito execution,
                              exercised by tests rather than exposed over HTTP
    ai/                       AiProvider (the seam), the three providers,
                              DecisionToolLoop, Tools, Prompts, StepResolver
  assets/                     editable BPMN and DMN files
frontend/                     React + Vite
  src/editor/                 editorApi.ts — the only place that touches KIE packages
  src/assistant/              panel, activity trail, change-order card
  src/llm/                    backend client
  src/graph/                  step catalog parsed from the open file
docs/                         proposal, architecture, plan, demo script, future work,
                              test corpora, real DMN, model choice, tool loop,
                              capability gap, agentic architecture
```

---

## API

| Endpoint | Purpose |
|---|---|
| `GET /api/health` | provider name and, for metered providers, calls spent today |
| `GET /api/assets` · `GET /api/assets/{name}` | list and read files |
| `PUT /api/assets/{name}` | save, after re-running the gates |
| `POST /api/ai/ask` | **the entry point** — returns an answer or a proposed change |
| `POST /api/ai/ask/stream` | the same, as newline-delimited JSON, one line per step |
| `POST /api/ai/chat` | explanation only |
| `POST /api/ai/propose` | proposed edit + gate results |

`/ask` is the entry point because whether a sentence is a question or an instruction is a
judgement about language, which is the model's job. Splitting that across two endpoints meant
"stick a quality check after submit" got answered as a question instead of offered as an edit.

---

## Configuration

`.env` at the project root, gitignored, loaded into `bootRun` by `backend/build.gradle`. Start
from `.env.example`, which documents every setting.

| Variable | Default | What it does |
|---|---|---|
| `AI_PROVIDER` | `dummy` | `dummy`, `ollama`, or `gemini` |
| `TOOL_LOOP` | `true` | set `false` to force the one-shot path everywhere |
| `TOOL_LOOP_MAX_TURNS` | `12` | conversation cap for decision edits |
| `OLLAMA_MODEL` | `ornith:9b` | any tool-capable local model |
| `OLLAMA_BASE_URL` | `http://localhost:11434` | |
| `GEMINI_API_KEY` | empty | never commit this |
| `GEMINI_MODEL` | `gemini-3.5-flash` | |
| `GEMINI_DAILY_LIMIT` | `0` | `0` means unknown; the count is still reported |

Deeper settings live in `backend/src/main/resources/application.yml`, each with the measurement
that produced it. Both providers run at temperature 0, because choosing which tool to call is
classification and sampling variance on a classification is pure downside.

---

## Tests and tools

```bash
cd backend
./gradlew test        # 183 tests: harness, Kogito engines, branching, decisions,
                      # name resolution, model replies, tool loop, provider wiring
```

The tool loop's own tests use a scripted provider rather than a live model, covering the turn
cap, an invented tool name, a reply with no tool call at all, a verbatim repeat, and the
half-finished edit that must not be offered. What a real model chooses varies per run, so that
question belongs in the benchmark.

```bash
./gradlew bench                                                  # score a model over 20 requests
./gradlew focus -Pq="change the income risk category to high"    # what a request ranks to
./gradlew inspect                                                # examine a corpus file
./gradlew surveyCorpora                                          # stats over fetched corpora
```

`scripts/fetch-corpora.sh` pulls the test corpora (1241 DMN and 418 BPMN files from other
open-source projects). They are gitignored and reproducible rather than vendored. See
[`docs/06-test-corpora.md`](docs/06-test-corpora.md).

---

## Documentation

| Document | What's in it |
|---|---|
| [`01-proposal.md`](docs/01-proposal.md) | the original case for building it |
| [`02-architecture.md`](docs/02-architecture.md) | the whole system, including the editor and save path |
| [`03-implementation-plan.md`](docs/03-implementation-plan.md) | phased scope, and what's done |
| [`04-demo-script.md`](docs/04-demo-script.md) | a walkthrough that shows the interesting parts |
| [`05-future-work.md`](docs/05-future-work.md) | what's next, and what was deliberately left out |
| [`06-test-corpora.md`](docs/06-test-corpora.md) | how the corpora are fetched and measured |
| [`07-working-with-real-dmn.md`](docs/07-working-with-real-dmn.md) | what a genuinely messy model breaks |
| [`08-choosing-a-model.md`](docs/08-choosing-a-model.md) | which models drive this, with measurements |
| [`09-tool-loop.md`](docs/09-tool-loop.md) | why the loop replaced the one-shot protocol |
| [`10-capability-gap.md`](docs/10-capability-gap.md) | what the assistant still cannot do |
| [`11-agentic-architecture.md`](docs/11-agentic-architecture.md) | **the agentic layer, end to end** |

---

## Contributing

Issues and pull requests are welcome.

Two things to know before changing the AI layer. Prompts, reply parsing, and schemas are shared
across providers on purpose — if two providers were given different instructions, a difference
in their answers would tell you nothing about the models. And a provider translates transport
and nothing else: it does not run tools, decide when to stop, or touch a file.

Adding a tool, a provider, or a gate is three short checklists in
[`docs/11-agentic-architecture.md`](docs/11-agentic-architecture.md#15-extending-it).

Run `./gradlew test` before opening a PR.

---

## Licence

MIT. See [LICENSE](LICENSE).

You can use this commercially, modify it, and redistribute it. The one condition is
attribution: keep the copyright notice and licence text in any substantial portion you
distribute.

Copyright (c) 2026 Adi Bankoti.

The BPMN and DMN editors are [KIE Tools](https://github.com/apache/incubator-kie-tools) and the
execution engines are [Kogito](https://github.com/apache/incubator-kie-kogito-runtimes), both
Apache 2.0. `loan-recommendation.dmn` is from the Drools test suite, also Apache 2.0.
