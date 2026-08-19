export interface CatalogEntry {
  id: string;
  name: string;
  type: string;
}

const STEP_TYPES = new Set([
  "task",
  "userTask",
  "serviceTask",
  "scriptTask",
  "businessRuleTask",
  "callActivity",
]);

/** id ↔ human-label map parsed straight from the XML; feeds the step picker and prompts. */
export function buildNodeCatalog(xml: string): CatalogEntry[] {
  try {
    const doc = new DOMParser().parseFromString(xml, "text/xml");
    if (doc.querySelector("parsererror")) return [];
    const entries: CatalogEntry[] = [];
    for (const el of Array.from(doc.getElementsByTagName("*"))) {
      if (STEP_TYPES.has(el.localName) && el.getAttribute("id")) {
        entries.push({
          id: el.getAttribute("id")!,
          name: el.getAttribute("name") ?? el.getAttribute("id")!,
          type: el.localName,
        });
      }
    }
    return entries;
  } catch {
    return [];
  }
}
