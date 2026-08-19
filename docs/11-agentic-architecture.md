# The agentic layer, end to end

A technical reference for the part of Process Desk that talks to a language model. It covers
what crosses the seam, what the model can and cannot do, how the loop is bounded, and where
every guard sits.

`09-tool-loop.md` explains *why* this design replaced the one that came before it, with the
measurements that forced each change. This document is the *what*: the contracts, the control
flow, and the invariants, in the order a request travels through them.

---

## 1. The one-sentence version

The model never sees a file and never writes one. It sees business vocabulary, and it replies
with either prose or a coordinate. Deterministic Java turns coordinates into XML, and Kogito
decides whether the result may be offered to a human.

Everything below is an elaboration of that sentence.

---

## 2. Two protocols, one harness

There are two ways a request can be served, and which one runs is decided by capability, not
by preference.

| | One-shot | Tool loop |
|---|---|---|
| Applies to | processes (BPMN), and decisions when tools are unavailable | decision models (DMN) |
| Model calls per request | 1 | 2 to 12 |
| Model sees | a projection chosen in advance | whatever it asks to see |
| Model may act | once | repeatedly, on an accumulating working copy |
| Can it check its own work | no | yes, via `check` |
| Entry point | `AiController.ask` | `DecisionToolLoop.run` |

The selection logic is three conditions in `AiController.ask`:

```java
if (DecisionProjection.looksLikeDecisionModel(xml)) {
    if (toolLoopEnabled && toolLoop.available()) {
        return viaToolLoop(request.message(), xml);
    }
    // ... one-shot decision path
}
// ... one-shot process path
```

`toolLoop.available()` delegates to `ai.supportsTools()`. `DummyAiProvider` returns false, so
it always takes the one-shot path. That path is the floor every provider meets, which is what
makes `TOOL_LOOP=false` a safe switch rather than an outage.

**Processes never use the loop.** A BPMN edit is `RENAME` or `ADD_AFTER` against a closed set
of step names, which one call answers. The loop exists for decision tables, where a single
request routinely needs two edits and the second one is only discoverable after the first.

---

## 3. The provider seam

`AiProvider` is the only interface between this application and any model. Five methods:

```java
String name();
Map<String, Object> usage();                                    // default: empty
String explain(String question, AssetProjection asset);
EditIntent interpret(String request, ProcessProjection process);
EditIntent interpretDecision(String request, DecisionProjection decision);
boolean supportsTools();                                        // default: false
Tools.Turn nextTurn(String system, List<Tools.Message> conversation, List<Tools.Spec> tools);
```

Three implementations ship: `DummyAiProvider` (answers from the file, no network),
`OllamaAiProvider` (local), `GeminiAiProvider` (hosted). Exactly one is active, selected by
`processdesk.ai-provider` through Spring's `@ConditionalOnProperty`.

What the seam guarantees: prompts live in `Prompts`, reply parsing lives in `ModelReplies`,
schemas live in `IntentSchema` and `Tools.SPECS`. A provider owns its transport and nothing
else. If Gemini and the local model were given different instructions, a difference in their
answers would tell you nothing about the models, which is the whole reason the shared pieces
are shared.

`nextTurn` translates but never decides. It does not run tools, choose when to stop, or touch
a file. `DecisionToolLoop` owns all of that, so two providers cannot drift into having
different ideas of what the assistant is allowed to do.

---

## 4. What the model actually receives

### 4.1 Projections, not files

An `AssetProjection` is the business-vocabulary view of an asset. For a process: its name and
the order work moves through it. For a decision model: what each decision needs to know and
the rules it applies, in order. Never XML, never identifiers, never diagram geometry.

For a decision file, the projection is roughly 315 characters against a 3.5 KB source. That
ratio is the point. On a hosted provider the projection is what leaves the machine, and it
carries nothing that would let a reader reconstruct the asset.

### 4.2 BM25 narrowing, on the one-shot path only

