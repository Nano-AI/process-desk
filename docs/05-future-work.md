# Future Work and Reference Notes

What is deliberately not built, what it would take, and the reference material worth having
open when it is. Written for whoever picks this up next — possibly you in three weeks.

---

## 1. Widening the vocabulary

The model answers with an `EditIntent`: `RENAME`, `ADD_AFTER`, `QUESTION`, `NONE`. Each new
kind of change is four small pieces:

1. an enum value in `AiProvider.EditIntent.Kind`
2. a method on `ProcessEditor` that performs it (owning identifiers and geometry, as the
   existing ones do)
3. a branch in `AiController.applyIntent`
4. tests, including at least one that proves Kogito still runs the edited process

`IntentSchema` picks up the new kind automatically — it reads the enum.

### `SET_PROPERTY` — the one a real request needed

"Increase the cost of hotel search" cannot be expressed as rename or add. It needs a target
step, a property name, and a value.

**Open question before writing it:** BPMN tasks have no "cost" field. Where such a value
lives in a real production model decides the implementation — a process variable, a
`<bpmn2:property>`, an extension element, or a field on a linked DMN decision. Answer that
first; the code is easy once it is known.

### `DELETE` — needs the dependency graph first

Deleting a step ripples: sequence flows to rewire, and possibly references from other files.
Do not ship it before §2, and gate it behind an impact check that names what else is
affected.

## 2. Dependency graph

Kogito assets reference each other explicitly, so this is parsing, not inference:

| Source | Reference |
|---|---|
| DMN `<import>` | DMN → DMN |
| BPMN `callActivity` | process → subprocess, by process id |
| BPMN `businessRuleTask` | process → DMN decision |
| BPMN `serviceTask` | process → Java class |

Powers "what breaks if I change this?", makes `DELETE` safe, and gives the assistant
cross-file context. Deterministic; no model involved.

## 3. BM25 retrieval — deferred, with the trigger written down

Not built, and **not needed yet**. The open file's step names are a closed set of a handful
of strings, so matching them exactly beats ranking them statistically. Build it when one of
these becomes true:

| Trigger | What BM25 would do |
|---|---|
| a single process exceeds ~50 steps | shortlist candidates into the schema enum, which cannot hold hundreds |
| the repository holds many process files | answer "which file is hotel search in?" before anything is opened |
| there is a markdown corpus to answer from | retrieve policy text for `explain` |

Note **BM25 is lexical, not semantic**: it will not match "cost" to "price". Synonym
matching needs embeddings (`nomic-embed-text` runs locally through the same Ollama).

Implementation sketch when the time comes: hand-rolled, roughly 80 lines, k1=1.2, b=0.75,
in-memory. Not Lucene — the corpus is small and a scoring function you can explain in a
design review beats a 10 MB dependency doing the same thing opaquely.

## 4. Streaming explanations

`explain` takes about 4 seconds and arrives all at once. Ollama supports `stream: true`;
the work is plumbing Server-Sent Events through `/api/ai/ask` and appending tokens in
`AssistantPanel`. Deliberately deferred: the activity trail already shows the wait is
productive, so this is polish rather than a fix.

`interpret` must stay non-streaming — structured output requires the whole response.

## 5. SceSim test scenarios

`.scesim` files open as text. KIE publishes **no** `@kie-tools/scesim-editor-standalone`;
only `@kie-tools/scesim-editor`, a React component with a different integration shape
(mounted directly rather than through an envelope iframe). `editorApi.ts` already routes by
extension and has the branch ready.

Worth doing if the demo needs to show test scenarios; skip otherwise.

## 5c. Reading decision logic that is not a table — the urgent one

Measured on 1,241 corpus files: **92% of decisions are shown to the model with no logic at
all**, because `DecisionProjection` only reads `<decisionTable>`. `literalExpression` alone
appears in 917 files against 381 for decision tables.

The consequence is not a gap, it is a fabrication: asked how a `literalExpression` decision
works, ornith invented a scoring scheme that appears nowhere in the file. A blank in a prompt
is an invitation.

Thirteen kinds are unhandled and one is handled. In corpus order:

