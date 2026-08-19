# Architecture — Kogito AI Assistant (Process Desk)

Technical companion to the proposal. Describes what is built today, and what is planned.

**Status legend:** ✅ built and verified · 🔜 planned

---

## 1. System Overview

```
┌──────────── Frontend (React + Vite, :5173) ─────────────┐
│  KIE standalone editor (iframe)   Assistant panel        │
│         ▲ getContent/setContent/undo    │                │
│         └──────── editorApi.ts ─────────┤                │
└─────────────────────────────────────────┼────────────────┘
                                          │ HTTP /api
┌──────────── Backend (Spring Boot 3.5, Java 21, :4000) ───┐
│  AiController ── AiProvider (seam) ─┬─ DummyAiProvider ✅ │
│       │                             ├─ OllamaAiProvider ✅ │
│       │                             └─ GeminiAiProvider ✅ │
│       ▼                                                   │
│  ProcessEditor ──► ValidationGates ──► AuditLog           │
│       │                  │                                │
│       │                  └── Kogito parser (gate 1)       │
│       ▼                                                   │
│  AssetService (files on disk)                             │
│                                                           │
│  ProcessRuntime / DecisionRuntime — Kogito engines,       │
│    exercised by tests to prove edits stay runnable        │
└───────────────────────────────────────────────────────────┘
```

Dependency direction: `ai` → `harness` → `assets`. `harness` uses Kogito's parser for the
first validation gate; the `kogito` package's execution side is reached only from tests.
Nothing imports upward.

## 2. Backend — Spring Boot + Gradle ✅

- **Java 21** (Kogito 10.x and Spring Boot 3.5 both require 17+; the machine had only Java 8,
  so `brew install openjdk@21` is a setup prerequisite).
- **Gradle 8.14** via wrapper. Kogito's own build tooling is Maven-first, but every Kogito
  artifact needed here is an ordinary jar on Maven Central, so Gradle resolves them fine.
- `./gradlew build` produces a ~50 MB Spring Boot jar with both Kogito engines bundled.

```gradle
implementation "org.kie.kogito:jbpm-bpmn2:10.2.0"      // BPMN parsing + process engine
implementation "org.kie:kie-dmn-core:10.2.0"           // DMN decision engine
implementation "org.kie:kie-dmn-validation:10.2.0"
```

## 3. Kogito Does All Asset Work ✅

The application never interprets BPMN or DMN semantics itself.

| Concern | Kogito component | Where |
|---|---|---|
| Read BPMN | `XmlProcessReader` + `BPMNSemanticModule`, `BPMNDISemanticModule`, `BPMNExtensionsSemanticModule` | `ValidationGates.structure`, `ProcessRuntime.read` |
| Run BPMN | `BpmnProcess` + `StaticApplication` / `StaticProcessConfig` | `ProcessRuntime.start` |
| Evaluate DMN | `DMNRuntimeBuilder` → `DMNRuntime.evaluateAll` | `DecisionRuntime` |
| Edit visually | `@kie-tools/bpmn-editor-standalone`, `@kie-tools/dmn-editor-standalone` | `editorApi.ts` |

**Runtime, not codegen.** Kogito normally generates process classes at build time via its
Maven plugin. Here definitions are read at runtime instead, so a file the user just edited
can be executed immediately without a rebuild — which is the whole point for an editing tool.

**Two API details worth recording** (both cost time to discover, neither is documented):

1. Kogito 10.2.0 removed the `BpmnProcess.from(Resource...)` static factory that 10.1.0 had.
   The current path is `new XmlProcessReader(modules, cl).read(...)` then
   `new BpmnProcess(definition, config, application)`.
2. Starting a process needs the process config reachable *both* directly and through the
   application's config: `new StaticApplication(new StaticConfig(Addons.EMTPY, processConfig))`.
   A bare `new StaticApplication()` leaves `config()` null and `activate()` throws.

**The engines are kept, and tested, but not exposed.** There is no HTTP surface for running
a file. `ProcessRuntime` and `DecisionRuntime` remain because they back the strongest claim
in the project — that an edit passing the gates is one Kogito can still execute — and
`KogitoRuntimeTest` asserts exactly that, on a process with branches. Without them the gates
would degrade to a syntax check that nobody had verified against the runtime.