`DecisionProjection.focusedOn(request)` ranks decisions against the request text using Okapi
BM25 (`k1 = 1.5`, `b = 0.75`) over each decision's name, columns, values, and any prose the
modeller left behind. Decision models read as English once you look at them, so term overlap
is a better signal than it first appears, and it costs no model call and no quota.

Three guards, because dropping the decision someone meant is worse than sending too much:

1. A small model (six decisions or fewer) is never narrowed.
2. A decision the request names by name is kept whatever it scored.
3. A request matching nothing keeps everything, rather than silently keeping an arbitrary five.

Inspect the ranking for any request:

```bash
cd backend && ./gradlew focus -Pq="change the income risk category to high"
```

The tool loop does not use this at all. Narrowing existed only to guess what was worth showing
before the model had said anything; `list_decisions` and `show_decision` replaced the guess
with a question.

### 4.3 Constrained decoding, on the one-shot path only

`IntentSchema` builds a JSON schema per request whose enums are the open file's real names and
values. Ollama takes it as `format`, Gemini as `responseSchema` (translated by `GeminiSchema`).
Naming a step that does not exist is therefore not an error caught afterwards. It is an answer
the decoder cannot produce.

---

## 5. The tool contract

Nine tools, defined once in `DecisionToolLoop.SPECS` and translated per provider. The
descriptions below are what the model reads.

| Tool | Arguments | Returns | Mutates |
|---|---|---|---|
| `list_decisions` | none | every decision, rule count, columns, and whether it is a formula | no |
| `show_decision` | `decision` | columns, hit policy, numbered rules | no |
| `set_cell` | `decision`, `rule`, `column`, `expect`, `to` | edit summary, or a refusal naming the conflict | yes |
| `add_rule` | `decision`, `conditions[]`, `outcomes[]` | edit summary | yes |
| `rename_decision` | `from`, `to` | edit summary | yes |
| `calculate` | `formula`, `variable`, `values[]`, `variables[]` | one result per value | no |
| `check` | none | gate failures, or confirmation | no |
| `done` | `summary` | terminates with a proposal | no |
| `answer` | `text` | terminates with prose | no |

Tool descriptions are kept short on purpose. Every description is prefilled on every turn of
every conversation, and prefill is what the loop actually costs: at a measured 126 tokens per
second, a generous set of schemas is paid for again each time the model stops to think.

### 5.1 `list_decisions`

Exists because of one specific failure. In the lending model, `DTI` is both a decision (a
literal expression, which the old projection rendered as a name with nothing under it) and a
column of *Affordability Category*. Asked to change "the DTI for affordable", the model picked
the decision, found no rules, and said so. It was not wrong. It could not see that there was
anything else to pick.

```
"DTI" — a formula, not a table of rules. It cannot be edited rule by rule.
"Affordability Category" — 3 rules, looks at: DTI
```

### 5.2 `show_decision`

Renders the rules numbered from 1, and those numbers are the coordinates an edit uses.

```
"Income Risk Category" (hit policy UNIQUE)
Columns: Income Risk Score
  1. Income Risk Score: >70 → "Low"
  2. Income Risk Score: (10..70] → "Medium"
  3. Income Risk Score: <=10 → "High"
```

The colon after the column name is load-bearing. Rendered as `DTI >0.36`, a model copying
what it was shown sends `expect: "DTI >0.36"` against a cell that holds only `>0.36`, gets
refused, and cannot see why: the refusal reads as a distinction without a difference. Two
fixes went in together, and the second is a concession that our formatting caused the problem:
rules render `DTI: >0.36`, and `DecisionEditor.withoutColumnPrefix` strips a leading column
name before comparing, since a caller carrying it has named the right cell.

When the target is a literal expression, the tool says what it is rather than showing nothing:

```
"DTI" is a formula, not a table of rules: Debt / Income
It cannot be edited rule by rule. If a rule elsewhere tests this value, it will
appear as a column in that decision.
```

### 5.3 `set_cell` and the `expect` guard

The single shape every table edit takes. One cell, addressed by decision, rule number, and
column, with `"outcome"` naming the result column.

`expect` is what makes a model-supplied coordinate safe. The caller states what it believes
the cell holds, and the edit is refused if the cell holds anything else:

