# Working With a Real Decision Model

`backend/assets/loan-recommendation.dmn` is not ours. It is a lending model from the Drools
test suite (Apache 2.0) — 45 KB, 11 decisions, 44 rules, four chained inputs. It is in the
repository because our own fixture agrees with our own code, and a file we did not write is
the only kind that argues back.

It found four defects, and each one changed the design rather than just getting patched:
a vocabulary that could not express an ordinary request, a regex deciding what people meant,
a rename that broke every multi-decision model, and a change that passed every check while
leaving the table meaning something else. All are described below, because the defects are
the point.

---

## Picking it

`./scripts/fetch-corpora.sh` pulls 1,241 DMN files. Ranking them by decision tables and
rules, then running each candidate through the gates, narrowed it to one:

```bash
cd backend
./gradlew inspect -Pfile=assets/loan-recommendation.dmn
```

`inspect` prints exactly what the model would be shown and whether Kogito accepts the file.
It is the fastest way to try a file this project has never seen:

```
══ assets/loan-recommendation.dmn  (45 KB)
   gate structure: PASS
   gate shape: PASS
   gate rules: PASS
   decisions=11 blank=5 inputs=4 | vocab: columns=13 conditions=43 outcomes=24
   projection 4109 chars (~1027 tokens), file ~11706 tokens → 11x smaller
```

Candidates that lost, and why — each is a category, not a one-off:

| File | Outcome |
|---|---|
| `customer_discount_full.dmn` | 118 rules, but unquoted FEEL strings: `Unknown variable 'Large'`. Gate 1 rejects it, so no edit could ever be offered. |
| `Chapter 11 Example.dmn` | Imports a second DMN model that is not in the file. Gate 1 fails on the missing import. |
| `0004-lending.dmn` | Passes, but 10 of 11 decisions are `literalExpression` and render blank. |

## Migrating it to DMN 1.3

The file was DMN 1.1. The editor refused it:

```
DMN MARSHALLER: Upgrading from DMN 1.1 is not supported. Minimum version is 1.2.
```

Kogito's compiler reads 1.1 happily, so the backend gates passed and the editor still could
not open it — worth knowing, because it means **the gates are not a proxy for "the editor
will show this"**. Two different pieces of KIE with two different floors.

All assets are now DMN 1.3 (`20191111`), matching `refund-approval.dmn`. The migration is
two substitutions, and the second is the one that matters:

```python
'http://www.omg.org/spec/DMN/20151101/dmn.xsd' → 'https://www.omg.org/spec/DMN/20191111/MODEL/'
'http://www.omg.org/spec/FEEL/20140401'        → 'https://www.omg.org/spec/DMN/20191111/FEEL/'
typeRef="feel:number"                          → typeRef="number"
```

DMN 1.1 qualified built-in types with the FEEL prefix; 1.2 onwards writes them bare. Doing
only the namespaces produced a file that parsed and then failed to compile:

```
Unable to resolve type reference '{...}feel:string' on node 'tAffordability'
NullPointerException: ... because "toSet" is null
```

The gates caught it. That is the argument for having gate 1 be Kogito's own compiler rather
than a schema check: a namespace-valid file that the engine cannot use is exactly the
failure a schema check would wave through.

## What the model is shown

1,027 tokens, 11× smaller than the file. No identifiers, no namespaces, no diagram:

```
Decision model: Loan Recommendation2
Needs to know: Appraised Value, Loan, Borrower, Credit Score
Decides "Loan Recommendation":
  - Collateral Risk Category "Very low", Affordability Category "Affordable",
    Credit Risk Category "A","B","C","D" → "Approve"
  - Collateral Risk Category "Low", Affordability Category "Marginal",
    Credit Risk Category "A","B" → "Approve"
  ...
Decides "Income Risk Score":
  - Borrower.Age "<18", any Borrower.EmploymentStatus, ... → -100
  - Borrower.Age "[18..35]", ... → 30
```

Note the ratio: our small fixture projects 40× smaller, this one 11×. **Projection size
scales with rule count, not file size** — the rules are the part we keep. A model with
several hundred rules would need summarising rather than listing.

