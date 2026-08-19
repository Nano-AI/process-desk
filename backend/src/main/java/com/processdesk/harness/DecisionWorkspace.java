package com.processdesk.harness;

import org.w3c.dom.Element;

import java.util.ArrayList;
import java.util.List;

/**
 * A decision model the assistant is working on, and the operations it may perform on it.
 *
 * <p>These are the tool implementations. Each returns text written to be read by the model —
 * short, because every character is prefilled on the next turn, and specific, because the whole
 * point of a loop is that the model can correct itself from what it is told.
 *
 * <p>Edits accumulate on a working copy. That is what makes two-part requests expressible
 * ("Affordable under 0.15 and Marginal from 0.15 to 0.36" is two {@link #setCell} calls), and
 * it is what lets {@link #check()} report the gap a half-finished pair of boundary edits leaves
 * behind while the model is still in a position to fix it. Under the one-shot protocol that gap
 * could only ever be discovered after the model had stopped talking.
 *
 * <p>Nothing here trusts the model. {@code setCell} still carries {@code expect} and is still
 * refused when the cell disagrees, and the gates are still the arbiter of what may be offered.
 * A loop widens what can be attempted; it does not widen what can be written.
 */
public class DecisionWorkspace {

    private final String original;
    private final DecisionEditor editor;
    private final DecisionGates gates;
    private final List<String> edits = new ArrayList<>();

    private String working;

    public DecisionWorkspace(String xml, DecisionEditor editor, DecisionGates gates) {
        this.original = xml;
        this.working = xml;
        this.editor = editor;
        this.gates = gates;
    }

    public String workingXml() {
        return working;
    }

    public boolean changed() {
        return !edits.isEmpty();
    }

    /** What was done, in order, for the summary the user reads. */
    public List<String> edits() {
        return List.copyOf(edits);
    }

    /**
     * Every decision in the file, and whether it is a table of rules or a formula.
     *
     * <p>The distinction is the whole reason this tool exists. Five of the eleven decisions in
     * the lending model are literal expressions, and the projection renders them as a name with
     * nothing under it — indistinguishable from a table with no rules. Asked to change "the DTI
     * for affordable", the model picked the DTI *decision*, which is a formula, over the DTI
     * *column* of "Affordability Category", and correctly reported that it had no rules to
     * change. It was not wrong; it could not see that there was anything else to pick.
     */
    public String listDecisions() {
        try {
            BpmnDocument doc = BpmnDocument.parse(working);
            List<Element> decisions = doc.elements("decision");
            if (decisions.isEmpty()) {
                return "This file has no decisions.";
            }

            StringBuilder out = new StringBuilder();
            for (Element decision : decisions) {
                String name = BpmnDocument.displayName(decision);
                Element table = firstChild(decision, "decisionTable");
                out.append("\n\"").append(name).append("\" — ");
                if (table == null) {
                    out.append("a formula, not a table of rules. It cannot be edited rule by rule.");
                    continue;
                }
                int rules = (int) BpmnDocument.childElements(table).stream()
                        .filter(child -> "rule".equals(child.getLocalName())).count();
                List<String> columns = DecisionEditor.columnsOf(table);
                out.append(rules).append(rules == 1 ? " rule" : " rules")
                        .append(", looks at: ").append(String.join(", ", columns));
            }
            return out.toString().trim();
        } catch (Exception e) {
            return "I couldn't read this decision model.";
        }
    }