```
Rule 2 of "Affordability Category" has DTI set to [0.33..0.36], not <0.33.
Nothing was changed.
```

A miscounted rule therefore changes nothing instead of changing the wrong thing. The refusal
names both halves of the coordinate, which is what lets the model see which one was wrong and
retry rather than guess again.

This check is exact. It replaced a heuristic that asked whether the user's sentence mentioned
the value, which needed a special case for "under 18" against `<18` and was wrong at the edges
either way.

`set_cell` also replaced four separate operations that addressed a rule by its condition, by
its outcome, and by the two crossed pairs. Those were never four operations. They were a 2×2
of how you find the rule and which cell you write, and naming each combination separately
meant every new phrasing looked like a missing feature.

### 5.4 `calculate`

The model states the relationship; the arithmetic happens in `Formula` over `BigDecimal`. That
division of labour plays to what each side is reliable at, and the prompt is blunt about why
it matters:

> Nothing downstream checks whether a number you wrote is the number you meant: `expect` only
> confirms the value you are replacing, so a slip in a conversion passes every check and is
> wrong where nobody can see it.

Two modes, one tool, because they are the same operation. With `variable` and `values` it
converts a whole column in one call:

```
calculate(formula="(c * 9 / 5) + 32", variable="c", values=["20", "8"])
→ Using (c * 9 / 5) + 32: 20 → 68, 8 → 46.4
```

Without them it evaluates a single expression. `variables` carries fixed bindings written
`name=number`, which hold for every value put through the formula, so a conversion can carry
its constants and still run over a whole column.

A value that will not parse is named rather than skipped. A silently dropped input becomes a
rule that never gets converted, in a table that otherwise looks finished.

### 5.5 `check`

The tool that changes what the architecture can do. It runs the full gate set over the working
copy, mid-conversation, comparatively against the original.

```
After this change, nothing in "Affordability Category" covers ( 0.15 .. 0.33 ), so
those cases would get no answer at all. Moving one boundary usually means moving
the one next to it.
Fix this with further changes, or undo what you changed.
```

Under the one-shot protocol this exact situation could only ever produce a refusal, and the
old docs described the fix as "requires a multi-edit planner". It required no planner. It
required telling the model what it had broken while it was still in a position to fix it.

### 5.6 `done` and `answer`

Explicit termination, and the prompt is specific about picking between them from intent rather
than sentence shape: "I want to change the score for under 18s to -150" is a change, and "what
score do under 18s get?" is a question.

Both are required to write in the user's words. The instruction is absolute:

> In `done` and `answer`, never mention tools, rule numbers, columns, schemas, tables or DMN.

---

## 6. The loop, step by step

`DecisionToolLoop.run(request, xml, onStep)`. State is three things: a `DecisionWorkspace`
holding the original and the working copy, the conversation, and a map of calls already made.

```java
DecisionWorkspace workspace = new DecisionWorkspace(xml, editor, gates);
List<Tools.Message> conversation = new ArrayList<>();
conversation.add(Tools.Message.user(request));
Map<String, String> repeated = new LinkedHashMap<>();
```

Then, until `maxTurns`:

1. **Call the model.** `ai.nextTurn(Prompts.DECISION_TOOLS, conversation, SPECS)`. Any thrown
   exception ends the run as `Outcome.unreachable`, which is deliberately distinct from a
   refusal (see §8.5).

2. **No tool calls?** Treat the prose as the answer and finish. A small model that has decided
   not to call a tool will not be argued into one, and looping on it burns turns to reach the
   same place.

3. **Append the assistant turn**, prose and calls together. Both is legal: small models narrate
   before they act, and discarding a call because it arrived with a sentence attached would
   throw away the useful half.

4. **For each call, in order:**
   - Record the name for the benchmark.
   - `done` or `answer` sets `closing` and breaks out.
   - Emit a `Step` to the progress callback, described in the words of someone who has not seen
     the tools: `set_cell` becomes `Changing rule 2 of "Affordability Category"`.
   - Dispatch, unless this exact call was already made (§8.2).
   - Append the result as a `tool` message.

