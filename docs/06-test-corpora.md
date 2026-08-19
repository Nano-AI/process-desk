# Testing Against Files We Didn't Write

Our own fixtures only ever prove that the code agrees with itself. The corpora below are
other people's BPMN and DMN — the DMN conformance suite, and the engines' own test
resources — and they are where the real failures turned up.

The first survey found that **92% of decisions were being handed to the model with their
logic silently removed**, and that the model then confidently invented the missing logic.
Nothing in our own test suite could have caught that, because every fixture we wrote was a
decision table, and decision tables are the one thing the code handled.

---

## Fetching

```bash
./scripts/fetch-corpora.sh          # everything
./scripts/fetch-corpora.sh tck      # one source
```

The corpora are **not committed** — they are large, they belong to other projects, and
their licences are not ours to re-publish. `corpora/` is gitignored; the script is the
reproducible artefact. All three sources are Apache 2.0.

| Source | Contents | Why this one |
|---|---|---|
| `dmn-tck` | 162 DMN | The OMG spec group's conformance suite. Every boxed expression kind, plus expected results per case, so it tests evaluation and not only parsing. |
| `drools` | 1,076 DMN | How the DMN engine we actually run is itself tested. The closest thing to "DMN as Kogito expects it". |
| `kogito-runtimes` | 418 BPMN | Processes the Kogito runtime's own tests exercise. |

Clones are `--depth 1 --filter=blob:none --sparse`: a few directories of XML, not years of
history. Roughly 500 MB in total, most of it the TCK.

## Surveying

```bash
cd backend && ./gradlew surveyCorpora
```

It prints a measurement, and deliberately does not fail a build. The thresholds worth
defending belong in real tests once they have been met.

```
corpora: /Users/…/process-desk/corpora
1241 DMN, 418 BPMN

── DECISIONS ──
  files with decisions      1158
  decisions found           5356
  shown to the model with no logic   4940  (92%)
  files affected            916  (79%)
  constructs present across the corpus:
    literalExpression         917 files
    decisionTable             381 files
    invocation                126 files
    businessKnowledgeModel    248 files
    <context                   47 files
    requiredDecision          268 files
    relation                   52 files
    functionDefinition         26 files

── PROCESSES ──
  files read                418
  Kogito can parse          407  (97%)
  passes all three gates    392  (94%)
  files containing steps    376
  ...projected as EMPTY     30  (8%)
```

### Reading that

- **`literalExpression` appears in 917 files; `decisionTable` in 381.** Our reader only
  understands decision tables, so the majority of real decision logic is invisible to it.
- **92% of decisions render with no logic.** Not an error — a blank. The model is shown a
  decision's *name* and nothing else.
- **268 files chain decisions** through `requiredDecision`. We list decisions flat, so the
  order they feed each other in is lost.
- **8% of processes with steps project as empty.** The same class of bug as the gateway
  branches, still present in shapes we have not met yet.
- **Kogito parses 97%**, so the failures are ours, not the engine's.

## Two examples of what this catches

### A decision whose logic vanishes

`literalExpression` instead of a table — the majority case in the corpus:

```xml
<decision id="D_Risk" name="Risk Score">
  <literalExpression>
    <text>if Return History &gt; 5 then 90 else if sum(Eligible Items) &gt; 500 then 60 else 20</text>
  </literalExpression>
</decision>
```

What the model is shown:

```
Decides "Risk Score"
```

What it then answered when asked *"how is Risk Score worked out?"*:

> - A base score starts at 0 and increases by 5 for every item in the basket.
> - If the member has returned more than two items, add 20 to the score.
> - If the member is not an Executive tier, add 10 to the score.

Every line invented. The thresholds, the arithmetic, the tier rule — none of it is in the
file. **A silent omission became a confident fabrication**, which is worse than an error
message, because nothing signals that anything is missing.

### A process that runs the survey out of stack

One corpus process recurses through an event subprocess that signals itself. It threw
`StackOverflowError`, which is an `Error` and not an `Exception`, so a `catch (Exception)`
let it escape and kill the JVM mid-survey. Anything walking a corpus needs `catch
(Throwable)` around each file: one hostile input must not end the run.

