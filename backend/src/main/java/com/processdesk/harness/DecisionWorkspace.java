package com.processdesk.harness;

import org.w3c.dom.Element;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
    // Decisions the model has actually looked at. "Call show_decision before every set_cell"
    // used to be a sentence in the prompt; a sentence is something a small model can skip and
    // a guessed rule number is a wasted change. It is a precondition now.
    private final java.util.Set<String> shown = new java.util.LinkedHashSet<>();
    // The notation card is spent once per conversation. It is attached to the first table the
    // model sees rather than carried in the system prompt, so a request that never opens a
    // table never pays for it — and it arrives on the turn before the notation is needed.
    private boolean notationSpent;
    // How many times the checks have failed since a given decision was last written to. One
    // failure is the ordinary half-finished boundary edit, which the loop recovers from by
    // itself. Two is a model that has stopped making progress, and the only point at which a
    // worked example is worth what it costs to prefill.
    private final java.util.Map<String, Integer> failures = new java.util.HashMap<>();
    private final DecisionExamples examples;

    private String working;

    public DecisionWorkspace(String xml, DecisionEditor editor, DecisionGates gates) {
        this(xml, editor, gates, null);
    }

    public DecisionWorkspace(String xml, DecisionEditor editor, DecisionGates gates,
                             DecisionExamples examples) {
        this.original = xml;
        this.working = xml;
        this.editor = editor;
        this.gates = gates;
        this.examples = examples;
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
            shown.add(actual.toLowerCase(java.util.Locale.ROOT));
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
            return out + cards(hitPolicy);
        } catch (Exception e) {
            return "I couldn't read that decision.";
        }
    }

    private boolean hasSeen(String decision) {
        return decision != null
                && shown.contains(decision.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * Refuses a write to a table the model has not looked at, and says what to do instead.
     *
     * <p>"Look before you change" was a paragraph of the system prompt, which is to say it was
     * a request. The rule number and the cell's current value are both coordinates that only
     * {@code show_decision} can supply, so a write that has not read is a guess — and
     * {@code expect} turns a guess into a wasted turn rather than a wrong edit, which is safe
     * but not free. Refusing it costs the same turn and returns something the model can act on.
     *
     * <p>Retryable, and the loop knows it: a refusal here is the one case where repeating the
     * identical call after a {@code show_decision} is the correct next move, so the loop's
     * repeat cutoff is cleared when a decision is shown.
     */
    private static String lookFirst(String decision) {
        return "You haven't looked at \"" + decision + "\" yet. Call show_decision first — the "
                + "rule number and the cell's current value both come from what it shows you, "
                + "and guessing either one wastes the change.";
    }

    /**
     * The two reference cards, attached to a table rather than carried in the system prompt.
     *
     * <p>Both were paragraphs of {@code DECISION_TOOLS}, which is prefilled on every turn of
     * every conversation — a cost paid even by a request that never opens a table, and on a
     * CPU-only machine a cost measured in seconds rather than tokens. Attached here they are
     * paid once, by the requests that need them, on the turn before they are needed.
     *
     * <p>Neither is retrieved. The part of DMN that governs what this tool can write is small
     * enough to write down, and an index that returns forty lines you could have pasted is the
     * expensive way to get a worse copy of them.
     */
    private String cards(String hitPolicy) {
        StringBuilder out = new StringBuilder();
        if (!notationSpent) {
            notationSpent = true;
            out.append("""

                    Notation:
                      <=20      20 or less             >70       over 70
                      [18..35]  18 to 35, both ends    (10..70]  over 10, up to and including 70
                      "Poor"    a word, always quoted  -         this column does not matter\
                    """);
        }
        // Rule order means nothing under UNIQUE and is priority under FIRST, which changes what
        // a correct add_rule looks like. 229 of the 643 tables in corpora/ are not UNIQUE — too
        // many to leave unsaid, too few to pay for on every request.
        String policy = hitPolicy == null ? "" : hitPolicy.trim().toUpperCase(Locale.ROOT);
        if (!policy.isBlank() && !"UNIQUE".equals(policy)) {
            out.append("\n\n").append(switch (policy) {
                case "FIRST" -> "Rule order is priority here: the first rule that matches wins, "
                        + "so a new rule goes last and cannot change what an existing rule "
                        + "already decides.";
                case "PRIORITY" -> "Outcomes here are ranked, not ordered by rule. Which outcome "
                        + "wins is decided by the order the output's allowed values are listed "
                        + "in, not by where a rule sits.";
                case "COLLECT" -> "This table gathers every rule that matches rather than "
                        + "choosing one, so two rules covering the same value is normal here.";
                case "ANY" -> "Rules here may overlap, but every overlapping rule has to produce "
                        + "the same outcome.";
                case "RULE ORDER", "OUTPUT ORDER" -> "This table returns every match, in order, "
                        + "so rule order changes the answer.";
                default -> "This table's hit policy is " + policy
                        + ", so more than one rule may apply.";
            });
        }
        return out.toString();
    }

    /**
     * Writes one cell of one rule, on the working copy.
     *
     * <p>A refusal is returned rather than thrown, and returned in the editor's own words —
     * "Rule 2 of X has Member Tier set to Executive, not Platinum" tells the model precisely
     * which half of its coordinate was wrong, which is enough for it to look again and retry.
     */
    public String setCell(String decision, int rule, String column, String expect, String to) {
        if (!hasSeen(decision)) {
            return lookFirst(decision);
        }
        try {
            DecisionEditor.EditResult result =
                    editor.setCell(working, decision, rule, column, expect, to);
            working = result.xml();
            edits.add(result.summary());
            return afterChange(result.summary(), decision);
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
        if (!hasSeen(decision)) {
            return lookFirst(decision);
        }
        try {
            DecisionEditor.EditResult result =
                    editor.addRule(working, decision, conditions, outcomes);
            working = result.xml();
            edits.add(result.summary());
            return afterChange(result.summary(), decision);
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        } catch (Exception e) {
            return "That rule couldn't be added. Nothing was changed.";
        }
    }

    /**
     * What every write returns: what was done, what it broke, and the table as it now stands.
     *
     * <p>Three of the eight turns on the documented success path existed only because
     * verification and re-reading were the model's job — {@code set_cell, check, show_decision,
     * set_cell}. Both are now consequences of writing, so the same self-correction happens in
     * five turns instead of eight, and it happens whether or not the model remembered to ask.
     *
     * <p>The prompt used to carry this as an instruction ("Call check after changing a
     * boundary"). An instruction is something a small model can skip. This is not.
     *
     * <p>The re-render is not free — a fifteen-rule table is a few hundred tokens on the next
     * turn — but it is the same few hundred tokens a {@code show_decision} would have cost,
     * without the model turn wrapped around them. On a CPU-bound machine, where a turn costs
     * far more than the tokens inside it, that trade gets better rather than worse.
     */
    private String afterChange(String summary, String decision) {
        String verdict = verdict();
        String result = summary + "\n" + verdict + "\nThe table now reads:\n"
                + showDecision(decision);

        String key = decision == null ? "" : decision.trim().toLowerCase(Locale.ROOT);
        if (verdict.startsWith("All checks pass")) {
            failures.remove(key);
            return result;
        }
        // Second failure on the same decision. The first is expected — moving one boundary
        // leaves a hole beside it and the model fixes it next turn — so an example then would
        // be paid for on almost every successful edit. Twice means the corrections are not
        // converging, which is the only case where showing a table that gets it right is worth
        // the seconds it costs to prefill.
        if (failures.merge(key, 1, Integer::sum) == 2 && examples != null) {
            return examples.like(decision, columnsOf(decision), hitPolicyOf(decision))
                    .map(example -> result + "\n\n" + example)
                    .orElse(result);
        }
        return result;
    }

    /** The open table's columns and hit policy, for ranking an example against it. */
    private List<String> columnsOf(String decision) {
        Element table = tableOf(decision);
        return table == null ? List.of() : DecisionEditor.columnsOf(table);
    }

    private String hitPolicyOf(String decision) {
        Element table = tableOf(decision);
        return table == null ? "" : table.getAttribute("hitPolicy");
    }

    private Element tableOf(String decision) {
        if (decision == null || decision.isBlank()) {
            return null;
        }
        try {
            return BpmnDocument.parse(working).elements("decision").stream()
                    .filter(d -> BpmnDocument.displayName(d).equalsIgnoreCase(decision.trim()))
                    .findFirst()
                    .map(d -> firstChild(d, "decisionTable"))
                    .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    /** The gates' verdict on the working copy, without echoing the edits back. */
    private String verdict() {
        List<String> failures = gates.run(original, working).stream()
                .filter(gate -> !gate.ok())
                .map(GateResult::detail)
                .filter(detail -> detail != null && !detail.isBlank())
                .toList();
        return failures.isEmpty()
                ? "All checks pass."
                // The gate's own wording already names the remedy — "moving one boundary
                // usually means moving the one next to it" — so this only has to say who does
                // it, and name the way out for a model that cannot.
                : String.join("\n", failures)
                        + "\nFix it with another set_cell, or call panic if you cannot.";
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