    /**
     * One decision in full: its columns, and its rules numbered as the coordinates an edit uses.
     *
     * <p>Fetching on demand is what replaced guessing up front. The one-shot path narrowed the
     * projection with BM25 because it had to choose, before the model said anything, which
     * decisions were worth the tokens. Here the model asks for the one it wants.
     */
    public String showDecision(String name) {
        if (name == null || name.isBlank()) {
            return "Name a decision. " + listDecisions();
        }
        try {
            BpmnDocument doc = BpmnDocument.parse(working);
            Element decision = doc.elements("decision").stream()
                    .filter(d -> BpmnDocument.displayName(d).equalsIgnoreCase(name.trim()))
                    .findFirst()
                    .orElse(null);
            if (decision == null) {
                return "There is no decision called \"" + name + "\".\n" + listDecisions();
            }

            String actual = BpmnDocument.displayName(decision);
            Element table = firstChild(decision, "decisionTable");
            if (table == null) {
                // Saying what it is instead of showing nothing. A formula cannot be edited by
                // this tool, and the model needs to know that in order to say so, or to go and
                // look for the column of the same name somewhere else.
                String formula = formulaOf(decision);
                return "\"" + actual + "\" is a formula, not a table of rules"
                        + (formula.isBlank() ? "." : ": " + formula)
                        + "\nIt cannot be edited rule by rule. If a rule elsewhere tests this "
                        + "value, it will appear as a column in that decision.";
            }

            List<String> columns = DecisionEditor.columnsOf(table);
            StringBuilder out = new StringBuilder("\"").append(actual).append("\"");
            String hitPolicy = table.getAttribute("hitPolicy");
            if (!hitPolicy.isBlank()) {
                out.append(" (hit policy ").append(hitPolicy).append(")");
            }
            out.append("\nColumns: ").append(String.join(", ", columns));

            int number = 1;
            for (Element rule : BpmnDocument.childElements(table)) {
                if (!"rule".equals(rule.getLocalName())) {
                    continue;
                }
                out.append("\n  ").append(number++).append(". ");
                List<String> parts = new ArrayList<>();
                int column = 0;
                List<String> results = new ArrayList<>();
                for (Element cell : BpmnDocument.childElements(rule)) {
                    String text = textOf(cell);
                    if ("inputEntry".equals(cell.getLocalName())) {
                        String label = column < columns.size() ? columns.get(column) : "input";
                        // "DTI: >0.36", not "DTI >0.36". The cell holds only the second half,
                        // and a reader copying what it was shown has to be able to see where
                        // the heading stops — gpt-oss:20b sent expect="DTI >0.36" against a
                        // cell containing ">0.36" and could not work out why it was refused.
                        parts.add("-".equals(text) || text.isEmpty()
                                ? "any " + label : label + ": " + text);
                        column++;
                    } else if ("outputEntry".equals(cell.getLocalName())) {
                        results.add(text);
                    }
                }
                out.append(String.join(", ", parts)).append(" → ").append(String.join(", ", results));
            }
            return out.toString();
        } catch (Exception e) {
            return "I couldn't read that decision.";
        }
    }

    /**
     * Writes one cell of one rule, on the working copy.
     *
     * <p>A refusal is returned rather than thrown, and returned in the editor's own words —
     * "Rule 2 of X has Member Tier set to Executive, not Platinum" tells the model precisely
     * which half of its coordinate was wrong, which is enough for it to look again and retry.
     */
    public String setCell(String decision, int rule, String column, String expect, String to) {
        try {
            DecisionEditor.EditResult result =
                    editor.setCell(working, decision, rule, column, expect, to);
            working = result.xml();
            edits.add(result.summary());
            return result.summary() + " Call check when the change is complete.";
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        } catch (Exception e) {
            return "That change couldn't be made. Nothing was changed.";
        }
    }

    /**
     * Adds a rule to the end of a table, on the working copy.
     *
     * <p>Conditions arrive as one string per column, in column order, because that is the shape
     * the table has and asking the model to name columns again would be asking it to repeat
     * something {@code show_decision} already told it.
     */
    public String addRule(String decision, List<String> conditions, List<String> outcomes) {
        try {
            DecisionEditor.EditResult result =
                    editor.addRule(working, decision, conditions, outcomes);
            working = result.xml();
            edits.add(result.summary());
            return result.summary() + " Call check to see whether it fits with the other rules.";
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        } catch (Exception e) {
            return "That rule couldn't be added. Nothing was changed.";
        }
    }

    /**
     * Renames a decision, and every reference to it.
     *
     * <p>A decision's name is a FEEL variable that other decisions read, so this is never a
     * cosmetic change — {@link DecisionEditor#renameDecision} rewrites the references too, and
     * gate 1 refuses the result if any are left behind.
     */
    public String renameDecision(String from, String to) {
        try {
            DecisionEditor.EditResult result = editor.renameDecision(working, from, to);
            working = result.xml();
            edits.add(result.summary());
            return result.summary();
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        } catch (Exception e) {
            return "That rename couldn't be made. Nothing was changed.";
        }
    }

