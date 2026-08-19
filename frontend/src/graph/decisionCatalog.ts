export interface DecisionCatalog {
  decisions: string[];
  columns: string[];
  conditions: string[];
  outcomes: string[];
}

const EMPTY: DecisionCatalog = { decisions: [], columns: [], conditions: [], outcomes: [] };

/**
 * The parts of a decision table a request can name, read from the open file.
 *
 * Mirrors what the backend enumerates for the model, so a template offers exactly the
 * values the harness will accept.
 */
export function buildDecisionCatalog(xml: string): DecisionCatalog {
  try {
    const doc = new DOMParser().parseFromString(xml, "text/xml");
    if (doc.querySelector("parsererror")) return EMPTY;

    const all = Array.from(doc.getElementsByTagName("*"));
    const textOf = (el: Element) =>
      Array.from(el.getElementsByTagName("*"))
        .find((child) => child.localName === "text")
        ?.textContent?.trim() ?? "";

    const decisions = all
      .filter((el) => el.localName === "decision")
      .map((el) => el.getAttribute("name") ?? "")
      .filter(Boolean);

    const columns = all
      .filter((el) => el.localName === "input" && el.parentElement?.localName === "decisionTable")
      .map(textOf)
      .filter(Boolean);

    const cells = (name: string) =>
      Array.from(new Set(all.filter((el) => el.localName === name).map(textOf)))
        // "-" is DMN for "this column does not matter", not a value anyone can name.
        .filter((value) => value !== "" && value !== "-");

    return { decisions, columns, conditions: cells("inputEntry"), outcomes: cells("outputEntry") };
  } catch {
    return EMPTY;
  }
}