**Five of eleven decisions render blank.** They are `literalExpression`, which the
projection cannot read yet — the 92% gap measured in `06-test-corpora.md`. The assistant
answers questions about the six it can see and is silent about the rest, which is the
failure mode to be careful of: silence reads like absence.

## What it found

### 1. A rule could not be named the way people name it

```
Edit risk score fur under 18 to -50
→ "Editing outcomes for Income Risk Score is not supported by this tool."
```

The vocabulary had `SET_OUTCOME`, which addresses a rule **by the value it produces** — it
needed "change -100 to -50". The user named the rule **by its condition** and gave only the
new result, which is how people actually talk. The request was clear and inexpressible.

This was first fixed by adding a fifth intent kind. That worked and was the wrong shape —
see below. Today it is one cell edit, `rule 1, column outcome`:

```
[1.6s EDIT ✓✓✓✓] I want to change Income Risk score for under 18's to -150
      Changed the result in rule 1 of "Income Risk Score" from -100 to -150.
```

The typo is untouched and irrelevant — the model resolves the intent, and `<18` came from
the table's own vocabulary, not from the sentence.

**The guard this needed is gone, and that is the improvement.** A schema enum can only offer
values the table holds, so a request naming something absent used to come back as the nearest
legal value — and a `namedInRequest` check asked whether the user's sentence mentioned it,
which needed a special rule so "under 18" could match `<18` without also matching `[18..35]`.
All of that was a guess about the sentence. It is replaced by `expect`: the model states what
it believes the cell contains, and the editor refuses if the cell holds something else. Exact,
against the file, and it catches a miscounted rule as well as an invented value.

### 2. Intent was being classified by regular expression

A QE typed:

```
I want to change Income Risk score for under 18's to -150
```

The reply was a set of instructions for doing it by hand:

> Update the rule for borrowers under 18 by changing the score from "-100" to "-150".
> - Locate the rule where Borrower.Age <18.

**The model had read it correctly** — right rule, right current value, right new value — and
the answer was thrown away before anyone saw it. A regex decided the sentence was a question
because it opened with "I" rather than a verb, so the request went to the explainer and the
edit was never offered.