**Preview-run work item handlers.** A plain BPMN `<task>` declares an empty work item type,
and the engine registers handlers by walking `WorkItemHandlerConfig.names()`. So `""` must be
registered explicitly, and the handler must return a `complete` transition (Kogito's
`DoNothingWorkItemHandler` leaves the item waiting forever). `ProcessRuntime.CompleteImmediatelyHandler`
completes every work item on arrival: a preview run shows the *path* work takes, not the work.

## 4. Editor Integration ✅

Current packages, both **10.2.0** — the older `@kie-tools/kie-editors-standalone` is superseded:

- `@kie-tools/bpmn-editor-standalone` (BPMN 2.0, React Flow)
- `@kie-tools/dmn-editor-standalone` (DMN 1.6, React Flow)
- **SceSim gap:** no `scesim-editor-standalone` exists on npm. Only `@kie-tools/scesim-editor`,
  a React component with a different integration shape. `.scesim` files currently open in the
  plain-text fallback with a note saying so.

Both editors expose the same API, so `editorApi.ts` routes by extension and the rest of the
app is type-agnostic:

| API | Used for |
|---|---|
| `getContent()` | read current file (includes unsaved canvas edits) |
| `setContent(path, xml)` | **the preview mechanism** — the editor renders the proposal |
| `undo()` / `redo()` | reject a preview; user-facing undo |
| `subscribeToContentChanges()` | detect manual edits |

Zero forks, zero patches. The integration survives Kogito version upgrades.

**Two boot-order traps** (both were live bugs, both fixed):

- `getContent()` returns `""` until the envelope finishes starting. Callers must fall back to
  the loaded file rather than trusting an empty string.
- The editor must be opened from a **callback ref**, not an effect — the container div is
  created by the same render that opens the file. And the handle must not be cleared in an
  async open path: React StrictMode double-invokes effects, and a second `open()` racing the
  first would wipe a handle the editor had already registered, with nothing to re-register it.

## 5. The Model Interface ✅

**Two things cross the seam, and neither is a file.**

```java
public interface AiProvider {
    String name();
    String explain(String question, ProcessProjection process);
    EditIntent interpret(String request, ProcessProjection process);

    record EditIntent(Kind kind, String targetStepName, String value, String declineReason) {
        enum Kind { RENAME, ADD_AFTER, NONE }
    }
}
```

### Inbound: the projection, not the file

`ProcessProjection` is a process name and the order work moves through it:

```
Process: Member Refund
Flow: Submit Request → Verify Membership → Decide Approval Route
      → Approve Automatically or Manager Approval → Issue Refund → Notify Member
```

Steps are grouped by how many hops from the start they are, so alternatives on different
branches of a gateway render as "A or B". Two things were wrong before this:

- Following only the first outgoing flow walked one branch and **silently omitted the
  others**. "Manager Approval" never reached the projection, so it never reached the schema
  enum, so the model could not name it even when asked to by name. The enum is a safety
  mechanism, and an incomplete one silently removes correct answers.
- Writing branches as a flat chain told the model that one step runs after the other, which
  is not what the process does.

That is the whole of what the model sees. Not identifiers, not diagram geometry, not
namespaces. The fixture renders to 62 characters against a 2.4 KB file — a reduction of
roughly forty times, and a test asserts it stays at least twenty times smaller. Beyond
speed, it means the model cannot reason about layout it never saw, and coordinate churn
cannot invalidate a prompt cache.

### Decision models project differently

A DMN file has no steps, so projecting it as a process yields nothing — and the assistant
answered *"I can't see any steps in this file"* to a perfectly fair question about a decision
table. `DecisionProjection` gives it the shape a person actually asks about:

```
Decision model: Refund Approval
Needs to know: Refund Amount, Member Tier
Decides "Approval Route" (the first rule that matches wins):
  - Refund Amount <= 50, any Member Tier → "Automatic"
  - Refund Amount > 50, Member Tier "Executive" → "Manager Approval"
  - Refund Amount > 50, any Member Tier → "Store Review"
```

