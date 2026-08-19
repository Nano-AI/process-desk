# Demo Script

Two minutes, no jargon, every claim visible on screen. Written to be read aloud.

**Before starting:** back end, front end and Ollama all running (see README). Reset the
demo files, since a previous run will have saved its edits into them:

```bash
cp backend/src/test/resources/fixtures/*.bpmn backend/assets/
```

Then open `member-refund.bpmn`. Confirm the badge top-right reads `assistant: ornith:9b` — if it says
`placeholder`, the model is not connected and the demo loses its point.

---

### 1. What this is (15s)

> This is our process editor, with an assistant beside it. The assistant runs entirely on
> this laptop — no cloud, nothing leaves the machine. The badge in the corner is the model
> that is answering.

Point at the canvas: eight steps, a decision point, two approval branches.

### 2. Understanding it (20s)

Type: **"what does this process do?"**

> A person who has never seen one of these diagrams can now read it.

The answer names the steps in plain language. Nothing technical appears.

### 3. Changing it (45s)

Type: **"stick a quality check right after the submit step"**

Talk over the checks as they tick green:

> It is not editing the file yet. It worked out what I meant, made the change on a copy,
> and is now checking it three ways — that Kogito can still read it, that every connection
> points somewhere real, and that no step is left stranded.

When the change order appears:

> One sentence, in my words. Nothing has been saved.

Click **Show me** — the new step appears on the canvas, wired in.

Click **Apply change**.

> Saved. And the checks run again before anything touches disk, so a change can't slip
> through by the browser claiming it passed.

### 4. Decisions too (30s)

Switch the file dropdown to `refund-approval.dmn`.

Type: **"when does a refund need a manager?"**

> It reads decision rules as well as processes, and it answers from the actual thresholds in
> the table rather than describing it in general terms.

> What it is shown is not the file. For this decision it is about three hundred characters —
> what the decision needs to know and the rules it applies. No identifiers, no markup. That
> is why a model small enough to run on a laptop is enough.

Decision rules can be explained but not changed yet, and it says so if you ask.

### 5. Undo (10s)

Click **Undo**.

> Every change is one click away from being undone, and every proposal is written to an
> audit log: what was asked, what was proposed, whether it passed, and what the person
> decided.

> And the first of those three checks hands the file to Kogito's own parser — the same code
> a deployment uses. Our test suite goes further and actually runs the edited process on the
> engine, so a change that passes the checks is one Kogito can still execute.

---

## If something goes wrong

| Symptom | Cause | Say / do |
|---|---|---|
| Badge reads `placeholder` | `AI_PROVIDER` in `.env` is `dummy` (or unset) | set it to `ollama` or `gemini` and restart; the demo works either way, but the answers are canned |
| Badge reads `unavailable` | back end not running | start it; nothing works without it |
| First model answer takes ~15s | the local model is loading | say so — it stays warm for 30 minutes after |
| Ollama will not start | `OLLAMA_MODELS` points at an external drive | mount it, or start Ollama with `OLLAMA_MODELS="$HOME/.ollama/models"` |
| "No Gemini API key is set" | `.env` has no `GEMINI_API_KEY` | paste the key and restart, or switch `AI_PROVIDER` to `ollama` and demo locally |
| "Gemini is rate limiting this key" | free-tier requests-per-minute | wait a few seconds; or switch to `ollama` for the rest of the demo |
| Editor canvas is blank | the editor bundle failed to load | reload; the assistant and the checks still work in the text fallback |

## Questions you will get, and honest answers

**"What if the AI gets it wrong?"**
It can only name a step and a kind of change. It never writes the file, never chooses
identifiers, and never touches the diagram. A wrong answer is a rejected proposal, not a
damaged process. The schema it answers under is built from the open file, so it cannot even
name a step that does not exist.

**"How do we know the check is real?"**
The first check hands the file to Kogito's own parser. If it passes, Kogito can load the
file — that is the same code path a deployment would use. And you just watched Kogito run it.

**"Is our process data going anywhere?"**
No. The model runs on this machine. The only thing it is shown is a list of step names —
not the file. That is a deliberate design choice, and it is why a small model is enough.

**"Can it do more than add and rename?"**
Not yet. Each new kind of change is a small, testable addition — the safety machinery
around it already exists. Deleting is the obvious next one and needs a dependency check
first, because deletions ripple across files in a way additions do not.

**"What would it take to use this for real?"**
Sign-off to run the model on company hardware, and a decision about which processes are in
scope. The engineering is further along than the approvals.