    /**
     * Works out a formula, for every value given, and reports each result.
     *
     * <p>Two jobs in one tool, because they are the same operation. With a variable it converts a
     * whole column at once — {@code (c * 9 / 5) + 32} over {@code 20, 8} — which is the shape a
     * unit change or a rescale actually takes. Without one it evaluates a single expression,
     * which is what a user typing "what is 0.36 times 1.1" wants.
     *
     * <p>The model states the relationship and the arithmetic happens here, which is the division
     * of labour that plays to what each is reliable at.
     */
    public String calculate(String formula, String variable, List<String> values,
                            List<String> variables) {
        if (formula == null || formula.isBlank()) {
            return "Give me a formula to work out.";
        }
        try {
            // Fixed names first: they hold for every value put through the formula, so a
            // conversion can carry its constants and still run over a whole column.
            java.util.Map<String, java.math.BigDecimal> fixed = Formula.names();
            for (String binding : variables == null ? List.<String>of() : variables) {
                int equals = binding == null ? -1 : binding.indexOf('=');
                if (equals <= 0) {
                    return "Write each value as name=number, like rate=0.3. I couldn't read \""
                            + binding + "\".";
                }
                String name = binding.substring(0, equals).trim();
                try {
                    fixed.put(name, new java.math.BigDecimal(binding.substring(equals + 1).trim()));
                } catch (NumberFormatException e) {
                    return "\"" + binding.substring(equals + 1).trim() + "\" isn't a number, so I "
                            + "can't use it for " + name + ".";
                }
            }

            boolean batched = values != null && !values.isEmpty()
                    && variable != null && !variable.isBlank();
            if (!batched) {
                return formula.trim() + " = " + Formula.evaluate(formula, fixed).toPlainString();
            }

            List<String> answers = new ArrayList<>();
            for (String value : values) {
                java.math.BigDecimal input;
                try {
                    input = new java.math.BigDecimal(value.trim());
                } catch (NumberFormatException e) {
                    // Named rather than skipped: a silently dropped input becomes a rule that
                    // never gets converted, in a table that otherwise looks finished.
                    answers.add("\"" + value + "\" isn't a number");
                    continue;
                }
                java.util.Map<String, java.math.BigDecimal> round = Formula.names();
                round.putAll(fixed);
                round.put(variable.trim(), input);
                answers.add(value.trim() + " → " + Formula.evaluate(formula, round).toPlainString());
            }
            return "Using " + formula.trim() + ": " + String.join(", ", answers);
        } catch (Formula.BadFormula e) {
            return e.getMessage();
        } catch (Exception e) {
            return "I couldn't work that formula out.";
        }
    }

    /**
     * Runs the gates over the working copy and says what, if anything, the changes broke.
     *
     * <p>The tool that matters most. A single boundary change on a range table is almost always
     * an incomplete edit — moving "High" to {@code <=20} leaves the neighbouring rule reading
     * {@code (10..70]}, so 15 matches both — and the one-shot protocol could only refuse the
     * result. Here the model is told what it left behind and can move the neighbour itself,
     * which is the multi-edit problem solved without a planner.
     */
    public String check() {
        List<GateResult> results = gates.run(original, working);
        List<String> failures = results.stream()
                .filter(gate -> !gate.ok())
                .map(GateResult::detail)
                .filter(detail -> detail != null && !detail.isBlank())
                .toList();
        if (failures.isEmpty()) {
            return edits.isEmpty()
                    ? "Nothing has been changed yet."
                    : "All checks pass. " + String.join(" ", edits);
        }
        return String.join("\n", failures)
                + "\nFix this with further changes, or undo what you changed.";
    }

    /** The gates as the controller needs them: structured, and against the original. */
    public List<GateResult> gates() {
        return gates.run(original, working);
    }

    private static Element firstChild(Element parent, String localName) {
        return BpmnDocument.childElements(parent).stream()
                .filter(child -> localName.equals(child.getLocalName()))
                .findFirst()
                .orElse(null);
    }

    private static String textOf(Element cell) {
        return BpmnDocument.childElements(cell).stream()
                .filter(child -> "text".equals(child.getLocalName()))
                .map(child -> child.getTextContent().trim())
                .findFirst()
                .orElse("");
    }

    /** The FEEL body of a literal expression, which is business English and worth showing. */
    private static String formulaOf(Element decision) {
        Element literal = firstChild(decision, "literalExpression");
        return literal == null ? "" : textOf(literal);
    }
}