Handing over the rules themselves, rather than only the decision's name, is what lets the
model answer *why*: asked "why would a $30 refund be automatic?", it cites the threshold.
DMN's `-` (this column does not matter for this rule) is rendered as "any Member Tier", so
it does not read as a value.

`AssetProjection` is the shared interface; `AiProvider.explain` takes either kind, and
`interpretDecision` handles changes to a decision table — changing a condition, changing an
outcome, renaming a decision. Rules are addressed by the value they currently hold rather
than by row number, because that is how a person describes them.

**Decision edits have their own gates.** `DecisionGates` uses Kogito's DMN compiler:
structure (the model loads with no errors), shape (every rule has one condition per column
and an outcome), rules (no decision left with nothing to apply). Reusing the process gates
would hand a DMN file to the BPMN parser, and a check that can only fail is indistinguishable
from no check at all.

**Where enums help and where they hurt.** Constraining `column`, `decisionName` and `kind` to
the file's real values is what keeps a 9B on track. Constraining the *value being changed* is
not: asked to change Member Tier "from Platinum", the model could not express a value the
table lacks, so it answered the nearest legal one — "Executive" — and an edit ran against a
value nobody had mentioned. Asking the model to echo the user's wording alongside failed too
(it returned the replacement value, or the whole sentence). The check belongs in code, where
the request text is already in hand: if the chosen value does not appear in what the user
typed, the request is refused with the real values listed. **Enumerate a choice where any
member is acceptable; do not enumerate a value that carries what the user meant.**

For a 3.5 KB decision file the model receives 315 characters — eleven times smaller, with
`Decision_approval`, `InputData_`, `typeRef`, `xmlns`, `hitPolicy` and every tag absent.

### Outbound: an intent, in the user's vocabulary

The model chooses **what** to change, in step names. The harness decides **how** it is
written. Model output is around fifty tokens.

### One entry point

Everything the user types goes to `POST /api/ai/ask`, and the backend decides whether it is
a question or a change. The browser must not make that call: routing on its own pattern list
sent "stick a quality check right after the submit step" to the explain path, so the user was
told about their process instead of being offered the edit they asked for.

The intent vocabulary carries the decision: `RENAME`, `ADD_AFTER`, `QUESTION`, `NONE`.

### Three layers of determinism around the model

1. **Regular expressions first.** Clean phrasings — `add X after Y`, `rename X to Y` — and
   unambiguous question openers are matched without inference; measured at 0.0s against
   4–8s for a model call.

   **A fast path must verify its own answer.** These patterns know the shape "add X after
   Y". Given "Add new step after Verify Membership called \"Send Request\"" — where the name
   comes last — the greedy capture swallowed the trailing clause and offered
   `Verify Membership called "Send Request"` as the step to change. The user got a confident,
   instant, wrong answer to a sentence the pattern had not understood, and the model was
   never consulted. So a captured step name is now checked against the open file using the
   same rule `StepResolver` applies; if it does not name a real step, the pattern misread the
   sentence and the request goes to the model, which reads it correctly. Typo tolerance is
   preserved because the check shares the resolver's rule rather than demanding an exact match.

   This is the second bug of exactly this shape. The lesson generalises: an optimisation that
   can be confidently wrong needs a cheap check that it was right, and the fall-through must
   be to the slower correct path rather than to an error message.

   The question list holds only words that cannot begin a polite instruction: *what, why,
   how, who, when, where, which, explain, describe, tell me*. It deliberately excludes
   "can", "do" and "is", and does not treat a trailing question mark as decisive — "can you
   add a step after submit?" is an edit, and an earlier version answered it as a question.
   Anything less than certain goes to the model, which is what it is for.
2. **The schema is built per request.** `IntentSchema.forProcess` puts the open file's step
   names into the `targetStepName` enum. Under Ollama's structured output the decoder cannot
   emit a token the schema forbids, so naming a step that does not exist stops being an error
   to catch and becomes an answer the model is incapable of giving.
3. **`StepResolver` decides the identifier.** Never the model. Exact match, then case and
   spacing, then a bounded edit distance.

The third layer runs even though the second should make it unnecessary, because a provider
that ignores its schema must not be trusted merely because it was given one.

### Measured on ornith 9b (Ryzen AI 7 PRO 350, 32 GB, CPU inference)