| Element | Blank | Render as |
|---|---:|---|
| `literalExpression` | 4,225 | the FEEL text as written |
| `invocation` | 253 | the invoked model and its bindings |
| `context` | 249 | each entry as `name = expression` |
| `relation` | 45 | a small table of rows |
| `filter` | 25 | the collection and its condition |
| `for` | 23 | the iterator, its range and its body |
| `conditional` | 21 | if / then / else |
| `some`, `every` | 24 | the quantifier and its condition |
| `iterator` | 10 | the iteration |
| `list` | 10 | the items |
| `functionDefinition` | 2 | parameters and body |
| *(none)* | 51 | say the decision declares no logic |

`filter`, `for`, `conditional`, `some`, `every` and `iterator` are DMN 1.5 boxed
expressions; the conformance suite exercises all of them. Start with `literalExpression` —
on its own it is 86% of the gap.

Plus `requiredDecision` edges so a chain reads in order — 268 corpus files chain decisions.

**The rule that matters:** never render a decision with nothing. An unrecognised kind must
say "this decision uses a form of logic I can't read yet", because that is a sentence the
model will repeat, whereas a blank is one it will fill.

Progress is measured by `./gradlew surveyCorpora`; when the figure is low it becomes a test.

## 6. Known limitations, stated plainly

- **Preview runs complete every work item immediately.** `CompleteImmediatelyHandler` makes
  a run show the *path* work takes, not what the work does. A real deployment registers
  handlers that call systems and assign human tasks. Anything depending on a handler's
  output is not realistic in a preview.
- **Gateway conditions are not evaluated from real data.** `member-refund.bpmn` uses a
  default flow, so a run always takes the automatic branch. Driving the branch from process
  variables needs those variables declared and passed in.
- **`stepNamesByDepth` orders by shortest path from the start.** For a loop or a complex
  join this is an approximation of "order of work", not a guarantee.
- **Only sequence flows are followed.** BPMN connects work four ways and we walk one, so
  steps reached by *nesting* (`subProcess`), *attachment* (boundary events via
  `attachedToRef`), *triggering* (event subprocesses) or *linking* (link throw/catch) are
  invisible — 30 corpus files, 8% of those containing steps. Each needs the walk extended:
  recurse into subprocess contents, follow boundary events from the activity they are
  attached to, treat event subprocess starts as roots, and pair link events by name.
- **No frontend tests.** The React side is verified by hand in a browser. If this grows,
  Vitest plus Testing Library on `AssistantPanel` and `TryItPanel` is the first thing to add.
- **Single user, no auth, files on local disk.**

## 7. Reference notes worth keeping

Things that cost time to discover and are not in any documentation:

**Kogito 10.2.0 API**
- `BpmnProcess.from(Resource...)` existed in 10.1.0 and is **gone** in 10.2.0. Use
  `new XmlProcessReader(modules, cl).read(...)` then `new BpmnProcess(definition, config, application)`.
- Starting a process needs the config reachable *both* ways:
  `new StaticApplication(new StaticConfig(Addons.EMTPY, processConfig))`. A bare
  `new StaticApplication()` leaves `config()` null and `activate()` throws.
- Work item handlers are registered by walking `WorkItemHandlerConfig.names()`, so a type
  must be *named* there, not merely resolvable by `forName`. A plain `<task>` has work item
  type `""` — register the empty string explicitly.
- `DoNothingWorkItemHandler` does not complete the item; it waits forever. To run a process
  through, return a `complete` transition from `activateWorkItemHandler`.
- `<exclusiveGateway>` requires `gatewayDirection="Diverging"` / `"Converging"`, or parsing
  fails with `Unknown gateway direction: null`.
- `afterNodeTriggered` fires in *post-order* — it walks back out of the process. Use
  `beforeNodeTriggered` for entry order.

**KIE editors**
- The current packages are `@kie-tools/bpmn-editor-standalone` and
  `@kie-tools/dmn-editor-standalone` (10.2.0). `@kie-tools/kie-editors-standalone` is the
  superseded one.
- `getContent()` returns `""` until the envelope finishes starting. Fall back to the loaded
  file rather than trusting an empty string.
- Open the editor from a **callback ref**, not an effect: the container div is created by
  the same render that opens the file.
- Do not clear the editor handle inside an async open path. React StrictMode double-invokes
  effects, and a second `open()` racing the first wipes a handle the editor already
  registered, with nothing left to re-register it.

**Regex fast paths**
- Verify what a pattern captured before acting on it. Two bugs came from patterns that were
  confidently wrong: one treated "can you call the approval step X instead" as a question,
  the other read "add a step after Y called X" as an edit to a step named `Y called X`.
  Both were instant and wrong, and in both cases the model would have got it right.
