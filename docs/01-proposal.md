# AI Assistant for Kogito Process & Decision Editing — Proposal

**Author:** Adi Bankoti
**Status:** Draft for review — working MVP built
**Date:** August 2026

---

## Summary

A locally-hosted AI assistant integrated into our Kogito-based editor UI that lets
non-technical users understand and safely edit BPMN processes and DMN decisions using plain
language. All AI inference runs on-device — no data leaves the machine. The assistant never
writes to files directly: every change passes through a validation harness backed by Kogito's
own engines, with visual preview and one-click undo.

**Deliverables:**
1. Working demo: plain-language explanation and editing on the real editor UI ✅
2. This documentation set ✅
3. Benchmark data on edit reliability and latency 🔜
4. Local model connected in place of the current placeholder 🔜

## Problem

Editing BPMN/DMN assets today requires specialist knowledge: BPMN semantics, gateway logic,
decision-table structure, and how files reference each other. Business stakeholders who own
the processes cannot read or change them without a technical intermediary. Small edits —
rename a step, add an approval — cost a request cycle to a specialist.

## Users & Goal

- **Primary:** business analysts and process owners with no BPMN training
- **Goal:** such a user can (a) get a plain-language explanation of any process, and (b) make
  a simple structural edit unassisted, with the system guaranteeing the result is valid

## Solution Overview

A side panel in our host application, alongside the embedded KIE editor:

- **Explain** — "What does this process do?" in plain language, read from the live file.
- **Edit by sentence** — "Add a review step after Submit Request." The assistant shows each
  check as it completes, states the change in one sentence, draws it on the canvas for
  approval, and only then saves. Undo is one click.
- **Plain-language checks** — validation problems are phrased as "The 'Approve' step isn't
  connected to anything after it", never as validator codes.

The user never sees XML, JSON, or validator jargon.

## Data Governance & Security

The core design constraint, addressed first:

| Concern | Design answer |
|---|---|
| Data egress | **Zero in local mode**, which is the deployment this proposal recommends: ornith 9b runs via Ollama on `localhost`. No cloud API, no telemetry. A hosted provider (Gemini) exists behind the same seam for development speed — see below for exactly what it would send. |
| What could ever leave | Only a *projection*: a process's step names in flow order, or a decision table's rules. About 150 tokens. Never the file, the identifiers, the diagram, or the namespaces. Enforced by the seam's type signature — a provider is handed an `AssetProjection` and has no access to the asset — and covered by tests. |
| Uncontrolled writes | The model cannot write files, and never emits XML. It returns an *intent* ("rename this step to that"); a deterministic harness performs the edit, three gates validate it, and a human approves it on a visual preview before anything is saved. |
| Traceability | Append-only audit log: every proposal records the request, the intent, gate results, and the user's decision. |
| Reversibility | Editor-native undo; nothing auto-commits. |

**Open prerequisite:** IT/manager sign-off to run Ollama and a model on company hardware,
and confirmation of which machine hosts the demo. Development uses synthetic fixtures until
that is confirmed in writing.

**If a hosted model is used instead.** The provider seam makes this a configuration change,
not a rewrite, and the projection that crosses it is the same either way. Two things then
need answering before any real asset is opened:

1. **Which agreement covers this data class.** Decision logic is business policy — "refunds
   over $50 need manager approval" is a real rule, not a sample. The organization being approved for
   Gemini generally is not the same as being approved to send process definitions.
2. **Which tier.** Free-tier AI Studio terms permit Google to use the input to improve its
   products; paid-tier terms do not. Fixture files only on a free-tier key.

Both are the same conversation already owed for Ollama, with a different answer at the end.
The design does not depend on which way it goes, which is the reason it was built behind a
seam.

## Technical Approach

**Backend: Spring Boot 3.5 + Gradle, Java 21.** Kogito is a Java framework, so a Java
backend lets the application use Kogito's real engines rather than reimplementing anything:

- BPMN files are parsed by **Kogito's own parser** — the Structure check passing means Kogito
  can load the file, not merely that the XML is well-formed.
- The test suite **executes edited processes on the Kogito engine** and evaluates decisions
  on its DMN engine, so a change that passes the gates is demonstrably still runnable.
- The deployable jar bundles both engines.

**Frontend: React + the current KIE standalone editors** (`@kie-tools/bpmn-editor-standalone`
and `@kie-tools/dmn-editor-standalone`, 10.2.0), consumed as published packages. The AI layer
integrates only through the editors' public API — `getContent`, `setContent`, `undo`,
`subscribeToContentChanges`. Zero forks, zero patches; the integration survives Kogito
upgrades.

**The model's role is deliberately narrow.** It turns a sentence into an intent expressed in
step *names*; it never sees identifiers, never emits XML, and never evaluates decision logic
(Kogito does that exactly, instantly, and for free). This keeps model output to roughly 50
tokens, which is what makes a 9B model on a laptop a sensible choice rather than a compromise.

## Success Metrics

1. A business user with no BPMN training completes a real edit unassisted.
2. ≥90% of proposed edits pass all validation gates on first or second attempt, over a
   20-request suite.
3. A simple change takes minutes, with no specialist in the loop.

## Risks & Mitigations

| Risk | Mitigation |
|---|---|
| A 9B model proposes something wrong | It can only name a step; an unknown name is rejected with the real list. Structural correctness is the harness's job, not the model's. |
| Local inference is slow | Output is ~50 tokens per edit, not a rewritten file. Explanations stream; edits show per-check progress. |
| Editor API too limited | Every capability needed is verified against the published API. |
| IT approval delayed | Development proceeds on fixtures; approval is a week-0 task. |
| Limited side-project time | Phased scope with explicit cut lines; each phase demos standalone. |

## Why Local / Why This Model

**ornith 9b** — 5.6 GB, 256K context, MIT licence, trained for agentic coding; fits a
standard 32 GB laptop. Local-first removes the entire cloud-LLM approval surface (data
residency, vendor terms, per-token cost) for this evaluation. If the approach proves out, a
shared internal server running the 35B variant is the natural next step — but note that the
narrow model role means a bigger model is an upgrade, not a requirement.