| Request | Path | Time |
|---|---|---|
| `Rename "Manager Approval" to "Supervisor Review"` | fast path | **0.0s** |
| "can you call the approval step Supervisor Review instead" | model | 4.8s |
| "can you add a quality check after submit request?" | model | 7.6s |
| "stick a quality check right after the submit step" | model | 7.0s |
| "what does this process do?" | model → explain | 4.0s |
| "delete the approval step" | model → declined | 3.0s |

### What the fast path may and may not do

`RequestPatterns` recognises "rename X to Y" and "add X after Y" without a model call. On a
metered provider that is worth having — the free tier allows twenty requests a day — but its
brief is narrow, and the boundary was learned the hard way.

**It may recognise an edit. It may not decide that something is a question.** The difference
is verification: a captured step name is checked against the open file, so a misread finds
no such step and falls through to the model. A question has nothing to check against.

Two regexes used to classify intent — one for processes, one for decisions — and both were
eventually confidently wrong. The decision one survived two rounds of patching before *"I
want to change Income Risk score for under 18's to -150"* was routed to the explainer
because it opens with a pronoun. The model had read it correctly; the classifier discarded
the answer before the user saw it. Both are gone: `interpret` and `interpretDecision` return
`QUESTION` as a kind, and the controller acts on that.

The cost is one extra call per question, because classifying and explaining are separate
turns. A wasted call is cheap; a change silently answered as a question is not.

Generation runs at **12.6 tokens/second**. First call after a cold start adds roughly 10
seconds to load the model, which `keep_alive: 30m` then avoids.

Two settings were established by measurement, not assumption:

- **`think: false`.** ornith is a reasoning model, and its private reasoning is tokens paid
  for and never read. Leaving it on roughly tripled time-to-decision (25s against 9s) with
  identical answers. The schema is the thinking.
- **Every schema field required.** With only `kind` marked required, ornith reliably
  returned `{"kind":"RENAME","targetStepName":"Manager Approval"}` and stopped — no new
  name, a half-formed edit the harness had to reject. Requiring all four fields costs a few
  wasted tokens on the fields a given kind ignores, and makes the reply usable every time.

### Typo tolerance, and where it stops

| Name length | Edits tolerated |
|---|---|
| under 5 | none |
| 5–8 | 1 |
| over 8 | 2 |

Short words get nothing: plenty of distinct short words sit one edit apart — "Pick" and
"Pack", "Ship" and "Skip" — and silently correcting one into the other would edit a step the
user never meant. Asking is cheap; editing the wrong step is not.

A name matching several steps equally well returns **ambiguous**, which is a different
answer from **not found**, and produces different wording: one asks the user which they
meant, the other tells them what exists. A corrected match is flagged `exact=false` and
recorded that way in the audit log, so a guess is never filed as a certainty. The user sees
the resolved name in the change-order sentence before approving.

## 6. Edit Harness ✅

**The model proposes; deterministic code disposes.**

`ProcessEditor` applies intents to the DOM under two invariants that make model-proposed
edits safe:

1. **Identifiers are generated here**, never taken from the caller. Two identical requests
   produce two distinct tasks.
2. **Diagram geometry is computed here.** The model never sees or supplies coordinates, so
   it cannot corrupt `bpmndi` layout.

`addTaskAfter` rewires `anchor → new task → old next`: it retargets the anchor's existing
outgoing flow to start at the new task, adds a new flow into it, fixes the anchor's
`<outgoing>` child, generates a `BPMNShape` below the anchor, and reroutes the affected edge.

`rename` changes the `name` attribute only — the id is untouched, so references elsewhere
keep working.

## 7. Validation Gates ✅

Every proposed change passes three gates before a human is asked to approve it. Wording is
written for a business user and shown verbatim in the UI.

| Gate | Check | On failure the user sees |
|---|---|---|
| **Structure** | Kogito's parser loads the file | "Kogito couldn't read the file after this change." |
| **Connections** | every `sourceRef`/`targetRef` resolves | "A connection points at a step that doesn't exist." |
| **Rules** | every step has a way in and a way out | "The 'Manager Approval' step isn't connected to anything after it." |

Gate 1 passing means *Kogito itself can load the file*, not merely that the XML is well-formed.

