package com.processdesk.harness;

import org.springframework.stereotype.Component;
import org.w3c.dom.Element;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Applies changes to a decision table.
 *
 * <p>One cell edit, addressed by coordinate: which decision, which rule, which column. That
 * replaced four methods which each addressed a rule a different way — by the condition it
 * held, by the outcome it produced, by a condition while changing the outcome, and the
 * reverse. Those were never four operations; they were a 2×2 of how you find the rule and
 * which cell you write, and modelling them as separate names meant every new phrasing looked
 * like a missing feature. Three were added reactively in one day, and the fourth combination
 * was still a refusal until someone hit it.
 *
 * <p>The user still speaks in values — "make the High band 20 or less". Turning that into a
 * coordinate is the model's job: it is shown the rules numbered, and counting a short list is
 * something it does reliably. Turning a coordinate into XML is this class's job.
 *
 * <p>{@code expect} is what makes that safe. The caller states what it believes is in the
 * cell, and the edit is refused if the cell holds something else — so a miscounted rule
 * changes nothing instead of changing the wrong thing. That check is exact, and it replaced
 * a heuristic that asked whether the user's sentence mentioned the value, which needed a
 * special case for "under 18" against {@code <18} and was wrong at the edges either way.
 */
@Component
public class DecisionEditor {

    public record EditResult(String xml, String summary, int cellsChanged) {}

    /** The name for a rule's result column, as the model is told to refer to it. */
    public static final String OUTCOME = "outcome";