5. **Closing set?** Run `finish`.

6. **Cap reached?** If the workspace changed and the gates pass, offer the work anyway.
   Otherwise decline and say so. A silent cap is the worst outcome a loop can have.

`finish` runs the gates one final time and produces one of two shapes the UI already
understands: an answer, or a gated proposal. `proposedXml` is populated only when every gate
passes, so the UI is never handed a file it must not offer.

### 6.1 A real run

`gpt-oss:20b`, on "Please change the DTI for affordable to be < 0.15 and Marginal to be from
0.15 to 0.36":

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

Turns 4 through 6 are the argument for the whole design. Nobody planned the second edit.

That path is eight turns, which is why the cap is not eight. The first live run capped at 8,
having made one of the two edits, and had its proposal withheld for the overlap that the
second edit would have fixed. Twelve now.

---

## 7. The working copy

`DecisionWorkspace` holds `original` and `working`. Every mutating tool applies to `working`
and appends a human-readable line to `edits`.

This is what makes multi-part requests expressible. "Affordable under 0.15 and Marginal from
0.15 to 0.36" is two `set_cell` calls against an accumulating document, and `check` sees the
sum of them rather than either one alone.

Nothing here trusts the model. `setCell` still carries `expect` and is still refused when the
cell disagrees. The gates are still the arbiter of what may be offered. A loop widens what can
be *attempted*; it does not widen what can be *written*.

Tool results are returned as text, including refusals, and returned in the editor's own words.
An exception thrown out of a tool would end the conversation; a sentence explaining the
refusal is a turn the model can recover from.

---

## 8. Guards

Each of these exists because a specific model did a specific thing.

### 8.1 Turn cap

`processdesk.tool-loop.max-turns`, default 12 in `application.yml`. A model that never calls
`done` is a real failure mode of small local models, and an unbounded conversation spends a
quota or a laptop rather than admitting it is stuck.

### 8.2 Verbatim repeat detection

A call's identity is `name + "|" + arguments`. When the same signature comes back, the loop
answers from memory instead of dispatching:

```
You already tried exactly this and it did not work: <the original result>
Do not repeat it. Look at the decision again, change the values you are sending,
use a different tool, or finish and explain what you cannot do.
```

Repeating the original result matters. Without it the model has lost the reason its approach
failed and has nothing to reason from except the fact that it failed.

None of these tools depend on anything but the working copy, which a failed call did not
change, so re-running one cannot answer differently. `gpt-oss:20b` sent the same rejected
`set_cell` four times over 292 seconds before inventing an excuse for giving up.

### 8.3 Unknown tool names

A name outside the known set returns a sentence listing what exists. Naming what exists is a
recoverable turn; a stack trace is not. `Outcome.unknownToolCalls()` counts them, because it
is the clearest single signal that a model is lost, and the benchmark wants the number.

### 8.4 Argument coercion

Models fill schemas imperfectly in three reproducible ways, and `Tools.Call` absorbs all three
rather than treating a parsing problem as a comprehension problem:

- **Double encoding.** `{"summary": "{\"summary\": \"Added a new category…\"}"}` was measured
  from `gpt-oss:20b`. `text()` unwraps up to four layers, bounded so a value that genuinely is
  JSON about itself cannot spin. This one is user-visible: that string is what the panel shows
  as the description of the change being approved.
- **Scalar for array.** `strings()` accepts a bare value where a list is expected. A
  one-column table invites exactly that mistake, and the model said what it meant in a shape
  one element away from the schema.
- **String for integer.** `number()` parses `"3"`. A model told a rule number is an integer
  will still sometimes quote it.

### 8.5 Unreachable is not refused

`Outcome.unreachable` is a distinct terminal state, and the benchmark is the reason. A timeout
produces no proposal, which is exactly what a correct refusal produces. Without the
distinction, two crashed requests were scored as the assistant rightly declining to do the
impossible.

### 8.6 Prompt-level guards