## The survey reads; it never runs

A BPMN script task carries executable code. Starting hundreds of downloaded processes would
be running strangers' code on this machine, which is not a thing to do casually on a work
laptop — so the survey parses and projects, and never calls `ProcessRuntime.start`.

Our own fixtures are still executed, because we wrote them.

## Adding a source

Add a `fetch_*` function to `scripts/fetch-corpora.sh` and a case in the `case` statement.
Keep to permissively licensed test corpora, and check the licence before adding: these files
are for local testing and must not be committed into this repository or shipped.

Candidates not yet pulled:

- `apache/incubator-kie-kie-tools` — BPMN/DMN behind the editors themselves
- `camunda/camunda-bpmn-model` — BPMN from a different vendor, good for parser robustness,
  though its DMN dialect differs from Kogito's

## Exactly what is not read

Measured by looking at the expression element inside every decision that renders blank.
**Thirteen kinds unhandled, one handled.**

| Expression element | decisions | blank | note |
|---|---:|---:|---|
| `literalExpression` | 4,225 | **4,225** | FEEL written directly — the dominant case by far |
| `invocation` | 253 | 253 | calls a business knowledge model with bindings |
| `context` | 249 | 249 | named entries, each its own expression |
| *(no expression element)* | 51 | 51 | a decision declared with no logic at all |
| `relation` | 45 | 45 | a small table of literal rows |
| `filter` | 25 | 25 | DMN 1.5 boxed filter |
| `for` | 23 | 23 | DMN 1.5 boxed iteration |
| `conditional` | 21 | 21 | DMN 1.5 boxed if/then/else |
| `every` | 12 | 12 | DMN 1.5 quantified |
| `some` | 12 | 12 | DMN 1.5 quantified |
| `iterator` | 10 | 10 | DMN 1.5 |
| `list` | 10 | 10 | |
| `functionDefinition` | 2 | 2 | a reusable function body |
| `decisionTable` | 418 | 2 | **the only kind we read** — 2 outliers worth a look |

So the "if/else, for-loops and filters" question has a precise answer: DMN 1.5 promoted those
to first-class boxed expressions (`conditional`, `for`, `filter`, `some`, `every`,
`iterator`), the conformance suite exercises all of them, and every one renders blank today.
They are individually small in number, but `literalExpression` alone is 86% of the problem —
and FEEL text inside it routinely contains the very if/else and filters being asked about.

## Exactly why 30 processes project as empty

All 30 have a start event *and* sequence flows. The steps are simply not reachable by
walking `sequenceFlow` from the start, because BPMN connects work in four ways and we only
follow one:

| Connection | Example files |
|---|---|
| **Nesting** — steps inside `<subProcess>`, which has its own start and flows | `BPMN2-CompositeProcessWithDIGraphical`, `BPMN2-InclusiveSplitAndJoinEmbedded` |
| **Attachment** — boundary events on an activity via `attachedToRef`, whose outgoing flow leads to handler steps | `BPMN2-ErrorBoundaryEventInterrupting`, `BPMN2-EscalationBoundaryEvent`, `BPMN2-ConditionalBoundaryEventInterrupting` |
| **Triggering** — event subprocesses, entered by signal rather than by a flow | `BPMN2-EventSubProcessWithLocalVariables`, `BPMN2-EventSubprocessSignalNested` |
| **Linking** — link throw/catch pairs that continue the flow by name | `BPMN2-LinkEventCompositeProcess` |

Each is the same defect as the gateway branch: a step the model is never shown is a step it
cannot be asked to change.

## What this is for next

The 92% figure is the target. The projection needs to handle every boxed expression kind —
`literalExpression`, `context`, `invocation`, `relation`, `list`, `functionDefinition` — and
show `requiredDecision` chains, with one rule holding it together: **never render a decision
with nothing**. An expression kind we cannot read should say so, because a blank is the one
thing the model will fill in for us.

Once that number is low, the survey stops being a report and becomes an assertion.
