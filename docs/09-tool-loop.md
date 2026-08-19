# The Tool Loop

The assistant used to get one guess. It was shown a projection someone else had chosen, and
had to reply with one edit. Three requests failed in three different-looking ways, and all
three turned out to be the same cause:

```
"Please change the DTI for affordable to be < 0.15 and Marginal to be from 0.15 to 0.36"
→ "The DTI decision currently has no rules defined to change."
```

That answer was correct given what the model could see. `DTI` is both a decision — a formula,
which the projection renders as a name with nothing under it — and a column of *Affordability
Category*, which is the table the request meant. The model could not ask which, could not be
told that one of them had no rules, and could not have made both edits even if it had picked
right.

Now it can look, change, check its own work, and fix what it broke.

## What changed, and what did not

**The harness did not change.** The editor still writes the XML, `expect` still refuses a cell
that holds something else, and the four Kogito gates still decide what may be offered. A loop
widens what can be *attempted*; it does not widen what can be *written*.

**The protocol changed.** Five tools, and a bounded conversation:

| Tool | What it fixed |
|---|---|
| `list_decisions` | the DTI ambiguity — shows both, and which is a formula with no rules |
| `show_decision` | blank decisions; also deleted the BM25 narrowing, which existed only to guess what to show up front |
| `set_cell` | unchanged, but callable more than once — which is multi-edit |
| `add_rule` | "create a new criteria", which had no operation behind it at all |
| `check` | runs the gates mid-conversation, so the model sees the gap it just made |
| `done` / `answer` | explicit termination |

`check` is the one that matters most, and the reason is worth stating plainly: it converts a
refusal into a correction. Under the one-shot protocol, moving one boundary of a range table
left a hole beside it and the only available response was to refuse the edit. The docs called
this "requires a multi-edit planner". It required no planner.

## What it actually does

`gpt-oss:20b`, on the request at the top of this page:

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

Turns 4 to 6 are the whole argument for this design. Nobody planned the second edit; the model
was told what it had broken while it was still in a position to fix it.

Result: 65–150s, all four gates pass, both halves of the request made.

## What measuring it changed

Four things were wrong in the first version, and all four were found by running it rather than
by reasoning about it.

**The turn cap was exactly enough, and therefore too few.** The successful path above is eight
turns. The cap was eight. The first live run made one of the two edits, hit the cap, and had
its proposal withheld for the overlap that the second edit would have fixed. Now twelve.

**Temperature 0.1 was a coin flip.** Two runs of the same request took different paths — one
self-corrected, one ran out of turns. Choosing which tool to call is classification, and
sampling variance on a classification is pure downside. At 0 the same request produces the same
eight turns and the same summary. This is the second time this project has learned that; Gemini
was moved to 0 for the same reason.

**A missing capability became a hang.** "Add a new criteria to the Affordability Category
table" has no tool that can do it, so the model tried other tools until the HTTP timeout —
321 seconds, ending in "I couldn't reach the model", which is both untrue and unactionable. The
fix is in the prompt: the model is now told exactly what it cannot do (add or remove a rule,
add a column, create a decision) and to say so rather than keep trying. Same request, 29
seconds, "I can't add that new category."

**The model answered from what it knows instead of from the file.** Asked why a loan would be
declined, it produced a fluent, entirely plausible answer citing missing documentation and
recent bankruptcies. Neither appears anywhere in this model. The one-shot path could not make
this mistake, because the projection was in front of it whether it wanted it or not; a loop
lets the model skip looking. The prompt now requires it to look first, and says why:

> An answer that sounds right but names a rule this file does not have is the worst thing you
> can produce, because nobody can tell it is wrong.

After: LTV, reserves, DTI, credit score, income risk category — all of which are in the file.

## What running it against a real model taught, part two

Adding `add_rule` produced a second round of failures, and all three were in the *plumbing*
between the model and the harness rather than in either one.

**The model copied the column heading into the value.** `show_decision` rendered a rule as
`1. DTI >0.36 → "Not affordable"`, and the cell holds only `>0.36`. So `gpt-oss:20b` sent
`expect: "DTI >0.36"`, the editor refused, and it had no way to see why — the refusal said the
cell holds `>0.36`, `not DTI >0.36`, which reads like a distinction without a difference. Our
formatting caused it. Two fixes: rules now render `DTI: >0.36`, and the editor strips a leading
column name before comparing, since a caller carrying it has named the right cell.

**It repeated the identical failing call four times.** Turns 3, 4, 5 and 8 were byte-identical,
and the request burned 292 seconds before giving up with an invented excuse ("the table only
allows one rule per category"). None of these tools depend on anything a failed call changed, so
re-running one cannot answer differently. The loop now remembers each call and its result, and
answers a verbatim repeat with the original reason plus an instruction to do something else.

**It double-encoded its own arguments.** `done` came back as
`{"summary": "{\"summary\": \"Added a new category…\"}"}`, and that string is what the panel
shows as the description of the change being approved. Unwrapped now — one layer, and only when
the inner JSON actually contains the same field.

None of these are reasoning failures, and none would have been found by reading the code.

## Adding a rule

`add_rule` appends to a table. Two decisions inside it are worth stating:

**Quoting is inferred from the column.** FEEL reads `"Poor"` as a string and `Poor` as a
variable that does not exist, and the second does not compile. A new rule has no sibling cell in
its own row to copy from, so the column is asked instead. On the real file this produced
`(0.36..0.5]` bare and `"Poor"` quoted, in one rule, correctly.

**Appended, not inserted.** Under UNIQUE — every table in the lending model — order carries no
meaning. Under FIRST it is priority, and last place is the only position that cannot change what
an existing rule already decides.

Shape is enforced and never padded: a rule needs one condition per column, and a request that
supplies the wrong number is refused with the columns named. Whether the new rule *makes sense*
is a coverage question, and the coverage gate answers it afterwards against the whole table —
which it does, catching a deliberately careless rule that overlaps its neighbour.

## Costs

**Latency.** 65–150s against about 10s for one shot. Prefill dominates, not generation: at the
measured 126 tokens/second a turn spends more time reading the conversation than writing to it.
Streaming the tool calls to the panel would make the loop its own progress bar, which is the
obvious next piece of UI work.

**Quota, for hosted providers.** Eight turns is eight billed requests. Flash-lite's 500/day
becomes about 60 conversations. This is the architecture's one real cost, and it is the reason
it is aimed at the local model — see `08-choosing-a-model.md`.

**Nondeterminism is still there in principle.** Temperature 0 removed the variance that was
measured, but a loop has more ways to go wrong than a single call, and the benchmark in
`08-choosing-a-model.md` is what will actually characterise this. Four metrics, twenty requests:
gate pass rate, turns to completion, wrong-tool rate, and termination failures.

## Where it runs

Both providers implement `nextTurn` — Ollama's `tools`, Gemini's `functionDeclarations`, which
differ by field name and are absorbed by the provider seam. `DummyAiProvider` reports
`supportsTools() == false` and takes the one-shot path, which remains the floor every provider
meets. Turn it off entirely with `TOOL_LOOP=false`.

```
AI_PROVIDER=ollama OLLAMA_MODEL=gpt-oss:20b ./gradlew bootRun
```

18 tests cover the tools and the loop's control flow — including the cap, an invented tool
name, a reply with no tool call at all, and the half-finished edit that must not be offered.
The loop's tests use a scripted provider rather than a model, because what a real model chooses
varies per run: that belongs in the benchmark, not in a unit test.
