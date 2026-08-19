/**
 * Client for the backend. The AI endpoints are backed by a placeholder provider
 * today; this module is the seam that will not change when the local model lands.
 */

export interface GateResult {
  // Not a union of the BPMN gate ids. Decision models run different checks — shape and
  // coverage rather than connections — and hard-coding the process ones is what made the
  // panel show "not reached" against a row that was never going to run for this file.
  id: string;
  label: string;
  ok: boolean;
  detail: string;
}

export interface ProposeResponse {
  ok: boolean;
  explanation: string | null;
  proposedXml: string | null;
  gates: GateResult[];
  message: string | null;
}

export interface ChatResponse {
  answer: string;
  provider: string;
}

export interface SaveResponse {
  ok: boolean;
  gates: GateResult[];
  message: string;
}

async function post<T>(path: string, body: unknown): Promise<T> {
  const res = await fetch(path, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  if (!res.ok) throw new Error(`${path} failed: ${res.status}`);
  return res.json();
}

/**
 * One call for anything the user types. The backend decides whether it is a question or
 * a change — that judgement needs the model, so the browser must not second-guess it.
 */
export interface AskResponse {
  answer: string | null;
  proposal: ProposeResponse | null;
  provider: string;
}

export function ask(message: string, xml: string): Promise<AskResponse> {
  return post<AskResponse>("/api/ai/ask", { message, xml });
}

/** One thing the assistant did, reported as it started rather than predicted in advance. */
export interface Step {
  label: string;
  detail: string | null;
}

/**
 * Ask, and report each step as it happens.
 *
 * The assistant looks at the file, changes it, checks its own work and sometimes fixes what it
 * broke — which can take half a minute, and used to be half a minute of one unchanging spinner.
 * The server streams newline-delimited JSON: any number of `step` objects, then one `result`
 * identical to what `ask` returns.
 *
 * Falls back to `ask` if the stream is unavailable, so the panel keeps working against a server
 * that predates this endpoint — losing the progress and nothing else.
 */
export async function askStreaming(
  message: string,
  xml: string,
  onStep: (step: Step) => void,
): Promise<AskResponse> {
  let res: Response;
  try {
    res = await fetch("/api/ai/ask/stream", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ message, xml }),
    });
  } catch {
    return ask(message, xml);
  }
  if (!res.ok || !res.body) return ask(message, xml);

  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";
  let result: AskResponse | null = null;

  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });

    // Only whole lines: a chunk boundary can land mid-object, and the remainder stays
    // buffered until the rest of it arrives.
    let newline: number;
    while ((newline = buffer.indexOf("\n")) >= 0) {
      const line = buffer.slice(0, newline).trim();
      buffer = buffer.slice(newline + 1);
      if (!line) continue;
      try {
        const event = JSON.parse(line);
        if (event.type === "step") onStep(event.payload as Step);
        else if (event.type === "result") result = event.payload as AskResponse;
      } catch {
        // A malformed line loses one step, which is not worth failing the request over.
      }
    }
  }

  if (!result) throw new Error("The assistant stopped before it finished.");
  return result;
}

export async function health(): Promise<{ ok: boolean; provider: string }> {
  const res = await fetch("/api/health");
  if (!res.ok) throw new Error("backend unreachable");
  return res.json();
}

export async function listAssets(): Promise<{ name: string }[]> {
  const res = await fetch("/api/assets");
  if (!res.ok) throw new Error("Could not list processes");
  return (await res.json()).assets;
}

export async function loadAsset(name: string): Promise<string> {
  const res = await fetch(`/api/assets/${encodeURIComponent(name)}`);
  if (!res.ok) throw new Error("Could not open that process");
  return (await res.json()).xml;
}

export async function saveAsset(name: string, xml: string): Promise<SaveResponse> {
  const res = await fetch(`/api/assets/${encodeURIComponent(name)}`, {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ xml }),
  });
  return res.json();
}