    /**
     * Writes one cell.
     *
     * @param decisionName which decision, as the projection names it
     * @param ruleNumber   which rule, 1-based, in the order the projection lists them
     * @param cell         a column heading, or {@link #OUTCOME} for the result
     * @param expect       what the caller believes the cell holds; the edit is refused if not
     * @param to           the new value, taking the cell's existing quoting
     */
    public EditResult setCell(String xml, String decisionName, int ruleNumber, String cell,
                              String expect, String to) throws Exception {
        BpmnDocument doc = BpmnDocument.parse(xml);

        Element decision = doc.elements("decision").stream()
                .filter(d -> BpmnDocument.displayName(d).equalsIgnoreCase(decisionName.trim()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "There is no decision called " + decisionName + "."));

        Element table = childrenNamed(decision, "decisionTable").stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "\"" + BpmnDocument.displayName(decision) + "\" isn't a table of rules, "
                                + "so I can't change a rule in it."));

        List<Element> rules = childrenNamed(table, "rule");
        if (ruleNumber < 1 || ruleNumber > rules.size()) {
            throw new IllegalArgumentException("\"" + BpmnDocument.displayName(decision)
                    + "\" has " + rules.size() + (rules.size() == 1 ? " rule" : " rules")
                    + ", so there is no rule " + ruleNumber + ".");
        }
        Element rule = rules.get(ruleNumber - 1);

        Element target = cellOf(table, rule, cell);
        String actual = target.getTextContent().trim();
        // A rule reads "DTI >0.36" when it is shown, and the cell holds only ">0.36". A caller
        // copying what it was shown therefore arrives carrying the column name, and refusing
        // that is pedantry about our own formatting: the coordinate and the value are both
        // right. Measured on gpt-oss:20b, which sent expect="DTI >0.36" and then repeated the
        // identical rejected call three more times.
        expect = withoutColumnPrefix(expect, cell);
        to = withoutColumnPrefix(to, cell);
        if (!matches(actual, expect)) {
            // The coordinate and the value disagree, so one of them is wrong and neither is
            // safe to act on. Naming both is what lets the user see which.
            throw new IllegalArgumentException("Rule " + ruleNumber + " of \""
                    + BpmnDocument.displayName(decision) + "\" has " + describe(cell) + " set to "
                    + actual + ", not " + expect + ". Nothing was changed.");
        }

        target.setTextContent(quoteLike(actual, to));
        String summary = "Changed " + describe(cell) + " in rule " + ruleNumber + " of \""
                + BpmnDocument.displayName(decision) + "\" from " + actual + " to " + to.trim() + ".";
        return new EditResult(doc.serialize(), summary, 1);
    }

    /** The {@code <text>} element of the addressed cell. */
    private static Element cellOf(Element table, Element rule, String cell) {
        if (cell == null || cell.isBlank() || OUTCOME.equalsIgnoreCase(cell.trim())) {
            Element entry = childrenNamed(rule, "outputEntry").stream().findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("That rule has no result to change."));
            return required(textCell(entry));
        }
        int index = indexOfColumn(table, cell);
        if (index < 0) {
            throw new IllegalArgumentException("This decision has no column called " + cell
                    + ". It looks at: " + String.join(", ", columnsOf(table)) + ".");
        }
        List<Element> conditions = childrenNamed(rule, "inputEntry");
        if (index >= conditions.size()) {
            throw new IllegalArgumentException("That rule has no value for " + cell + ".");
        }
        return required(textCell(conditions.get(index)));
    }

    private static Element required(Element text) {
        if (text == null) {
            throw new IllegalArgumentException("That cell is empty, so there is nothing to change.");
        }
        return text;
    }

    private static String describe(String cell) {
        return cell == null || cell.isBlank() || OUTCOME.equalsIgnoreCase(cell.trim())
                ? "the result" : cell.trim();
    }


    /**
     * Adds a rule to the end of a decision table.
     *
     * <p>The one thing a person asks for that this tool could not do. "Create a new criteria on
     * a decision table" was not a phrasing problem or a model problem — there was no operation,
     * so every wording of it failed, and under the tool loop the model spent turns trying other
     * tools until the request timed out.
     *
     * <p>Appended rather than inserted, which is the safe end. Under UNIQUE — the common hit
     * policy, and the one every table in the lending model uses — order carries no meaning at
     * all. Under FIRST it means priority, and last place is the position that cannot change
     * what any existing rule already decides.
     *
     * <p>Nothing here judges whether the new rule makes sense. A rule that overlaps another, or
     * that fills a gap badly, is a coverage question, and the coverage gate answers it against
     * the whole table after the fact. This method's contract is narrower: the rule is shaped
     * correctly, or it is refused.
     *
     * @param conditions one per column, in column order; "-" for a column that does not matter
     * @param outcomes   one per result column, usually a single value
     */
    public EditResult addRule(String xml, String decisionName, List<String> conditions,
                              List<String> outcomes) throws Exception {
        BpmnDocument doc = BpmnDocument.parse(xml);

        Element decision = doc.elements("decision").stream()
                .filter(d -> BpmnDocument.displayName(d).equalsIgnoreCase(decisionName.trim()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "There is no decision called " + decisionName + "."));

        Element table = childrenNamed(decision, "decisionTable").stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "\"" + BpmnDocument.displayName(decision) + "\" isn't a table of rules, "
                                + "so I can't add a rule to it."));

        List<String> columns = columnsOf(table);
        int outputs = Math.max(1, childrenNamed(table, "output").size());

        // Refused rather than padded. A rule with the wrong number of cells is the classic way
        // to corrupt a table — conditions silently shift into the wrong columns — and guessing
        // at the missing one would be inventing business logic nobody asked for.
        if (conditions.size() != columns.size()) {
            throw new IllegalArgumentException("\"" + BpmnDocument.displayName(decision)
                    + "\" looks at " + columns.size() + (columns.size() == 1 ? " thing: " : " things: ")
                    + String.join(", ", columns) + ". A new rule needs a value for each of them"
                    + (conditions.size() == 1 ? ", and I was given 1." : ", and I was given "
                            + conditions.size() + "."));
        }
        if (outcomes.size() != outputs) {
            throw new IllegalArgumentException("A rule in \"" + BpmnDocument.displayName(decision)
                    + "\" produces " + outputs + (outputs == 1 ? " result" : " results")
                    + ", and I was given " + outcomes.size() + ".");
        }

        List<Element> existing = childrenNamed(table, "rule");
        Element rule = create(doc, table, "rule");
        rule.setAttribute("id", "_" + java.util.UUID.randomUUID());

        for (int i = 0; i < conditions.size(); i++) {
            rule.appendChild(entry(doc, table, "inputEntry",
                    quoteLikeColumn(existing, "inputEntry", i, conditions.get(i))));
        }
        for (int i = 0; i < outcomes.size(); i++) {
            rule.appendChild(entry(doc, table, "outputEntry",
                    quoteLikeColumn(existing, "outputEntry", i, outcomes.get(i))));
        }
        table.appendChild(rule);

        String summary = "Added a rule to \"" + BpmnDocument.displayName(decision) + "\": "
                + describeRule(columns, conditions, outcomes) + ".";
        return new EditResult(doc.serialize(), summary, 1);
    }

    /** "DTI [0.36..0.5] gives \"Poor\"", for the sentence the user approves. */
    private static String describeRule(List<String> columns, List<String> conditions,
                                       List<String> outcomes) {
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < conditions.size(); i++) {
            String value = conditions.get(i).trim();
            String column = i < columns.size() ? columns.get(i) : "input";
            parts.add("-".equals(value) || value.isEmpty() ? "any " + column : column + " " + value);
        }
        return String.join(", ", parts) + " gives " + String.join(", ", outcomes);
    }

    /**
     * A new cell's value, quoted the way that column already quotes its values.
     *
     * <p>FEEL cares: {@code "Poor"} is a string and {@code Poor} is a variable that does not
     * exist, and the difference is a model that will not compile. There is no existing cell in
     * this rule to copy from, so the column is asked instead — which is the same question
     * {@link #quoteLike} answers for an edit, aimed one row up.
     */
    private static String quoteLikeColumn(List<Element> rules, String entryName, int index,
                                          String value) {
        for (Element rule : rules) {
            List<Element> entries = childrenNamed(rule, entryName);
            if (index >= entries.size()) {
                continue;
            }
            String existing = entries.get(index).getTextContent().trim();
            if (existing.isEmpty() || "-".equals(existing)) {
                continue;
            }
            return quoteLike(existing, value);
        }
        return value.trim();
    }

    /** An {@code inputEntry} or {@code outputEntry} wrapping a {@code text} node. */
    private static Element entry(BpmnDocument doc, Element table, String name, String value) {
        Element entry = create(doc, table, name);
        entry.setAttribute("id", "_" + java.util.UUID.randomUUID());
        Element text = create(doc, table, "text");
        text.setTextContent(value);
        entry.appendChild(text);
        return entry;
    }

    /** Creates an element in the table's own namespace and prefix, so it serialises to match. */
    private static Element create(BpmnDocument doc, Element sibling, String localName) {
        String prefix = sibling.getPrefix();
        return doc.dom().createElementNS(sibling.getNamespaceURI(),
                prefix == null || prefix.isBlank() ? localName : prefix + ":" + localName);
    }

    /**
     * Renames a decision, and everything that refers to it by that name.
     *
     * <p>Identifiers are untouched — those are the machine's. But a decision's *name* is a
     * FEEL variable, and other decisions read it by name: in a real model "Income Risk
     * Category" appears as an input expression inside "Credit Risk Category". Renaming only
     * the decision leaves those references pointing at something that no longer exists, and
     * Kogito refuses to compile the result.
     *
     * <p>Caught by running this against a model we did not write. Our own fixture has one
     * decision and nothing to refer to it, so a rename that broke every real multi-decision
     * model passed its tests.
     */
    public EditResult renameDecision(String xml, String from, String to) throws Exception {
        BpmnDocument doc = BpmnDocument.parse(xml);
        Optional<Element> decision = doc.elements("decision").stream()
                .filter(d -> BpmnDocument.displayName(d).equalsIgnoreCase(from.trim()))
                .findFirst();
        if (decision.isEmpty()) {
            throw new IllegalArgumentException("There is no decision called " + from + ".");
        }
        String actual = BpmnDocument.displayName(decision.get());
        setNameEverywhere(decision.get(), actual, to);

        // A decision's variable carries the same name; leaving it behind breaks references.
        BpmnDocument.childElements(decision.get()).stream()
                .filter(child -> "variable".equals(child.getLocalName()))
                .forEach(variable -> setNameEverywhere(variable, actual, to));

        int references = renameReferences(doc, actual, to);

        String summary = "Renamed \"" + actual + "\" to \"" + to + "\""
                + (references == 0 ? "." : ", and updated " + references
                        + (references == 1 ? " reference to it." : " references to it."));
        return new EditResult(doc.serialize(), summary, 1 + references);
    }

    /**
     * Rewrites every place the old name is used as a name rather than as prose: labels, and
     * expression text that is exactly the decision's name.
     *
     * <p>Only whole-value matches are rewritten. A substring replace inside a longer FEEL
     * expression would be a silent edit to logic nobody asked to change, and the gates would
     * not necessarily catch it — the result can compile and mean something else.
     */
    private static int renameReferences(BpmnDocument doc, String from, String to) {
        int changed = 0;
        for (String tag : List.of("inputExpression", "literalExpression", "outputExpression")) {
            for (Element expression : doc.elements(tag)) {
                Element text = textCell(expression);
                if (text != null && text.getTextContent().trim().equals(from)) {
                    text.setTextContent(to);
                    changed++;
                }
            }
        }
        for (String tag : List.of("input", "output", "decisionTable")) {
            for (Element element : doc.elements(tag)) {
                if (from.equals(element.getAttribute("label"))) {
                    element.setAttribute("label", to);
                    changed++;
                }
                if (from.equals(element.getAttribute("outputLabel"))) {
                    element.setAttribute("outputLabel", to);
                    changed++;
                }
            }
        }
        return changed;
    }

    /**
     * Sets {@code name}, and any vendor display name alongside it.
     *
     * <p>Exporters write their own copy — Trisotech uses {@code triso:displayName} — and the
     * projection reads whichever it finds. Setting only {@code name} renames the decision
     * everywhere except the place the user is looking.
     */
    private static void setNameEverywhere(Element element, String from, String to) {
        element.setAttribute("name", to);
        org.w3c.dom.NamedNodeMap attributes = element.getAttributes();
        for (int i = 0; i < attributes.getLength(); i++) {
            org.w3c.dom.Node attribute = attributes.item(i);
            if (attribute.getLocalName() != null
                    && attribute.getLocalName().equalsIgnoreCase("displayName")
                    && attribute.getNodeValue().trim().equals(from)) {
                attribute.setNodeValue(to);
            }
        }
    }

    /** Column headings, in order, so a caller can name what a rule is testing. */
    public static List<String> columnsOf(Element table) {
        List<String> columns = new ArrayList<>();
        for (Element input : childrenNamed(table, "input")) {
            columns.add(BpmnDocument.childElements(input).stream()
                    .filter(e -> "inputExpression".equals(e.getLocalName()))
                    .flatMap(e -> BpmnDocument.childElements(e).stream())
                    .filter(e -> "text".equals(e.getLocalName()))
                    .map(e -> e.getTextContent().trim())
                    .findFirst()
                    .orElse("input"));
        }
        return columns;
    }

    private static int indexOfColumn(Element table, String column) {
        List<String> columns = columnsOf(table);
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).equalsIgnoreCase(column.trim())) {
                return i;
            }
        }
        return -1;
    }

    private static List<Element> childrenNamed(Element parent, String localName) {
        return BpmnDocument.childElements(parent).stream()
                .filter(child -> localName.equals(child.getLocalName()))
                .toList();
    }

    private static Element textCell(Element entry) {
        return BpmnDocument.childElements(entry).stream()
                .filter(child -> "text".equals(child.getLocalName()))
                .findFirst()
                .orElse(null);
    }

    /**
     * Cell values carry their own quoting: a string outcome is written {@code "Automatic"}
     * and a number is written bare. A replacement keeps whatever form the cell already used,
     * so changing a value cannot quietly change its type.
     */
    private static String quoteLike(String existing, String replacement) {
        String trimmedExisting = existing.trim();
        String trimmedReplacement = replacement.trim();
        boolean wasQuoted = trimmedExisting.startsWith("\"") && trimmedExisting.endsWith("\"");
        boolean isQuoted = trimmedReplacement.startsWith("\"") && trimmedReplacement.endsWith("\"");
        if (wasQuoted && !isQuoted) {
            return "\"" + trimmedReplacement + "\"";
        }
        return trimmedReplacement;
    }

    /**
     * Drops a leading column name, so "DTI &gt;0.36" and "&gt;0.36" mean the same cell.
     *
     * <p>Only an exact leading match followed by whitespace, and only for the column being
     * written. A value that merely starts with similar text is left alone — the risk being
     * guarded against is silently rewriting a condition, which is worse than a refusal.
     */
    private static String withoutColumnPrefix(String value, String column) {
        if (value == null || column == null || column.isBlank()
                || OUTCOME.equalsIgnoreCase(column.trim())) {
            return value;
        }
        String trimmed = value.trim();
        String prefix = column.trim();
        if (trimmed.length() > prefix.length()
                && trimmed.regionMatches(true, 0, prefix, 0, prefix.length())
                && Character.isWhitespace(trimmed.charAt(prefix.length()))) {
            return trimmed.substring(prefix.length()).trim();
        }
        return trimmed;
    }

    /** Compares cell text ignoring the quoting and spacing a person would not type. */
    private static boolean matches(String cell, String wanted) {
        return unquote(cell).equalsIgnoreCase(unquote(wanted));
    }

    private static String unquote(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return trimmed.substring(1, trimmed.length() - 1).trim();
        }
        return trimmed;
    }
}
