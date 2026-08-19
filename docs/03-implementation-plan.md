# Implementation Plan

Budget: ~10–15 hrs/week of evenings and weekends, roughly one month. Total ≈ 45–55 hours.

**Status:** the MVP skeleton is built and verified. What follows marks what is done and what
remains.

---

## Week 0 — prerequisites

- [ ] **Approval:** confirm in writing with your manager that Ollama and a local model may
      run on the dev machine, and which machine demos. Until confirmed: **fixture files
      only, never real company assets.** This is the one assumption that invalidates the
      plan if false, and it is still open.
- [x] Toolchain: JDK 21 (`brew install openjdk@21` — the machine had only Java 8, which
      neither Spring Boot 3 nor Kogito 10 supports) and Gradle.
- [x] `ollama pull ornith:9b`; measured **12.6 tokens/second** on this Ryzen under CPU
      inference. Recorded with the full latency table in `docs/02-architecture.md` §5.
- [ ] Retry with `OLLAMA_VULKAN=1` to see whether the 860M iGPU is usable; CPU-only is
      already fast enough for the demo.
- [x] Settings applied via `application.yml` rather than a Modelfile, so they live in the
      repo and travel with the code: `num_ctx 8192`, `temperature 0.1`, `keep_alive 30m`,
      `think false`.
- [ ] Note for a shared machine: `OLLAMA_MODELS` here points at an external SSD, so the
      drive must be mounted before Ollama will start.

## Done — MVP skeleton ✅

**Backend (Spring Boot 3.5 + Gradle, Java 21)**
- Asset store with path-traversal guard; save re-runs validation rather than trusting the client
- Edit harness: `rename`, `addTaskAfter`; harness owns identifiers and diagram geometry
- Three validation gates, gate 1 being Kogito's own parser
- Append-only audit log
- Kogito execution: BPMN process instances and DMN evaluation, exercised by tests — the
  proof that a change passing the gates is one Kogito can still run
- 131 tests, including "Kogito still runs a process after the harness edits it" and the
  branch-coverage case that a single-flow walk got wrong

**Frontend (React + Vite)**
- `@kie-tools/bpmn-editor-standalone` and `dmn-editor-standalone` 10.2.0, routed by extension
- Assistant panel: activity trail (spinner → check, with real gate wording), change-order
  card, canvas preview via `setContent`, undo, step picker
- `editorApi.ts` as the single integration seam

**AI seam**
- `AiProvider` interface: `explain`, `interpret`, `interpretDecision`. Only projections go
  in and intents come out; XML never crosses it in either direction.
- `DummyAiProvider` (default), `OllamaAiProvider`, `GeminiAiProvider`, chosen by
  configuration (`AI_PROVIDER` in `.env`).
- Prompts, reply handling and schema are shared (`Prompts`, `ModelReplies`, `IntentSchema`),
  so a difference between providers is a difference between models rather than between
  instructions. Only transport and error wording live in a provider.

**Local model ✅**
- ornith 9b running through Ollama, verified end to end in the browser: "stick a quality
  check right after the submit step" becomes a validated, previewable edit in about 7s.
- One entry point, `POST /api/ai/ask`; the backend decides question versus change.
- `/api/health` reports the live provider, and the UI badge reads it.

**Hosted model ✅**
- `GeminiAiProvider`: same prompts, same `IntentSchema`, translated to Gemini's OpenAPI
  subset by `GeminiSchema` (upper-case types, unknown keywords dropped rather than sent —
  Gemini answers an unrecognised keyword with a 400, not a warning).
- `thinkingBudget: 0`, the counterpart of Ollama's `think: false`: the schema is the
  reasoning, and on a request whose job is picking one of four kinds the thinking is
  latency for nothing.
- Key in `.env` at the project root, gitignored, loaded into `bootRun` by the build. Never
  in `application.yml`, which is a key in git history, and never in the URL, which is a key
  in proxy logs.
- With no key the app still boots and every non-assistant feature works; the panel says
  which file to put the key in. A rate-limited key is named as such rather than reported as
  a generic failure, because on a free tier that is the error people will actually meet.
- The swap proved the seam: gates, editors, resolver, audit log and UI were untouched.

**Measured, same requests against both providers**

| Request | ornith 9b | gemini-3.5-flash |
|---|---:|---:|
| change a condition | 10.0s | 1.4s |
| rename a decision | 8.6s | 1.4s |
| change an outcome | 12.5s | 1.2s |
| refuse a value the table lacks | 11.8s | 1.1s |