A change failing any gate is never offered for approval: `/api/ai/propose` withholds
`proposedXml` unless all three pass, so the UI cannot render an enabled Apply button for a
broken edit. Saving re-runs all three rather than trusting the client. Every proposal is
appended to `audit.log` with the request, the intent, the gate results, and the outcome.

## 8. Frontend UX ✅

- **Activity trail** — one row per task with a spinner, then a check or a cross, plus a
  detail line. The three check rows carry the wording the gates themselves returned, so a
  row is never marked done before the thing it names has happened.
- **Change order card** — one plain sentence, docked above the composer rather than inside
  the scrolling transcript, so the decision is always on screen.
- **Templates** — one button per phrasing the fast path understands exactly, targeting a
  step chosen from a dropdown. They fill the box and **select the words the user must
  replace**. That selection is the point: the earlier chips inserted `Add "New Step" after …`
  with the caret at the end, so a step literally named "New Step" was one Enter away — and
  one duly ended up saved in a real file. A template whose placeholder is pre-selected
  cannot be accepted by accident. They also cost no inference, so the common edits are
  instant.
- **Preview** — "Show me" pushes the proposal through `setContent()`; the editor draws it.
  Cancel calls `undo()`.
- **Composer** — multi-line textarea, Enter sends, Shift+Enter newline, plus a step-name
  picker so users need not type names exactly.
- **Design direction** — "drafting desk": paper/ink palette, Archivo display, Public Sans
  body, IBM Plex Mono for identifiers.

## 9. Retrieval and Dependency Graph 🔜

Not built. Planned:

- **BM25 over markdown docs**, and over asset filenames plus step names to answer "which
  file?" across a large repository. Hand-rolled (roughly 80 lines, k1=1.2, b=0.75) rather
  than Lucene: the corpus is small, and a scoring function you can explain in a design review
  beats a 10 MB dependency doing the same thing opaquely.
- **BM25 is deliberately not used for step names.** A file's step names are a closed set of
  2–50 strings; ranking them statistically would be less accurate than deciding them
  exactly, which is what `StepResolver` does.
- **Dependency graph** parsed from explicit XML references (DMN `<import>`, BPMN
  `callActivity`, `businessRuleTask` → decision, `serviceTask` → class). Deterministic, not
  statistical — BM25 answers "which doc mentions X", not "what depends on X".

Shipped today: `nodeCatalog.ts`, the id ↔ label map that feeds the step picker.

## 10. Folder Structure ✅

```
backend/                                  Spring Boot + Gradle, Java 21
  build.gradle, settings.gradle, gradlew
  assets/                                 editable BPMN + DMN files
  audit.log                               append-only proposal record
  src/main/java/com/processdesk/
    ProcessDeskApplication.java           entry point + CORS for the Vite dev server
    assets/       AssetService            path-traversal-guarded file IO
                  AssetController         GET list/read, PUT save (re-runs gates)
    harness/      BpmnDocument            DOM wrapper; secure parsing configured once
                  ProcessEditor           rename, addTaskAfter; owns ids + geometry
                  ValidationGates         the three gates
                  GateResult, AuditLog
    kogito/       ProcessRuntime          run BPMN on the Kogito engine (test-only)
                  DecisionRuntime         evaluate DMN on the Kogito engine (test-only)
    ai/           AiProvider              THE SEAM (explain + interpret)
                  DummyAiProvider         placeholder; @ConditionalOnProperty
                  AiController            POST /api/ai/{chat,propose}
  src/test/java/…/
    HarnessTest                           7 tests: edits + gate failure modes
    KogitoRuntimeTest                     5 tests: Kogito really executes both types

frontend/                                 React 18 + Vite + TypeScript
  src/editor/     editorApi.ts            ONLY place that touches KIE packages
                  EditorContainer.tsx     callback-ref boot, text fallback
  src/assistant/  AssistantPanel.tsx      chat, trail orchestration, composer
                  ActivityTrail.tsx       spinner → check rows
                  ProposalCard.tsx        the change order
  src/llm/        client.ts               backend client (the stable seam)
  src/graph/      nodeCatalog.ts          step names parsed from the open file
  src/styles/     app.css                 design tokens + components
```