Two failure modes have no code fix and are handled in `Prompts.DECISION_TOOLS`.

**Answering from world knowledge.** Asked why a loan would be declined, the model produced a
fluent answer citing missing documentation and recent bankruptcies. Neither appears anywhere
in the file. The one-shot path could not make this mistake, because the projection was in
front of it whether it wanted it or not. A loop lets the model skip looking.

> You know a great deal about lending and approvals in general, and none of it is in this
> file. An answer that sounds right but names a rule this file does not have is the worst
> thing you can produce, because nobody can tell it is wrong.

**Grinding on an impossible request.** "Add a new criteria to the Affordability Category
table" has no tool behind it, so the model tried other tools until the HTTP timeout: 321
seconds ending in "I couldn't reach the model", which is both untrue and unactionable. The
prompt now states the boundary explicitly (cannot remove a rule, add or remove a column, or
create a decision) and tells the model to say so. Same request, 29 seconds, "I can't add that
new category."

---

## 9. Gates

Gates are the last word, and they are the same gates whichever protocol produced the change.

**Processes** (`ValidationGates`, three):

1. **Structure** — Kogito's `XmlProcessReader` loads the file with the BPMN semantic modules.
2. **Connections** — every `sourceRef` and `targetRef` points at an element that exists.
3. **Rules** — every step has a way in and a way out; start and end events are connected.

**Decisions** (`DecisionGates`, four):

1. **Structure** — Kogito's DMN compiler builds the model and reports no `ERROR` messages.
2. **Shape** — every rule has exactly one condition per column and at least one outcome.
3. **Rules** — no decision table is left with nothing to apply.
4. **Coverage** — the change introduces no gap and no overlap.

The decision gates are deliberately not the process gates. Those hand the document to the BPMN
parser, which no DMN file will ever satisfy, so reusing them would mean a check that always
fails or, worse, one nobody noticed was meaningless.

### 9.1 Coverage, and why it is comparative

Moving a band from `<=10` to `<=20` while its neighbour still reads `(10..70]` means a score
of 15 matches both rules. Under a UNIQUE hit policy that is not a style problem; the table can
no longer say what the answer is. Gates 1 through 3 all pass it. Nothing is structurally
wrong. It just means something different from what the user asked for.

Kogito's own decision-table analyser finds it, from a dependency already on the classpath:

```java
validator.validate(new StringReader(xml),
        DMNValidator.Validation.VALIDATE_COMPILATION,
        DMNValidator.Validation.ANALYZE_DECISION_TABLE)
```

Findings are filtered to `ERROR` severity **or** any message type starting with
`DECISION_TABLE`. Severity alone checked half the problem: an overlap is an ERROR and a gap is
a WARN, so filtering on severity caught the overlap and missed the hole beside it.

The gate runs `analyse(before)` and `analyse(after)` and reports only what the change
introduced. Real decision models are not clean. The lending model in `assets/` ships with
twelve overlapping-rule errors, and a gate that failed on "the analyser reports errors" would
refuse every correct edit to it. A gate nobody can satisfy gets switched off.

### 9.2 Finding identity across a rename

Comparing findings by message text made renames catastrophic. The text names the table, so
renaming *Loan Recommendation* to *Lending Decision* re-reported all twelve pre-existing
errors as new, and a rename that changed no rule at all was refused.

```java
private static String signature(DMNMessage message) {
    // ...
    String subject = rules.find()
            ? "rules " + rules.group(1)
            : text.replaceAll("'[^']*'", "'…'");
    return message.getMessageType() + "|" + message.getSourceId() + "|" + subject;
}
```

Identity is the element the finding is about and the rules involved, neither of which a rename
touches. Compared by signature, reported by text.

### 9.3 Translating the analyser

The analyser's own wording is precise and unusable for the person this tool is for:
`Overlap values: [ "Low", "Affordable", [ "A" .. "D" ) ] for rules: [3, 7]`. A gap and an
overlap are different problems and get different sentences: one means the table gives two
answers, the other means it gives none.