The regex was patched twice before this: once to allow a leading clause ("for under 18,
set…"), once to keep "does" from being read as an instruction. A third patch would have
been the wrong move. **Deciding what a person meant is the job the model is there to do**,
and on a model this capable, a hand-written classifier in front of it can only subtract.

Decision files now route the way process files always did: `interpretDecision` returns
`QUESTION` as one of its kinds, and the controller acts on that. `asksForAChange` and its
three supporting patterns are deleted, along with eight tests that only ever described the
regex's own behaviour.

| Request | Routed as | Why no regex could do this |
|---|---|---|
| `I want to change Income Risk score for under 18's to -150` | change | opens with a pronoun |
| `the score for under 18 should be -200` | change | no imperative verb at all |
| `can we make under 18s -75 instead` | change | reads as a question, is an instruction |
| `what score do under 18s get?` | question | |
| `why would a loan be declined?` | question | |

Five for five, where the previous rules got the first three wrong.

**The fast path stays, with a narrower brief.** `RequestPatterns` still recognises
"rename X to Y" and "add X after Y" without a model call, which matters when the free tier
allows twenty requests a day. The difference is that those patterns **verify their captures
against the open file** — a misread finds no such step and falls through to the model. A
question has nothing to check against, which is why the rule is now: *regex may recognise an
unambiguous edit it can verify; it may never decide that something is a question.*

The cost is one extra call per question, since classification and explanation are separate
turns. That is the right trade: a wasted call is cheap, and a change silently answered as a
question is not.

### 3. A change was answered as a question

```
for borrowers aged 18 to 35 set the risk score to 40
→ "The system automatically assigns a risk score based on borrower age..."
```

The change-verb test anchored at the start of the sentence. "For X, set Y" puts the rule
first and the verb second, so it was classified as a question and answered with a fluent
description of the very rule the user had asked to change — **which reads exactly like a
change that was made.** The worst shape of failure available: confident, relevant, and
nothing happened.

This was first fixed by widening the regex to allow a leading qualifying clause, then again
to stop "does" reading as an instruction. Both patches worked and neither was the answer;
the third failure above is what settled it. The regex is gone and the model routes. This one
is kept in the record because **the fix that works is not always the fix that is right** —
two rounds of patching a classifier bought less than deleting it did.

### 4. An edit that was structurally perfect and semantically wrong

Changing the "High" band from `<=10` to `<=20` produced this:

```
Income Risk Score  >70        → "Low"
Income Risk Score  (10..70]   → "Medium"
Income Risk Score  <=20       → "High"
```

A score of 15 now matches two rules, and the hit policy is UNIQUE — at most one rule may
match. The table can no longer say what the answer is. **All three gates passed it**: the
model compiles, every rule has the right number of cells, no decision is empty. Nothing was
structurally wrong. It simply meant something other than what was asked for.

The user's request implied a second edit — the neighbouring band has to become `(20..70]` —
and neither the model nor the harness noticed.

`kie-dmn-validation` was already a dependency and was never being used. Kogito's own
decision-table analyser finds this exactly:

```
DECISION_TABLE_HITPOLICY_RECOMMENDER :: Overlapping rules have different output value,
so the HitPolicy for decision table 'Income Risk Category' should be PRIORITY
```

Now a fourth gate. Two details that decided its shape:

**It compares, rather than judges.** Running the analyser on this file before any edit
returns **twelve** overlap errors, all in `Loan Recommendation`. A gate that failed on "the
analyser reports errors" would refuse every edit to this model, including correct ones — and
a gate nobody can satisfy is a gate that gets switched off. Only messages the change
*introduces* are reported.

**The refusal says what to do.** The analyser's own wording — `Overlap values: [ "Low",
"Affordable", [ "A" .. "D" ) ] for rules: [3, 7]` — is precise and unusable. What the user
now sees:

> After this change, two rules in "Income Risk Category" can both apply to the same case, so
> the table wouldn't say which answer wins. Check the neighbouring rule — changing one
> boundary usually means moving the one next to it.

That message is the failing gate's own explanation. It used to be replaced with "that change
would leave the decision model in a state Kogito can't use", which is true and useless.

**The gate now checks gaps too, and that changed what we know.** Asked again to make High
cover `<=20`, the model moved the *neighbouring* rule to `(20..70]` instead — half of the
correct pair, leaving 10–20 matching nothing. The analyser reports gaps at WARN and overlaps
at ERROR, so filtering on severity caught one and missed the other.

With both checked, the real finding is blunt: **almost any single boundary change on a range
table is an incomplete edit.** Two tests written as "this should pass" were wrong and the
gate was right. The assistant refuses the half-edit rather than offering it, which is correct
and not sufficient — the pair it needs (High to `<=5` *and* Medium to `(5..70]`) is one
intent more than a request can express. That is why multi-edit is now the first item in the
plan rather than the third.

## Try it

```bash
cp .env.example .env          # add a key, or set AI_PROVIDER=ollama
cd backend && ./gradlew bootRun
npm --prefix frontend run dev
```

Open `loan-recommendation.dmn` and ask:

| Request | What happens |
|---|---|
| `why would a loan be declined?` | answered from the rules it can see |
| `Edit risk score for under 18 to -50` | proposal, gated, previewed on the canvas |
| `for borrowers aged 18 to 35 set the risk score to 40` | same, rule found by its condition |
| `rename the Reserves Months decision to Cash Reserves` | rename, references updated with it |
| `Change Income Risk Category to High for values <= 20` | **refused** — it would leave a hole |
| `set the risk score for over 100 to 5` | refused — no rule holds that |

The canvas frames a proposal in blue and says **Proposed / Not saved yet**; applying it
turns the frame green and says **Saved — this is the file now**. A proposal and the saved
file render identically, so the state has to be carried by the frame rather than the
diagram.

## Adding your own

Any DMN 1.2+ file in `backend/assets/` appears in the picker. Check it first:

```bash
./gradlew inspect -Pfile=assets/your-file.dmn
```

If gate 1 fails, the assistant will never offer an edit for it — fix the file, not the tool.
If `blank=` is high, the decisions use expression kinds the projection cannot read yet, and
the assistant will be quiet about them rather than wrong.
