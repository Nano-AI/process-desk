import { useEffect, useRef, useState } from "react";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { askStreaming, type ProposeResponse } from "../llm/client";
import type { CatalogEntry } from "../graph/nodeCatalog";
import type { DecisionCatalog } from "../graph/decisionCatalog";
import { ProposalCard } from "./ProposalCard";
import { ActivityTrail, type Task } from "./ActivityTrail";

interface Msg {
  role: "user" | "assistant";
  text: string;
}

interface Props {
  fileName: string | null;
  getXml: () => Promise<string | null>;
  catalog: CatalogEntry[];
  decisions: DecisionCatalog | null;
  previewActive: boolean;
  onPreview: (xml: string, note?: string) => Promise<boolean>;
  onApply: (xml: string) => Promise<{ ok: boolean; message: string }>;
  onCancelPreview: () => void;
}

export function AssistantPanel({ fileName, getXml, catalog, decisions, previewActive, onPreview, onApply, onCancelPreview }: Props) {
  const [messages, setMessages] = useState<Msg[]>([]);
  const [input, setInput] = useState("");
  const [busy, setBusy] = useState(false);
  const [tasks, setTasks] = useState<Task[] | null>(null);
  const [proposal, setProposal] = useState<ProposeResponse | null>(null);
  const chatRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLTextAreaElement>(null);
  const [targetStep, setTargetStep] = useState("");

  useEffect(() => {
    chatRef.current?.scrollTo({ top: chatRef.current.scrollHeight, behavior: "smooth" });
  }, [messages, proposal, tasks]);

  const append = (msg: Msg) => setMessages((m) => [...m, msg]);


  /** Everything still spinning has finished; nothing is left mid-air when the next row lands. */
  const settle = () =>
    setTasks((current) =>
      current ? current.map((t) => (t.state === "running" ? { ...t, state: "done" } : t)) : current,
    );

  /**
   * The trail is built from what happened, never from what might.
   *
   * The old version declared six rows up front, three of them named after BPMN gates. Opening a
   * decision model meant "Checking every connection is valid" could never resolve, so it sat
   * there and was finally relabelled "not reached" — which read as the assistant skipping steps.
   * Now each row is appended as its step begins, and the checks at the end are whichever ones
   * this file's gates actually returned.
   */
  async function runAsk(message: string, xml: string) {
    setTasks([{ id: "read", label: "Reading the file", state: "running" }]);

    let seq = 0;
    const response = await askStreaming(message, xml, (step) => {
      settle();
      setTasks((current) => [
        ...(current ?? []),
        {
          id: `step-${seq++}`,
          label: step.label,
          detail: step.detail ?? undefined,
          state: "running",
        },
      ]);
    });
    settle();

    if (response.answer !== null || response.proposal === null) {
      append({ role: "assistant", text: response.answer ?? "I couldn't work out what you meant." });
      return;
    }

    const result = response.proposal;

    // Whatever this file's gates are. A decision model reports shape and coverage; a process
    // reports connections. Reading them off the response means neither has to be known here.
    setTasks((current) => [
      ...(current ?? []),
      ...result.gates.map((gate) => ({
        id: `gate-${gate.id}`,
        label: gate.label,
        detail: gate.detail,
        state: (gate.ok ? "done" : "failed") as Task["state"],
      })),
    ]);

    if (!result.explanation) {
      append({ role: "assistant", text: result.message ?? "I couldn't make that change." });
      return;
    }

    setProposal(result);
    if (!result.ok && result.message) {
      append({ role: "assistant", text: result.message });
    }
  }

  async function send(text: string) {
    const message = text.trim();
    if (!message || busy) return;
    setInput("");
    setProposal(null);
    setTasks(null);
    append({ role: "user", text: message });
    setBusy(true);

    const xml = (await getXml()) ?? "";

    try {
      await runAsk(message, xml);
    } catch {
      setTasks(null);
      append({
        role: "assistant",
        text: "I couldn't reach the server. Check that the backend is running, then try again.",
      });
    } finally {
      setBusy(false);
    }
  }

  async function applyProposal() {
    if (!proposal?.proposedXml) return;
    const { message } = await onApply(proposal.proposedXml);
    setProposal(null);
    setTasks(null);
    append({ role: "assistant", text: message });
  }

  function cancelProposal() {
    setProposal(null);
    setTasks(null);
    onCancelPreview();
    append({ role: "assistant", text: "Nothing was changed." });
  }

  const anchor = targetStep || catalog[0]?.name || "Submit Request";

  /**
   * Fills the box with a phrasing the system understands exactly, and selects the part the
   * user has to replace.
   *
   * <p>Selecting the placeholder is the point. The old chips inserted `Add "New Step"
   * after …` with the caret at the end, so a step literally called "New Step" was one
   * Enter away — and ended up saved in a real file. A template that pre-selects the words
   * you must change cannot be accepted by accident.
   */
  function useTemplate(text: string, placeholder: string) {
    setInput(text);
    requestAnimationFrame(() => {
      const box = inputRef.current;
      if (!box) return;
      box.focus();
      const at = text.indexOf(placeholder);
      if (at >= 0) box.setSelectionRange(at, at + placeholder.length);
    });
  }
  // Both kinds are editable, but in different vocabularies: a process has steps, a decision
  // has columns, conditions and outcomes. Offering "Add a step" on a decision would invite a
  // request the harness has no way to perform.
  const isDecision = Boolean(fileName && /\.dmn$/i.test(fileName));

  return (
    <aside className="panel" aria-label="Assistant">
      <div className="panel-header">
        <span className="eyebrow">Work order</span>
        <h2>Assistant</h2>
        <p>
          {isDecision
            ? "Describe a change to these rules in your own words. Nothing is saved until you approve it."
            : "Describe a change in your own words. Nothing is saved until you approve it."}
        </p>
      </div>

      <div className="chat" ref={chatRef}>
        {messages.length === 0 && (
          <div className="msg assistant">
            {isDecision
              ? "Ask how this decision works, or tell me a rule to change."
              : "Ask about this process, or tell me a change to make — rename a step, or add a new one."}
          </div>
        )}
        {messages.map((m, i) => (
          <div key={i} className={`msg ${m.role}`}>
            <Answer text={m.text} />
          </div>
        ))}
        {tasks && <ActivityTrail tasks={tasks} />}
      </div>

      {/* Kept out of the scrolling transcript so the decision is always on screen. */}
      {proposal?.explanation && (
        <div className="proposal-dock">
          <ProposalCard
            explanation={proposal.explanation}
            canApply={Boolean(proposal.ok && proposal.proposedXml)}
            previewActive={previewActive}
            onShowMe={async () => {
              if (!proposal.proposedXml) return;
              const shown = await onPreview(proposal.proposedXml, proposal.explanation ?? undefined);
              if (!shown) {
                append({
                  role: "assistant",
                  text: "I couldn't draw the preview on the canvas. You can still apply the change, or cancel it.",
                });
              }
            }}
            onApply={applyProposal}
            onCancel={cancelProposal}
          />
        </div>
      )}

      <div className="composer">
        <div className="chips">
          {isDecision ? (
            <button className="chip" onClick={() => send("What does this decide?")}>
              What does this decide?
            </button>
          ) : (
            <button className="chip" onClick={() => send("Explain this process")}>
              Explain this process
            </button>
          )}
        </div>
        {isDecision && decisions && decisions.decisions.length > 0 && (
          <div className="templates">
            {decisions.columns.length > 0 && decisions.conditions.length > 0 && (
              <button
                className="chip"
                onClick={() =>
                  useTemplate(
                    `Change ${decisions.columns[decisions.columns.length - 1]} from ${
                      decisions.conditions[decisions.conditions.length - 1]
                    } to "NEW VALUE"`,
                    "NEW VALUE",
                  )
                }
              >
                Change a condition
              </button>
            )}
            {decisions.outcomes.length > 0 && (
              <button
                className="chip"
                onClick={() =>
                  useTemplate(
                    `Change the ${decisions.outcomes[decisions.outcomes.length - 1]} outcome to "NEW OUTCOME"`,
                    "NEW OUTCOME",
                  )
                }
              >
                Change an outcome
              </button>
            )}
            <button
              className="chip"
              onClick={() =>
                useTemplate(`Rename the ${decisions.decisions[0]} decision to "NEW NAME"`, "NEW NAME")
              }
            >
              Rename the decision
            </button>
          </div>
        )}
        {!isDecision && catalog.length > 0 && (
          <div className="templates">
            <select
              aria-label="Step to change"
              value={targetStep}
              onChange={(e) => setTargetStep(e.target.value)}
            >
              <option value="">{`Step: ${catalog[0]?.name ?? "—"}`}</option>
              {catalog.map((c) => (
                <option key={c.id} value={c.name}>
                  {c.name}
                </option>
              ))}
            </select>
            <button
              className="chip"
              title="Understood exactly, with no waiting"
              onClick={() => useTemplate(`Add "NEW STEP" after "${anchor}"`, "NEW STEP")}
            >
              Add a step after it
            </button>
            <button
              className="chip"
              title="Understood exactly, with no waiting"
              onClick={() => useTemplate(`Rename "${anchor}" to "NEW NAME"`, "NEW NAME")}
            >
              Rename it
            </button>
          </div>
        )}
        <textarea
          ref={inputRef}
          className="composer-input"
          value={input}
          rows={3}
          placeholder={
            isDecision
              ? 'Ask about this decision, or describe a change\ne.g. Change Member Tier from "Executive" to "Gold"' 
              : 'Ask about this process, or describe a change\ne.g. Add "Quality Check" after "Submit Request"'
          }
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={(e) => {
            // Enter sends; Shift+Enter starts a new line.
            if (e.key === "Enter" && !e.shiftKey) {
              e.preventDefault();
              send(input);
            }
          }}
          disabled={busy}
          aria-label="Ask about this process, or describe a change"
        />
        <div className="composer-row">
          <span className="composer-hint">
            {"Or just describe the change in your own words."}
          </span>
          <button className="btn btn-primary" onClick={() => send(input)} disabled={busy || !input.trim()}>
            {busy ? "Working…" : "Ask"}
          </button>
        </div>
      </div>
    </aside>
  );
}