- The fall-through belongs to the model, not to an error message shown to the user.
- **A pattern may recognise an edit it can verify; it may never decide that a sentence is a
  question.** Verification is the whole distinction: a captured step name is checked against
  the open file, so a misread falls through. Nothing checks a classification. Every regex
  that tried to classify intent was eventually wrong, and each fix widened it until it was
  wrong in a new way — "I want to change the score for under 18s to -150" opens with a
  pronoun and has no imperative verb, and no amount of patching reaches it. Deleting the
  classifier and letting the model answer `QUESTION` fixed five phrasings at once, three of
  which the regex had got wrong.
- Keep the fast path for what it is: a cache for sentences that are already an intent,
  valuable because a metered provider allows twenty requests a day. Not a substitute for
  understanding.

**Templates and placeholders**
- A template that inserts placeholder text must *select* it. Left unselected, "New Step" was
  applied verbatim to a real file — the button had made the wrong thing the default.

**Ollama with a small model**
- Mark **every** schema field required. With only `kind` required, ornith 9b returns
  `{"kind":"RENAME","targetStepName":"..."}` and stops — a half-formed edit.
- Set `think: false` on reasoning models. Measured here: 25s against 9s, identical answers.
- `keep_alive: 30m`, or the model unloads after five minutes and the next request reloads it.
- `num_ctx` defaults to 4096 and silently truncates.
- `OLLAMA_MODELS` on this machine points at an external SSD; Ollama will not start unless it
  is mounted.

**One operation instead of four — done, and what it cost**

- `SET_CONDITION`, `SET_OUTCOME`, `SET_OUTCOME_WHERE`, `SET_CONDITION_WHERE` were never four
  operations. They were a 2×2 of *how you find the rule* (by its condition, by its outcome)
  and *which cell you write*. Modelling that as four names meant every new phrasing looked
  like a missing feature: three were added reactively in one day, and the fourth combination
  was still a refusal until a user hit it.
- Replaced by one: decision, rule number, column, `expect`, `to`. The projection numbers its
  rules, so the model reads a coordinate off it. Turning "make the High band 20 or less" into
  a coordinate is language work; turning a coordinate into XML is not.
- **`expect` is the real win.** The model states what it believes is in the cell and the edit
  is refused if it holds anything else, so a miscounted rule changes nothing. That deleted
  `namedInRequest`, `requestMentionsCondition` and the digit-matching rule that existed only
  to let "under 18" match `<18` — a heuristic about the user's sentence, replaced by an exact
  check against the file.
- **A capability was lost and should be recorded:** one intent now writes one cell. "Change
  every Approve to Approve with conditions" used to rewrite six rules; it now needs six
  edits. The natural extension is a rule selector rather than a number, and it belongs with
  the multi-edit work rather than as another kind.
- The four kinds also produced a coin-flip: at temperature 0.1 the same request chose
  `SET_CONDITION_WHERE` twice and `SET_CONDITION` once. Temperature 0 hid that; the collapse
  removes the choice that was being got wrong.

**Coverage, and why multi-edit is now required rather than nice**

- The gate checks gaps as well as overlaps, and that changed what the project knows about
  itself: **almost any single boundary change on a range table is an incomplete edit.**
  `<=10 → <=5` leaves `(5..10]` matching nothing; `<=10 → <=20` makes 15 match two rules.
  Two tests written as "this should pass" were wrong, and the gate was right.
- Asked to make High cover `<=20`, the model moved the *neighbouring* rule to `(20..70]`
  instead — half of the correct pair, and a hole where the other half should be. It is not a
  reasoning failure; one intent cannot express two edits.
- Gaps are reported at WARN and overlaps at ERROR, so a severity filter catches one and
  misses the other. Filter on the analyser's own message types instead.
- Findings must be identified by something a rename does not change. Comparing on message
  text made renaming a table look like it had introduced all twelve overlaps the file already
  had, and a rename that changed no rule at all was refused.

**BM25 over decisions — built, and what it did and did not solve**

- It works because these files are English underneath. Decision names, column headings,
  outcome values, `<description>` elements and the FEEL inside a `literalExpression` all read
  as words, and a request uses the same ones. Term overlap was written off here early and
  that was wrong.
- Splitting compound identifiers matters more than the ranking maths: `Borrower.EmploymentStatus`
  has to become `borrower employment status` before "employment status" can reach it.
