import { useCallback, useEffect, useRef, useState } from "react";
import { EditorContainer } from "./editor/EditorContainer";
import type { EditorHandle } from "./editor/editorApi";
import { AssistantPanel } from "./assistant/AssistantPanel";
import { buildNodeCatalog, type CatalogEntry } from "./graph/nodeCatalog";
import { buildDecisionCatalog, type DecisionCatalog } from "./graph/decisionCatalog";
import { health, listAssets, loadAsset, saveAsset, type Health } from "./llm/client";

export function App() {
  const [assets, setAssets] = useState<{ name: string }[]>([]);
  const [openName, setOpenName] = useState<string | null>(null);
  const [openXml, setOpenXml] = useState<string | null>(null);
  const [catalog, setCatalog] = useState<CatalogEntry[]>([]);
  const [decisions, setDecisions] = useState<DecisionCatalog | null>(null);
  const [dirty, setDirty] = useState(false);
  const [previewActive, setPreviewActive] = useState(false);
  // What the canvas is currently showing that the file does not, in the assistant's own
  // words. Shown on the canvas itself: the editor looks identical whether it holds the
  // saved file or a proposal, and that is the one thing a user must not have to guess.
  const [previewNote, setPreviewNote] = useState<string | null>(null);
  const [justSaved, setJustSaved] = useState(false);
  const [banner, setBanner] = useState<string | null>(null);
  const [status, setStatus] = useState<Health | null>(null);
  const editorRef = useRef<EditorHandle | null>(null);

  useEffect(() => {
    health()
      .then(setStatus)
      .catch(() => setStatus({ ok: false, provider: "unavailable" }));
    listAssets()
      .then((found) => {
        setAssets(found);
        const first = found.find((a) => a.name.endsWith(".bpmn"));
        if (first) open(first.name);
      })
      .catch(() => setBanner("Couldn't reach the server. Start the backend and reload."));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // The badge reports what the local model has spent on prefill, which only changes while a
  // request is running. Ten seconds is often enough to watch a number move during a long tool
  // loop and rare enough that a call this cheap does not need thinking about.
  useEffect(() => {
    const timer = setInterval(() => {
      health().then(setStatus).catch(() => undefined);
    }, 10000);
    return () => clearInterval(timer);
  }, []);

  async function open(name: string) {
    const xml = await loadAsset(name);
    // The handle is not cleared here: opening is async, so a second call racing the
    // first would otherwise wipe a handle the editor had already registered, and
    // nothing would re-register it. EditorContainer replaces it via onReady instead.
    setOpenName(name);
    setOpenXml(xml);
    setCatalog(buildNodeCatalog(xml));
    setDecisions(/\.dmn$/i.test(name) ? buildDecisionCatalog(xml) : null);
    setDirty(false);
    setPreviewActive(false);
    setPreviewNote(null);
    setJustSaved(false);
  }

  const onEditorReady = useCallback((handle: EditorHandle) => {
    editorRef.current = handle;
  }, []);

  /**
   * Current file contents. The editor is asked first so unsaved canvas edits are
   * included, but it returns an empty string until its envelope has finished
   * starting up, so the loaded file is used until there is really something there.
   */
  const getXml = useCallback(async () => {
    if (!editorRef.current) return openXml;
    try {
      const fromEditor = await editorRef.current.getContent();
      return fromEditor && fromEditor.trim().length > 0 ? fromEditor : openXml;
    } catch {
      return openXml;
    }
  }, [openXml]);

  /** Shows the proposed change on the canvas. Cancelling puts it back. */
  async function preview(xml: string, note?: string): Promise<boolean> {
    if (!editorRef.current) {
      console.error("preview: no editor handle registered");
      return false;
    }
    try {
      await editorRef.current.setContent(xml);
      setPreviewActive(true);
      setPreviewNote(note ?? null);
      setJustSaved(false);
      setDirty(true);
      return true;
    } catch (e) {
      console.error("preview: setContent failed", e);
      return false;
    }
  }

  async function cancelPreview() {
    if (previewActive && editorRef.current) {
      await editorRef.current.undo();
      setPreviewActive(false);
      setPreviewNote(null);
    }
  }

  async function apply(xml: string): Promise<{ ok: boolean; message: string }> {
    if (!openName) return { ok: false, message: "No process is open." };
    if (!previewActive && editorRef.current) {
      await editorRef.current.setContent(xml);
    }
    const result = await saveAsset(openName, xml);
    setPreviewActive(false);
    setPreviewNote(null);
    if (result.ok) {
      setCatalog(buildNodeCatalog(xml));
      if (decisions) setDecisions(buildDecisionCatalog(xml));
      setDirty(false);
      // The canvas does not change at the moment of saving — it already shows this file.
      // Without a confirmation the most important state change in the app is invisible.
      setJustSaved(true);
      return { ok: true, message: "Done. Saved, and you can undo it from the toolbar above." };
    }
    const failed = result.gates?.find((g) => !g.ok);
    return { ok: false, message: failed ? failed.detail : result.message };
  }

  async function undoLast() {
    await editorRef.current?.undo();
    setPreviewActive(false);
    setPreviewNote(null);
  }

  // The saved confirmation is a moment, not a state — it clears itself so the canvas does
  // not keep claiming something that stopped being news.
  useEffect(() => {
    if (!justSaved) return;
    const timer = setTimeout(() => setJustSaved(false), 4000);
    return () => clearTimeout(timer);
  }, [justSaved]);

  return (
    <div className="shell">
      <header className="topbar">
        <div className="wordmark">PROCESS DESK</div>
        <div className="topbar-file">
          <select
            aria-label="Open process"
            value={openName ?? ""}
            onChange={(e) => e.target.value && open(e.target.value)}
          >
            {assets.map((a) => (
              <option key={a.name} value={a.name}>
                {a.name}
              </option>
            ))}
          </select>
          <span className={`status ${dirty ? "unsaved" : ""}`}>
            {dirty ? "unsaved changes" : "saved"}
          </span>
        </div>
        <div className="topbar-right">
          <button className="btn btn-secondary" onClick={undoLast} disabled={!openName}>
            Undo
          </button>
          <ModelBadge status={status} />
        </div>
      </header>

      <main className={`canvas${previewActive ? " previewing" : ""}`}>
        {previewActive && (
          <div className="canvas-flag proposed" role="status">
            <span className="canvas-flag-label">Proposed</span>
            <span className="canvas-flag-text">
              {previewNote ?? "This is what the file would look like."}
            </span>
            <span className="canvas-flag-tail">Not saved yet</span>
          </div>
        )}
        {justSaved && (
          <div className="canvas-flag saved" role="status">
            <span className="canvas-flag-label">Saved</span>
            <span className="canvas-flag-text">This is the file now.</span>
          </div>
        )}
        {banner && <div className="canvas-empty"><p>{banner}</p></div>}
        {!banner && <EditorContainer fileName={openName} xml={openXml} onReady={onEditorReady} />}
      </main>

      <AssistantPanel
        fileName={openName}
        getXml={getXml}
        catalog={catalog}
        decisions={decisions}
        previewActive={previewActive}
        onPreview={preview}
        onApply={apply}
        onCancelPreview={cancelPreview}
      />

      <footer className="footer">
        <span><span className="statusdot" />Kogito engine, running locally</span>
        <span>Every change is checked before it is saved</span>
        <span className="spacer" />
        <span>{openName ?? "no file open"}</span>
      </footer>
    </div>
  );
}

/**
 * What is answering, top right.
 *
 * <p>The model's own name rather than a category, because "ollama" is not an answer to "what
 * read my file" and the name is what a person types into `OLLAMA_MODEL` to change it. The
 * second line is prefill: on a machine without a GPU that is where a tool loop spends its
 * time, so it is the one number worth putting on screen without being asked for.
 */
function ModelBadge({ status }: { status: Health | null }) {
  if (!status) {
    return <span className="model-badge">connecting…</span>;
  }
  if (!status.ok) {
    return <span className="model-badge offline">no backend</span>;
  }
  // The dummy provider names itself "placeholder". Saying so is more use than printing it:
  // nothing is reading the file, and that is the thing a user needs to know.
  if (status.provider === "placeholder") {
    return (
      <span className="model-badge offline" title="Set AI_PROVIDER=ollama in .env to connect a model.">
        no model
      </span>
    );
  }

  const usage = status.usage ?? {};
  const prefill = usage.prefillTokensPerSecond ?? 0;
  const generation = usage.generationTokensPerSecond ?? 0;
  const spent =
    prefill > 0
      ? `${Math.round(prefill)} tok/s in · ${Math.round(generation)} out`
      : "idle";
  const detail =
    prefill > 0
      ? `${usage.promptTokens ?? 0} tokens read in ${(usage.promptSeconds ?? 0).toFixed(1)}s, ` +
        `${usage.replyTokens ?? 0} written in ${(usage.replySeconds ?? 0).toFixed(1)}s`
      : "Nothing measured yet. Ask it something.";

  return (
    <span className="model-badge" title={detail}>
      <span className="model-name">{status.provider}</span>
      <span className="model-spent">{spent}</span>
    </span>
  );
}