All five decision cases and all process cases produced the same intents and passed all
three gates. Two differences worth recording:

- On "change Member Tier from Platinum to Gold", ornith picked the nearest legal enum value
  and needed `ModelReplies.requestMentions` to catch it. Gemini refused on its own and said
  why. The guard stays — it is what makes the behaviour a property of the system rather than
  of the model — but it stopped being load-bearing.
- Gemini refused "delete the approval step" with *"Only RENAME, ADD_AFTER, and QUESTION
  operations are allowed"* — fluent, accurate, and written in exactly the vocabulary this
  project exists to keep away from the user. Fixed in the prompt and backstopped in code.
  A stronger model does not remove the need to constrain what reaches the user; it changes
  which mistakes it makes.

**Editing decisions ✅**
- **One operation**: write one cell, addressed by decision, rule number and column, plus
  renaming a decision. It replaced four kinds that were really a 2×2 of how you find the rule
  and which cell you write — see `docs/05-future-work.md`.
- `expect` states what the model believes is in the cell; a mismatch changes nothing. That
  replaced a heuristic about whether the user's sentence mentioned the value.
- Own gates on Kogito's DMN compiler and analyser: structure, shape, rules, coverage.
- Own gates on Kogito's DMN compiler: structure, shape, rules.
- Verified against ornith end to end, including refusing a value the table does not contain.

**Explaining decisions ✅**
- `DecisionProjection` carries inputs and decision-table rules, so the assistant can say why
  an outcome happens. Verified against ornith: "why would a $30 refund be automatic?" is
  answered from the threshold.
- Decision models get decision-shaped templates and their own gates.
- The panel adapts: decision files get decision chips, no step picker.

## Next

1. **Multi-edit.** Promoted from third to first by the coverage gate: a single boundary
   change on a range table is almost always incomplete, so "make High cover 20 or less"
   needs High *and* its neighbour moved together. One intent cannot say that, and the gate
   now refuses the half-edit rather than shipping it. A planner emitting a *list* of cell
   edits, each gated and approved as one change, is what unblocks it.
2. **Read decision logic that is not a table.** 92% of corpus decisions reach the model with
   no logic, and it invents the rest. `docs/06-test-corpora.md` has the measurement;
   `docs/05-future-work.md` §5c has the design. Most urgent — it produces confident wrong
   answers today.
3. **`SET_PROPERTY`.** What "increase the cost of hotel search" needs and rename/add cannot
   express. Blocked on deciding where a cost lives in a real production model — see
   `05-future-work.md` §1.
4. **Benchmark.** 20 requests through the seam, gate pass-rate and latency.
5. **Stream `explain`.** Polish; the activity trail already covers the wait.

Everything deferred, with the trigger for building it, is written down in
`docs/05-future-work.md` — including the Kogito and KIE API notes that cost real time to
discover.

## Then — widen the vocabulary

Each new intent kind is one enum value, one `ProcessEditor` method, and tests:
`delete` (must check the dependency graph first), `connect`, `set-property`, `add-gateway`.

Destructive intents need the dependency graph, so build that alongside `delete`.

## Then — retrieval and graph

- BM25 over the markdown docs (MiniSearch, in-browser)
- Dependency graph from explicit XML references; powers "what breaks if I change this?"

## Benchmark

20 edit requests against the intent seam, measuring gate pass-rate (first attempt and after
one retry) and wall-clock latency. This turns "intents beat raw XML" from an opinion into a
number, and it is the paragraph reviewers will actually read. It also needs real tokens/sec,
so do it after the model is connected.

## Cut lines, in order

1. Extra intent kinds beyond rename and add — the headline demo works with two
2. Benchmark shrinks to 10 requests
3. Retrieval over docs (explaining from the file itself needs no index)
4. **Never cut:** the validation gates, preview/undo, the audit log, the docs. Those are the
   project's credibility.

## Definition of done

- A person with no BPMN training completes one explanation and one edit unassisted
- Benchmark table exists with real numbers from this laptop
- Docs submitted to the company docs system
- Code in the team repo or a handoff branch, with the README

## Known gaps

- `.scesim` files open as text: KIE publishes no standalone SceSim editor. Wiring
  `@kie-tools/scesim-editor` (a React component, different integration shape) is optional work.
- Preview runs complete every work item immediately, so they show the path work takes, not
  what the work does.
- Single user, no auth, files on local disk.