When a proposal is withheld, the user is shown the failing gate's own detail. A gate goes to
the trouble of explaining itself, and replacing that with "this wouldn't be safe" throws away
the only part the user can act on.

---

## 10. Streaming

`POST /api/ai/ask/stream` returns newline-delimited JSON rather than server-sent events,
because the request carries the file and SSE is GET-only.

```json
{"type":"step","payload":{"label":"Reading the rules for \"Affordability Category\"","detail":null}}
{"type":"step","payload":{"label":"Changing rule 3 of \"Affordability Category\"","detail":"<0.15"}}
{"type":"step","payload":{"label":"Checking nothing else broke","detail":null}}
{"type":"result","payload":{"answer":null,"proposal":{...},"provider":"ornith:9b"}}
```

Each line is flushed individually. Buffering would deliver every step at the end, which is the
same as not having them. The final `result` holds exactly what `/ask` would have returned, so
a client that cannot stream loses the progress and nothing else.

**Steps are reported when a call starts, never announced in advance.** The loop does not know
its own next move, and a predicted list of steps is what left the old panel showing rows
marked "not reached".

Requests that cannot stream (a process file, or a provider without tools) emit a single
`result` line.

One configuration note that cost real debugging time: `spring.mvc.async.request-timeout` is
900000ms. The container default of 30 seconds is long enough that a short Gemini run slips
under it and looks fine, and far too short for anything else. Past it, the async thread is
interrupted mid-call and the HTTP send throws `InterruptedException` with no message, so the
panel reports "I couldn't reach the model" about a model that was answering normally. A local
model at 65 to 300 seconds a request would never once have succeeded.

---

## 11. Provider translation

The two tool-capable providers differ by field name, and that difference is absorbed entirely
inside `nextTurn`.

| Concept | Ollama | Gemini |
|---|---|---|
| Tool declaration | `tools[].function` | `tools[].functionDeclarations` |
| Model requests a call | `message.tool_calls[]` | a `functionCall` part |
| Result goes back as | a `tool` role message | a `functionResponse` part on a user turn |
| Result attribution | `tool_name` field | position in `parts` |
| Constrained decoding | `format` | `generationConfig.responseSchema` |
| System prompt | a `system` role message | `systemInstruction` |

Two provider-specific details worth knowing:

**Gemini thought signatures.** Some models attach an opaque `thoughtSignature` to a call and
require it back when the call is replayed in history. Gemini 3 rejects a conversation without
it: "Function call is missing a thought_signature in functionCall parts". It rides along on
`Tools.Call` even though nothing in this codebase can read it, and it is a sibling of
`functionCall` on the part, not a field inside it.

**Ollama prefix caching.** The whole conversation is resent every turn, which is how the API
works and is less wasteful than it looks: Ollama keeps the KV cache for a prefix it has
already seen, so only the newest messages are actually prefilled. That is the difference
between a turn costing about a second and costing ten.

---

## 12. Configuration

```yaml
processdesk:
  ai-provider: ${AI_PROVIDER:dummy}          # dummy | ollama | gemini
  tool-loop:
    enabled: ${TOOL_LOOP:true}
    max-turns: ${TOOL_LOOP_MAX_TURNS:12}
  ollama:
    model: ${OLLAMA_MODEL:ornith:9b}
    num-ctx: 8192
    temperature: 0
    keep-alive: 30m
    think: false
    timeout-seconds: 120
  gemini:
    model: ${GEMINI_MODEL:gemini-3.5-flash}
    api-key: ${GEMINI_API_KEY:}
    temperature: 0
    thinking-budget: 0
    daily-limit: ${GEMINI_DAILY_LIMIT:0}
```

Four of these are measurements rather than preferences:

**`temperature: 0`, both providers.** Choosing which tool to call and which rule number to
name is classification, and sampling variance on a classification is pure downside. At 0.1,
two runs of the same two-part DTI request took different paths: one self-corrected, one ran
out of turns. Prose is slightly flatter at 0; a coin flip on an edit is worse.

**`think: false`.** ornith is a reasoning model, and its private reasoning is tokens paid for
and never read. Measured on this hardware: 9 seconds with thinking off against 25 with it on,
same answers. The schema is the thinking.

