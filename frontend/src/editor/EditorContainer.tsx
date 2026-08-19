import { useCallback, useEffect, useRef, useState } from "react";
import { kindOf, openEditor, openFallback, type EditorHandle } from "./editorApi";

interface Props {
  fileName: string | null;
  xml: string | null;
  onReady: (handle: EditorHandle) => void;
}

/**
 * Boots the editor matching the open file. If the editor bundle fails to load, or the
 * file type has no editor, drops to a plain-text view so the assistant, the validation
 * harness, and the Kogito run still work end to end.
 *
 * <p>The editor is opened from a callback ref rather than an effect: the container div
 * is created by the same render that opens the file, and a ref callback fires with the
 * node itself, so there is no window where the effect runs before the node exists.
 */
export function EditorContainer({ fileName, xml, onReady }: Props) {
  const handleRef = useRef<EditorHandle | null>(null);
  const [failure, setFailure] = useState<string | null>(null);

  const kind = fileName ? kindOf(fileName) : null;
  const unsupported =
    fileName && !kind
      ? "This file type doesn't have a visual editor."
      : kind === "scesim"
        ? "Test scenario files open as text — KIE doesn't publish a standalone editor for them yet."
        : null;

  useEffect(() => {
    setFailure(null);
  }, [fileName]);

  // Closing the previous editor is the container's job; the ref callback runs for the
  // new node before React detaches the old one.
  useEffect(() => {
    return () => {
      handleRef.current?.close();
      handleRef.current = null;
    };
  }, []);

  const mountEditor = useCallback(
    (node: HTMLDivElement | null) => {
      if (!node || !kind || kind === "scesim" || xml === null) return;

      handleRef.current?.close();
      handleRef.current = null;

      openEditor(kind, node, xml)
        .then((handle) => {
          handleRef.current = handle;
          onReady(handle);
        })
        .catch((e) => {
          console.error("Editor failed to boot:", e);
          setFailure("The visual editor couldn't start, so this is the raw file.");
        });
    },
    // A new node is created per file, so the callback must change with the file too.
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [fileName, xml === null],
  );

  const mountFallback = useCallback(
    (node: HTMLTextAreaElement | null) => {
      if (!node || xml === null) return;
      const handle = openFallback(node, xml);
      handleRef.current = handle;
      onReady(handle);
    },
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [fileName, xml],
  );

  if (xml === null) {
    return (
      <div className="canvas-empty">
        <span className="eyebrow">Process Desk</span>
        <p>Open a file to get started.</p>
      </div>
    );
  }

  const note = unsupported ?? failure;
  if (note) {
    return (
      <div className="xml-fallback">
        <div className="xml-fallback-note">{note}</div>
        <textarea ref={mountFallback} spellCheck={false} aria-label="File contents" />
      </div>
    );
  }

  return <div className="canvas-host" ref={mountEditor} key={fileName ?? "none"} />;
}