/**
 * Renders the assistant's reply as markdown.
 *
 * <p>The prompts ask for a lead sentence and a few short bullets, and the local models
 * mostly comply. They also reach for headings, numbered steps, fenced code and the odd
 * table when a question invites one, and the hand-rolled renderer this replaced handled
 * only bold, inline code and "- " bullets — so everything else arrived as literal "###"
 * and "1." in the panel, which reads as a transcript of a prompt rather than an answer.
 *
 * <p>Nothing in a reply can become markup. react-markdown builds a React element tree
 * rather than setting innerHTML, and no rehype-raw plugin is installed, so HTML in a
 * model's output stays text. That property was true of the renderer this replaces and is
 * worth keeping: the answer is untrusted, whichever provider produced it.
 */
export function Answer({ text }: { text: string }) {
  return (
    <ReactMarkdown
      remarkPlugins={[remarkGfm]}
      components={{
        // The bullets keep their existing look: the panel's answers are the only list
        // that has ever appeared here, and it is styled to match the change-order card.
        ul: ({ children }) => <ul className="answer-points">{children}</ul>,
        ol: ({ children }) => <ol className="answer-steps">{children}</ol>,
        // Headings in a chat bubble are decoration, not navigation. Every level renders
        // the same, because a model choosing "###" over "##" is not saying anything.
        h1: ({ children }) => <p className="answer-heading">{children}</p>,
        h2: ({ children }) => <p className="answer-heading">{children}</p>,
        h3: ({ children }) => <p className="answer-heading">{children}</p>,
        h4: ({ children }) => <p className="answer-heading">{children}</p>,
        h5: ({ children }) => <p className="answer-heading">{children}</p>,
        h6: ({ children }) => <p className="answer-heading">{children}</p>,
        // Wrapped so a wide table scrolls inside the bubble instead of stretching it.
        table: ({ children }) => (
          <div className="answer-table">
            <table>{children}</table>
          </div>
        ),
        // A model can put any URL here, so treat it as untrusted: noopener stops the new
        // tab reaching back through window.opener, and noreferrer keeps the local address
        // out of the request.
        a: ({ href, children }) => (
          <a href={href} target="_blank" rel="noopener noreferrer">
            {children}
          </a>
        ),
      }}
    >
      {text}
    </ReactMarkdown>
  );
}