## 11. Test Coverage ✅

131 tests, `./gradlew test`:

- the fixture passes every gate; renaming keeps it valid and preserves the id
- adding a step produces flow order `Submit Request → Quality Check → Manager Approval`,
  generates a diagram shape, and survives all gates
- two identical add requests produce distinct identifiers
- a dangling `targetRef` fails the Connections gate; a removed flow fails Rules and names
  the step in plain language; malformed XML fails Structure
- **Kogito runs the fixture to completion**, and **still runs it after the harness edits it**
- Kogito evaluates the decision table correctly across all three rules

On branching processes:

- every step reaches the projection, including one behind a gateway — the case a
  single-flow walk silently dropped
- branches render as "A or B" rather than as a sequence
- a cycle in the flow terminates the walk and lists each step once
- a step can be added on a branch and the result still passes every gate

On decision models:

- a DMN file is recognised as one, and a process is not mistaken for one
- the projection carries the decision's inputs and its rules, with DMN's "-" rendered as
  "any <column>" so a don't-care does not read as a value
- thresholds such as `<= 50` survive intact while markup and identifiers do not

On running what the harness produced:

- Kogito runs the fixture to completion, and still runs it after an edit
- with a gateway, the recorded path shows the branch actually taken and omits the other
- Kogito evaluates the decision table correctly across all three rules

Plus, on the model seam:

- the projection carries flow order and nothing else — asserted to contain no identifiers
  and no geometry, and to stay at least twenty times smaller than the file
- the schema's `targetStepName` enum is the open file's real step names, and all four fields
  are required
- a reply missing its new name, or naming a blank step, declines rather than becoming a
  half-formed edit
- politely phrased edits ("can you add a quality check after submit request?") are **not**
  short-circuited as questions, which is the regression that made the fast path answer a
  user instead of offering them their change

And on the providers themselves — none of which makes a network call, because a suite that
needs an API key and a quota is a suite that stops running:

- reply handling is tested once, in `ModelRepliesTest`, because every provider shares it:
  the rule that half an edit becomes a refusal must not depend on who answered
- the schema translation is tested against Gemini's dialect specifically — upper-case types,
  the enums surviving, `maxLength` dropped rather than sent, `kind` generated first
- an empty vocabulary leaves a field unconstrained rather than emitting an empty enum, which
  is not a loose constraint but an impossible one
- a clear request is answered by the fast path with no key and no call, on either provider
- a missing key, a rejected key and an unreachable API each produce a message naming what to
  check, rather than a stack trace or a generic failure

That Kogito-after-edit test remains the important one: it ties the edit harness to the
runtime, so a change that passes the gates is a change Kogito can actually execute.

## 12. Architecture Decision Records

| ADR | Decision | Reason |
|---|---|---|
| 001 | Model emits **intents**, not XML or edit-op DSL | ~50 output tokens suits a 9B on a laptop; XML never crosses the seam, so a bad response cannot corrupt a file — only name a step that does not exist |
| 007 | Model receives a **projection**, not the file | ~40× smaller prompt, and it cannot act on geometry or identifiers it never saw |
| 008 | Step names resolved by **`StepResolver`**, never the model | a closed set of 2–50 names is a decided question, not a guessed one; BM25 would rank where exact matching should decide |
| 002 | Local inference over cloud API | zero egress → smallest approval surface; free evaluation |
| 003 | Host-app integration only, no editor fork | survives upstream upgrades |
| 004 | Kogito runtime parsing, not build-time codegen | a just-edited file must run without a rebuild; Kogito codegen is Maven-first and would fight the Gradle build |
| 005 | DMN evaluated by Kogito, never by the model | the engine is exact, instant, and free; a 9B reading DMN XML is a slower, less reliable version of what already exists |
| 006 | BM25 for docs, parsed graph for relationships | explicit XML references beat statistical retrieval for structure |
| 010 | Decision edits get their own gates, on the DMN compiler | the process gates would always fail on a DMN, which is not a check |
| 012 | Enums for choices, code checks for values | an enum turns "a value that is not there" into a confident substitution |
| 009 | Kogito execution kept as a test-only capability | it verifies that a change passing the gates is runnable; dropping it would leave the gates unproven |
