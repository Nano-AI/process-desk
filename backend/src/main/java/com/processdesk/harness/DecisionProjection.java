package com.processdesk.harness;

import org.w3c.dom.Element;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * What the model is allowed to see of a decision model: what it decides, what it needs to
 * know, and the rules in the order they are tried.
 *
 * <p>A decision table is the part of a DMN file a person actually asks about, and it is
 * small — a handful of rows of conditions and outcomes. Handing those over as text lets the
 * assistant explain *why* an answer comes out the way it does, instead of only naming the
 * decision. Everything else in the file (identifiers, diagram, type definitions, FEEL
 * namespaces) is left behind.
 */
public record DecisionProjection(String modelName, List<String> inputs, List<Decision> decisions,
                                 Vocabulary vocabulary) implements AssetProjection {

    public static final DecisionProjection EMPTY =
            new DecisionProjection("", List.of(), List.of(), new Vocabulary(List.of(), List.of(), List.of(), List.of()));

    public DecisionProjection(String modelName, List<String> inputs, List<Decision> decisions) {
        this(modelName, inputs, decisions, new Vocabulary(List.of(), List.of(), List.of(), List.of()));
    }

    /**
     * One decision: its name, how ties are broken, and its rules as readable lines.
     *
     * <p>It also carries what it is made of — its columns and the values they hold — and the
     * text it can be found by. Both exist so a projection can be narrowed to the decisions a
     * request is about without the vocabulary and the prose disagreeing: the enums the model
     * is constrained to must describe the rules it was actually shown.
     */
    public record Decision(String name, String hitPolicy, List<String> rules,
                           List<String> columns, List<String> conditions, List<String> outcomes,
                           java.util.Map<String, List<String>> conditionsByColumn,
                           String searchText) {

        public Decision(String name, String hitPolicy, List<String> rules) {
            this(name, hitPolicy, rules, List.of(), List.of(), List.of(), java.util.Map.of(),
                    name == null ? "" : name);
        }
    }

    /** True when the file is a DMN decision model rather than a process. */
    public static boolean looksLikeDecisionModel(String xml) {
        try {
            BpmnDocument doc = BpmnDocument.parse(xml);
            return !doc.elements("decision").isEmpty() || !doc.elements("inputData").isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    public static DecisionProjection of(String xml) {
        try {
            BpmnDocument doc = BpmnDocument.parse(xml);

            String modelName = doc.elements("definitions").stream()
                    .map(el -> el.getAttribute("name"))
                    .filter(name -> !name.isBlank())
                    .findFirst()
                    .orElse("");

            List<String> inputs = doc.elements("inputData").stream()
                    .map(BpmnDocument::displayName)
                    .filter(name -> !name.isBlank())
                    .toList();

            List<Decision> decisions = doc.elements("decision").stream()
                    .map(decision -> readDecision(doc, decision))
                    .toList();

            // The vocabulary travels with the projection: it is what the schema enumerates,
            // and the seam only ever hands the model a projection. Built from the decisions
            // themselves so that narrowing the projection narrows the vocabulary with it.
            return new DecisionProjection(modelName, inputs, decisions, vocabularyOf(decisions));
        } catch (Exception e) {
            return EMPTY;
        }
    }

    private static Decision readDecision(BpmnDocument doc, Element decision) {
        String name = BpmnDocument.displayName(decision);
        Element table = BpmnDocument.childElements(decision).stream()
                .filter(child -> "decisionTable".equals(child.getLocalName()))
                .findFirst()
                .orElse(null);

        if (table == null) {
            // No table to read, but the decision is still findable: its name, its description,
            // and any FEEL body are English, and that is what a search has to work from.
            return new Decision(name, "", List.of(), List.of(), List.of(), List.of(),
                    java.util.Map.of(), name + " " + proseOf(decision));
        }

        // The column headings, so a rule can name what each of its conditions is testing.
        List<String> conditionLabels = BpmnDocument.childElements(table).stream()
                .filter(child -> "input".equals(child.getLocalName()))
                .map(input -> BpmnDocument.childElements(input).stream()
                        .filter(e -> "inputExpression".equals(e.getLocalName()))
                        .flatMap(e -> BpmnDocument.childElements(e).stream())
                        .filter(e -> "text".equals(e.getLocalName()))
                        .map(e -> e.getTextContent().trim())
                        .findFirst()
                        .orElse("input"))
                .toList();

        List<String> rules = new ArrayList<>();
        java.util.LinkedHashSet<String> allConditions = new java.util.LinkedHashSet<>();
        java.util.LinkedHashSet<String> allOutcomes = new java.util.LinkedHashSet<>();
        java.util.Map<String, java.util.LinkedHashSet<String>> byColumn = new java.util.LinkedHashMap<>();

        for (Element rule : BpmnDocument.childElements(table)) {
            if (!"rule".equals(rule.getLocalName())) {
                continue;
            }
            List<String> conditions = new ArrayList<>();
            List<String> outcomes = new ArrayList<>();
            int column = 0;
            for (Element cell : BpmnDocument.childElements(rule)) {
                String text = BpmnDocument.childElements(cell).stream()
                        .filter(e -> "text".equals(e.getLocalName()))
                        .map(e -> e.getTextContent().trim())
                        .findFirst()
                        .orElse("");
                if ("inputEntry".equals(cell.getLocalName())) {
                    String label = column < conditionLabels.size() ? conditionLabels.get(column) : "input";
                    // "-" is DMN for "this column does not matter for this rule".
                    boolean anyValue = "-".equals(text) || text.isEmpty();
                    conditions.add(anyValue ? "any " + label : label + " " + text);
                    if (!anyValue) {
                        allConditions.add(text);
                        byColumn.computeIfAbsent(label, k -> new java.util.LinkedHashSet<>()).add(text);
                    }
                    column++;
                } else if ("outputEntry".equals(cell.getLocalName())) {
                    outcomes.add(text);
                    if (!text.isEmpty() && !"-".equals(text)) {
                        allOutcomes.add(text);
                    }
                }
            }
            rules.add(String.join(", ", conditions) + " → " + String.join(", ", outcomes));
        }

        java.util.Map<String, List<String>> perColumn = new java.util.LinkedHashMap<>();
        byColumn.forEach((c, values) -> perColumn.put(c, List.copyOf(values)));

        // What this decision can be found by: its name, what it looks at, what it can say,
        // and any prose the modeller left behind.
        String searchText = String.join(" ", name, String.join(" ", conditionLabels),
                String.join(" ", allConditions), String.join(" ", allOutcomes), proseOf(decision));

        return new Decision(name, table.getAttribute("hitPolicy"), rules,
                List.copyOf(conditionLabels), List.copyOf(allConditions), List.copyOf(allOutcomes),
                java.util.Map.copyOf(perColumn), searchText);
    }

    /**
     * The English a modeller left in a decision: its description, and any FEEL body.
     *
     * <p>A literal expression is code, but it is code written out of business words —
     * {@code if Return History > 5 then 90 else 20} — so it is worth searching even though
     * it cannot yet be rendered as rules. It is the one part of the 86% of decisions we
     * cannot read that is still useful for finding them.
     */
    private static String proseOf(Element decision) {
        StringBuilder prose = new StringBuilder();
        collectProse(decision, prose, 0);
        return prose.toString();
    }

    private static void collectProse(Element element, StringBuilder into, int depth) {
        if (depth > 6) {
            return;
        }
        for (Element child : BpmnDocument.childElements(element)) {
            String tag = child.getLocalName();
            if ("decisionTable".equals(tag)) {
                continue;
            }
            if ("description".equals(tag) || "text".equals(tag)) {
                into.append(' ').append(child.getTextContent().trim());
            }
            collectProse(child, into, depth + 1);
        }
    }

    /** Below this many decisions, sending everything is cheap and narrowing only adds risk. */
    private static final int NARROW_ABOVE = 6;

    /** How many to keep when narrowing. Generous: a near miss in ranking must still land. */
    private static final int KEEP = 5;

    /**
     * The same model, reduced to the decisions this request appears to be about.
     *
     * <p>Sending all eleven decisions of the lending model costs about a thousand tokens and,
     * more importantly, offers the model eleven decisions' worth of columns and values to
     * choose between when the request concerns one of them. Ranking by term overlap is enough
     * to pick the right few: these files are written in business words, and a request uses
     * the same ones.
     *
     * <p>Three guards, because dropping a decision the user meant is a worse failure than
     * sending too much:
     * <ul>
     *   <li>a small model is never narrowed;
     *   <li>a decision whose name the request mentions is always kept, whatever it scored;
     *   <li>a request that matches nothing keeps everything, rather than silently keeping an
     *       arbitrary five.
     * </ul>
     */
    public DecisionProjection focusedOn(String request) {
        if (decisions.size() <= NARROW_ABOVE || request == null || request.isBlank()) {
            return this;
        }

        java.util.Map<String, String> documents = new java.util.LinkedHashMap<>();
        for (Decision decision : decisions) {
            documents.put(decision.name(), decision.searchText());
        }

        List<String> ranked = new Bm25(documents).rank(request);
        if (ranked.isEmpty()) {
            return this;
        }

        java.util.LinkedHashSet<String> keep = new java.util.LinkedHashSet<>(
                ranked.subList(0, Math.min(KEEP, ranked.size())));

        // A decision the user named outranks anything a score has to say about it.
        String haystack = request.toLowerCase(java.util.Locale.ROOT);
        decisions.stream()
                .map(Decision::name)
                .filter(name -> !name.isBlank() && haystack.contains(name.toLowerCase(java.util.Locale.ROOT)))
                .forEach(keep::add);

        List<Decision> kept = decisions.stream().filter(d -> keep.contains(d.name())).toList();
        if (kept.isEmpty() || kept.size() == decisions.size()) {
            return this;
        }
        return new DecisionProjection(modelName, inputs, kept, vocabularyOf(kept));
    }

    /** The vocabulary of exactly these decisions, so enums describe what the model was shown. */
    private static Vocabulary vocabularyOf(List<Decision> decisions) {
        java.util.LinkedHashSet<String> columns = new java.util.LinkedHashSet<>();
        java.util.LinkedHashSet<String> conditions = new java.util.LinkedHashSet<>();
        java.util.LinkedHashSet<String> outcomes = new java.util.LinkedHashSet<>();
        java.util.Map<String, java.util.LinkedHashSet<String>> byColumn = new java.util.LinkedHashMap<>();

        for (Decision decision : decisions) {
            columns.addAll(decision.columns());
            conditions.addAll(decision.conditions());
            outcomes.addAll(decision.outcomes());
            decision.conditionsByColumn().forEach((column, values) ->
                    byColumn.computeIfAbsent(column, k -> new java.util.LinkedHashSet<>()).addAll(values));
        }

        java.util.Map<String, List<String>> perColumn = new java.util.LinkedHashMap<>();
        byColumn.forEach((column, values) -> perColumn.put(column, List.copyOf(values)));

        return new Vocabulary(List.copyOf(columns), List.copyOf(conditions), List.copyOf(outcomes),
                decisions.stream().map(Decision::name).filter(n -> !n.isBlank()).toList(),
                java.util.Map.copyOf(perColumn));
    }

    @Override
    public boolean isEmpty() {
        return decisions.isEmpty();
    }

    @Override
    public String render() {
        if (isEmpty()) {
            return "Decision model: (this file has no decisions)";
        }

        StringBuilder out = new StringBuilder("Decision model: ").append(modelName);
        if (!inputs.isEmpty()) {
            out.append("\nNeeds to know: ").append(String.join(", ", inputs));
        }
        for (Decision decision : decisions) {
            out.append("\nDecides \"").append(decision.name()).append("\"");
            if ("FIRST".equalsIgnoreCase(decision.hitPolicy())) {
                out.append(" (the first rule that matches wins)");
            }
            out.append(decision.rules().isEmpty() ? "" : ":");
            // Numbered, because a rule number is the address an edit is expressed in. The
            // user still speaks in values; turning "the High one" into rule 3 is the
            // model's job, and counting a short list is something it does reliably.
            int number = 1;
            for (String rule : decision.rules()) {
                out.append("\n  ").append(number++).append(". ").append(rule);
            }
        }
        return out.toString();
    }

    /**
     * The column headings, the condition values each column currently holds, and the
     * outcomes produced — everything a request can legitimately refer to.
     *
     * <p>Enumerating these lets the schema forbid a value the table does not contain, the
     * same way step names do for processes.
     */
    public record Vocabulary(List<String> columns, List<String> conditions, List<String> outcomes,
                             List<String> decisions,
                             java.util.Map<String, List<String>> conditionsByColumn) {

        public Vocabulary(List<String> columns, List<String> conditions, List<String> outcomes,
                          List<String> decisions) {
            this(columns, conditions, outcomes, decisions, java.util.Map.of());
        }

        /**
         * The values one column actually holds.
         *
         * <p>The flat list is every condition in the file, which for a model of any size is
         * both unreadable and wrong to quote back: "Income Risk Score" can only be
         * {@code >70}, {@code (10..70]} or {@code <=10}, and telling a user it might be
         * "Employed" — a value from a different decision entirely — is worse than saying
         * nothing.
         */
        public List<String> conditionsFor(String column) {
            if (column == null) {
                return List.of();
            }
            return conditionsByColumn.entrySet().stream()
                    .filter(entry -> entry.getKey().equalsIgnoreCase(column.trim()))
                    .map(java.util.Map.Entry::getValue)
                    .findFirst()
                    .orElse(List.of());
        }
    }

    public static Vocabulary vocabularyOf(String xml) {
        try {
            BpmnDocument doc = BpmnDocument.parse(xml);
            java.util.LinkedHashSet<String> columns = new java.util.LinkedHashSet<>();
            java.util.LinkedHashSet<String> conditions = new java.util.LinkedHashSet<>();
            java.util.LinkedHashSet<String> outcomes = new java.util.LinkedHashSet<>();
            java.util.Map<String, java.util.LinkedHashSet<String>> byColumn = new java.util.LinkedHashMap<>();

            for (Element table : doc.elements("decisionTable")) {
                List<String> tableColumns = DecisionEditor.columnsOf(table);
                columns.addAll(tableColumns);
                for (Element rule : BpmnDocument.childElements(table)) {
                    if (!"rule".equals(rule.getLocalName())) {
                        continue;
                    }
                    int columnIndex = 0;
                    for (Element cell : BpmnDocument.childElements(rule)) {
                        String text = BpmnDocument.childElements(cell).stream()
                                .filter(e -> "text".equals(e.getLocalName()))
                                .map(e -> e.getTextContent().trim())
                                .findFirst().orElse("");
                        boolean isCondition = "inputEntry".equals(cell.getLocalName());
                        boolean isOutcome = "outputEntry".equals(cell.getLocalName());
                        if (isCondition && !text.isEmpty() && !"-".equals(text)) {
                            conditions.add(text);
                            if (columnIndex < tableColumns.size()) {
                                byColumn.computeIfAbsent(tableColumns.get(columnIndex),
                                        k -> new java.util.LinkedHashSet<>()).add(text);
                            }
                        } else if (isOutcome && !text.isEmpty() && !"-".equals(text)) {
                            outcomes.add(text);
                        }
                        if (isCondition) {
                            columnIndex++;
                        }
                    }
                }
            }
            List<String> names = doc.elements("decision").stream()
                    .map(BpmnDocument::displayName).filter(n -> !n.isBlank()).toList();

            java.util.Map<String, List<String>> perColumn = new java.util.LinkedHashMap<>();
            byColumn.forEach((column, values) -> perColumn.put(column, List.copyOf(values)));

            return new Vocabulary(List.copyOf(columns), List.copyOf(conditions), List.copyOf(outcomes),
                    names, java.util.Map.copyOf(perColumn));
        } catch (Exception e) {
            return new Vocabulary(List.of(), List.of(), List.of(), List.of());
        }
    }

    /** Decision names, for messages that need to list what this file covers. */
    public List<String> decisionNames() {
        return decisions.stream().map(Decision::name).collect(Collectors.toList());
    }
}