**`thinking-budget: 0`.** Gemini thinks by default and charges latency for it on a request
whose whole job is picking one of four kinds. Measured on gemini-3.5-flash: 0.7s against 1.3s,
same answer. Not every model accepts the setting, so the provider drops it and retries once
rather than making you know which family you are on. `-1` omits it entirely.

**`num-ctx: 8192`.** Ollama defaults to 4096 and truncates silently.

`CallBudget` counts calls to a metered provider locally and reports them on `/api/health`,
because Google publishes no endpoint for what is left and the 429 is the first notification.
It resets at midnight America/Los_Angeles, not local midnight. A 429 is only treated as the
daily quota when the message names a `per_day` metric: free tiers cap per minute as well, and
a tool loop spending five to eight calls per request hits the minute cap while the day is
barely touched. Treating that as the day being gone wrote the counter to 501/500 after about
thirty real calls and disabled the provider until midnight.

---

## 13. Cost

**Latency.** 65 to 150 seconds against about 10 for one shot. Prefill dominates, not
generation: at 126 tokens per second a turn spends more time reading the conversation than
writing to it.

**Quota.** Each turn is one billed request. An eight-turn loop is eight of a hosted provider's
daily allowance, so flash-lite's 500/day is roughly 60 conversations. This is the
architecture's one real cost, and the reason it is aimed at the local model.

---

## 14. Audit

Every run is recorded to `backend/audit.log`, one JSON object per line:

```json
{"ts":"2026-08-19T09:00:00Z","event":"tool-loop","provider":"ornith:9b",
 "request":"change the DTI for affordable to be < 0.15","turns":8,
 "edits":["Changed DTI in rule 3 of \"Affordability Category\" from <0.33 to <0.15."],
 "passed":true}
```

Events: `tool-loop`, `proposed`, `proposed-decision`, `propose-failed`,
`propose-decision-failed`, `saved`, `save-rejected`.

The audit log deliberately does not track quota. It records proposals, so questions, refusals
and retries never appear in it, and those are the majority of calls.

---

## 15. Extending it

**Adding a tool.** Add a `Tools.Spec` to `SPECS`, a case to `dispatch`, the name to `KNOWN`,
a case to `describe` so the panel can label it, and the method to `DecisionWorkspace`. Return
text, never throw, and write the text for the model. Keep the description short, because it is
prefilled on every turn of every conversation forever.

**Adding a provider.** Implement `AiProvider`. Annotate with
`@ConditionalOnProperty(name = "processdesk.ai-provider", havingValue = "yours")`. Use the
shared `Prompts`, `ModelReplies`, and `IntentSchema`. If it can call tools, override
`supportsTools()` and `nextTurn`, and translate `Tools.Spec` into whatever the API calls a
tool. Do not run tools, decide when to stop, or touch a file.

**Adding a gate.** Add a method to `DecisionGates` or `ValidationGates` and include it in
`run`. Write the failure detail for a business user; it is shown verbatim in the UI. If the
check could fail on a file that was already imperfect, make it comparative, or it will refuse
correct edits to real models.

---

## 16. Tests

The loop's own tests use a scripted provider rather than a live model, covering the turn cap,
an invented tool name, a reply with no tool call at all, a verbatim repeat, and the
half-finished edit that must not be offered. What a real model chooses varies per run, so that
question belongs in the benchmark, not in a unit test.

```bash
cd backend && ./gradlew test        # 183 tests
```

The benchmark is separate and characterises models rather than code, on four metrics over
twenty requests: gate pass rate, turns to completion, wrong-tool rate, and termination
failures.

```bash
./gradlew bench
./gradlew focus -Pq="change the income risk category to high"
```

---

## See also

- `02-architecture.md` — the whole system, including the editor and the save path
- `08-choosing-a-model.md` — which models can actually drive this, and the measurements
- `09-tool-loop.md` — why the loop replaced the one-shot protocol
- `10-capability-gap.md` — what the assistant still cannot do