- The idf term is floored at a small positive. Textbook BM25 goes negative for a term in more
  than half the documents, and with eleven decisions that makes "risk" count *against* a
  match — evidence of the opposite of what it is.
- It solves projection size and vocabulary pollution. It does **not** help with anything
  arithmetic: retrieval cannot see that `<=20` and `(10..70]` overlap. That is the analyser's
  job, and the two are complementary rather than alternatives.
- Next use, unbuilt: the same ranker over the markdown docs, which is where it was originally
  proposed. The decision case is the harder one and it is done.

**Temperature**

- Set to **0**, not 0.1. Choosing which kind of edit a sentence describes is a
  classification, and sampling variance on a classification is pure downside. Measured on one
  request at 0.1: two runs chose `SET_CONDITION_WHERE` and produced the edit, the third chose
  `SET_CONDITION` and was refused. Same words, same file, different answer.
- This is a mitigation, not a fix. The real fragility is that four kinds overlap enough for a
  model to pick between them at all — see the note on collapsing them.

**Gemini**
- **Models are retired, and a key issued afterwards gets a 404, not a warning.**
  `gemini-2.5-flash` was the first default here and answered *"no longer available to new
  users"* on a key made the same day — while still appearing in `ListModels`. So the list
  endpoint is not proof a model is callable. Pin a current model in `.env` and expect to
  move it; the 404 message quotes the API's own wording, because that wording is the entire
  diagnosis and swallowing it sends people hunting for a bug in the code.
- **`thinkingConfig` is accepted by some models and 400s on others.** Measured on one key:
  `gemini-3.5-flash` takes `thinkingBudget: 0` (0.7s and no thought tokens, against 1.3s
  and 81 with it left on); `gemini-3.5-flash-lite` and `gemini-flash-latest` both answer
  "Request contains an invalid argument". Since it is an optimisation and not a requirement,
  the provider drops it and retries once rather than making the user know which family they
  are on.
- **Pick the model by its quota, not only by its quality.** Free-tier limits differ by 25×
  between families: flash-lite is 500 requests/day and 15/minute, the full Flash models are
  20/day and 5/minute. Quota is per project *per model*, so switching gives a separate
  bucket — which is also how work continued after exhausting one. Requests-per-minute is
  what a burst meets first: five is a single back-to-back batch, and a question costs two
  calls. Tokens never bind here, at 250K/minute against a projection of 150–1,000.
- **The dashboard lags.** It reported 3/20 requests for a day on which the API had already
  answered 429 for that model. Useful as a record of limits, not as a warning that one is
  approaching — `/api/health` counts what this application spends, live.
- **A stronger model makes different mistakes, not fewer.** Gemini refused "delete the
  approval step" with "Only RENAME, ADD_AFTER, and QUESTION operations are allowed" — the
  exact technical vocabulary this project exists to remove. The constraint that matters is
  on what reaches the user, and it does not get to relax because the model got better.
- `responseSchema` is an OpenAPI 3.0 subset, not JSON Schema. Types are upper case, and an
  unrecognised keyword is a 400 rather than something ignored — `maxLength` is the one that
  bites, since it is exactly what a small model needed. `GeminiSchema` translates and drops
  rather than maintaining a second schema, so the enum the model is constrained to cannot
  drift from the vocabulary the editor will accept.
- `thinkingBudget: 0` is the counterpart of `think: false`. Leaving it on can also produce
  an empty answer with `finishReason: MAX_TOKENS` — the budget went on reasoning and none
  was left for the reply, which reads like a bug and is a setting.
- `propertyOrdering` decides generation order. Put `kind` first: the choice of edit should
  be made before the values that depend on it.
- The key belongs in a header, not the URL. URLs end up in proxy logs and shell history, and
  a key in either is a key to rotate.

**Choosing a size** — generation is memory-bandwidth bound, so time per answer tracks the
weight bytes almost linearly. Measured 9b: ~16 tok/s, ~10s per edit. Estimated from the same
bandwidth: 18b ≈ 8 tok/s (~18s), 27b ≈ 5 tok/s (~25s). An agentic layer multiplies that by
the number of calls in a plan, which is what rules out the larger local models here rather
than any question of quality. Measure before trusting the estimates: `OLLAMA_MODEL` in
`.env` and the four verified decision cases take ten minutes.

**Measured on this hardware** (Ryzen AI 7 PRO 350, 32 GB, CPU inference): 12.6 tokens/second;
4–8s per model-backed request; 0.0s when a regular expression handles it.
