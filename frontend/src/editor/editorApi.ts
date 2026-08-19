/**
 * The single seam between this app and the KIE editors.
 * Everything else imports this wrapper, never an editor package directly.
 *
 * Each asset type has its own standalone editor package. They expose the same
 * API shape, so the rest of the app does not care which one is open.
 */

export type AssetKind = "bpmn" | "dmn" | "scesim";

export interface EditorHandle {
  kind: AssetKind | "fallback";
  filePath: string;
  getContent(): Promise<string>;
  setContent(xml: string): Promise<void>;
  undo(): Promise<void>;
  redo(): Promise<void>;
  subscribeToContentChanges?(callback: () => void): void;
  close(): void;
}

const FILE_PATHS: Record<AssetKind, string> = {
  bpmn: "model.bpmn",
  dmn: "model.dmn",
  scesim: "model.scesim",
};

export function kindOf(fileName: string): AssetKind | null {
  if (/\.bpmn2?$/i.test(fileName)) return "bpmn";
  if (/\.dmn$/i.test(fileName)) return "dmn";
  if (/\.scesim$/i.test(fileName)) return "scesim";
  return null;
}

/**
 * Opens the editor that matches the file type.
 *
 * <p>SceSim has no standalone build published, so test-scenario files fall back to
 * the plain-text view rather than silently opening in the wrong editor.
 */
export async function openEditor(
  kind: AssetKind,
  container: Element,
  initialXml: string,
): Promise<EditorHandle> {
  if (kind === "scesim") {
    throw new Error("No standalone editor is published for test scenario files.");
  }

  const filePath = FILE_PATHS[kind];
  const mod =
    kind === "bpmn"
      ? await import("@kie-tools/bpmn-editor-standalone/dist/index.js")
      : await import("@kie-tools/dmn-editor-standalone/dist/index.js");

  const editor = mod.open({
    container,
    initialContent: Promise.resolve(initialXml),
    initialFileNormalizedPosixPathRelativeToTheWorkspaceRoot: filePath,
    readOnly: false,
    origin: window.location.origin,
  });

  return {
    kind,
    filePath,
    getContent: () => editor.getContent(),
    setContent: (xml: string) => editor.setContent(filePath, xml),
    undo: () => editor.undo(),
    redo: () => editor.redo(),
    subscribeToContentChanges: (callback: () => void) => editor.subscribeToContentChanges(callback),
    close: () => editor.close(),
  };
}

/** Plain-text view used for SceSim, and if an editor fails to boot. */
export function openFallback(textarea: HTMLTextAreaElement, initialXml: string): EditorHandle {
  textarea.value = initialXml;
  let history: string[] = [];
  return {
    kind: "fallback",
    filePath: "model",
    getContent: async () => textarea.value,
    setContent: async (xml: string) => {
      history.push(textarea.value);
      textarea.value = xml;
    },
    undo: async () => {
      const previous = history.pop();
      if (previous !== undefined) textarea.value = previous;
    },
    redo: async () => {},
    close: () => {
      history = [];
    },
  };
}
