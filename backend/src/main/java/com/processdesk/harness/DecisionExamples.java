package com.processdesk.harness;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Element;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * One worked decision table, fetched when the model has just failed twice on the same one.
 *
 * <p>The corpus is the DMN Technology Compatibility Kit in {@code corpora/dmn-tck}: models the
 * specification says must produce particular answers, paired with the inputs and the answers.
 * That makes it the one body of DMN in this repository that is known correct rather than merely
 * real, which is what a worked example has to be.
 *
 * <p>Three decisions about when this fires, and all three are about cost.
 *
 * <p><b>On failure, not on entry.</b> A retrieval that runs on every request spends the context
 * budget before the model has done anything wrong, and on a machine without a GPU that budget is
 * seconds rather than tokens. The trigger is a decision whose coverage check has failed twice,
 * because one failure is the ordinary half-finished boundary edit the loop already recovers from
 * by itself, and two is a model that has stopped making progress.
 *
 * <p><b>One example, not three.</b> Ranked by BM25 over the table's own vocabulary and filtered
 * to tables shaped like the one in trouble: same hit policy, comparable width, and ranges rather
 * than equalities, because ranges are where every measured failure has been.
 *
 * <p><b>Absent is fine.</b> {@code corpora/} is gitignored and reproducible from
 * {@code scripts/fetch-corpora.sh}, so a clone that has never run it has no examples and must
 * behave exactly as before. Everything here degrades to {@link Optional#empty()}.
 */
@Component
public class DecisionExamples {

    private static final Logger log = LoggerFactory.getLogger(DecisionExamples.class);

    /** Small enough to read, big enough to show a boundary being handled. */
    private static final int MIN_RULES = 3;
    private static final int MAX_RULES = 7;

    /** A ceiling on indexing work, not on quality. The corpus has ~160 files. */
    private static final int MAX_TABLES = 400;

    private final Path root;
    private final boolean enabled;

    /** Built on first use. Null until then, empty when there is nothing to build from. */
    private volatile List<Table> tables;
    private volatile Bm25 index;

    public DecisionExamples(
            @Value("${processdesk.examples.dir:../corpora/dmn-tck}") String dir,
            @Value("${processdesk.examples.enabled:true}") boolean enabled) {
        this.root = Path.of(dir);
        this.enabled = enabled;
    }

    /** One indexed table, already rendered in the shape {@code show_decision} uses. */
    /**
     * @param rangeScore how many of its rules test the first column with a range or a
     *                   comparison rather than an equality. The point of an example is the
     *                   boundary, so a table where every rule has one teaches more than a table
     *                   where one does.
     */
    private record Table(String id, String name, String source, String hitPolicy,
                         int columns, double rangeScore, String rendered, String searchable) {}

    /**
     * A table shaped like this one that gets its ranges right, if the corpus holds one.
     *
     * @param name       the decision the model is stuck on, for ranking
     * @param columns    its column headings, also for ranking
     * @param hitPolicy  its hit policy; only tables under the same one are offered, because
     *                   under COLLECT two rules covering a value is correct and under UNIQUE it
     *                   is the bug being fixed
     */
    public Optional<String> like(String name, List<String> columns, String hitPolicy) {
        if (!enabled) {
            return Optional.empty();
        }
        load();
        if (index == null || tables.isEmpty()) {
            return Optional.empty();
        }

        String policy = normalise(hitPolicy);
        int width = columns == null ? 0 : columns.size();
        Map<String, Table> byId = new LinkedHashMap<>();
        tables.forEach(table -> byId.put(table.id(), table));

        // Structure decides eligibility; words only decide the order. This is the opposite of
        // ordinary retrieval and it is deliberate: the example is not being fetched for what it
        // says, it is being fetched for the shape it demonstrates. A lending table and a
        // shipping table share no vocabulary and the same boundary lesson, and requiring word
        // overlap would return nothing at all for most real files. Measured: a BM25 query of
        // "Affordability Category DTI" against the TCK matches no document, and an
        // overlap-first implementation of this method silently never fired.
        List<Table> eligible = tables.stream()
                .filter(candidate -> candidate.hitPolicy().equals(policy))
                .filter(candidate -> width == 0 || Math.abs(candidate.columns() - width) <= 1)
                .toList();
        if (eligible.isEmpty()) {
            return Optional.empty();
        }

        // Among the eligible, prefer the ones that are mostly boundaries. Measured while
        // building this: without it, a four-column request was answered with a table whose
        // rules were mostly equalities and one age comparison, which is a worse teacher than
        // a one-column discount table whose five rules tile a number line end to end.
        List<Table> best = eligible.stream()
                .filter(candidate -> candidate.rangeScore() >= 0.6)
                .toList();
        List<Table> pool = best.isEmpty() ? eligible : best;

        String query = name + " " + String.join(" ", columns == null ? List.of() : columns);
        for (String id : index.rank(query)) {
            Table ranked = byId.get(id);
            if (ranked != null && pool.contains(ranked)) {
                return Optional.of(render(ranked));
            }
        }
        // Nothing shared a word, which is the normal case: a lending table and a shipping
        // table have the same boundary lesson and no vocabulary in common. Fall back to the
        // most range-like, then the narrowest, because the fewer columns it has the more
        // obviously it is about the boundaries rather than about its own domain.
        return pool.stream()
                .max(java.util.Comparator.comparingDouble(Table::rangeScore)
                        .thenComparing(java.util.Comparator.comparingInt(Table::columns).reversed()))
                .map(DecisionExamples::render);
    }

    /**
     * What the model is shown, and what it is told about it.
     *
     * <p>Two things are stated rather than implied, because a table dropped into a conversation
     * with no framing is a table the model may try to edit. It is not this file, and it is from
     * the conformance suite, which is a claim about where it came from rather than a claim that
     * it passes this project's coverage gate. The closing line points at what to look at
     * instead of asserting a property nothing here has checked.
     */
    private static String render(Table table) {
        return "Here is a table from the DMN conformance suite, where the specification fixes "
                + "what it must produce. It is not this file and there is nothing to change in "
                + "it. It is an example of the shape you are trying to make.\n\n"
                + "\"" + table.name() + "\" (" + table.source() + "), hit policy "
                + table.hitPolicy() + "\n" + table.rendered()
                + "\n\nLook at how its conditions divide the range: each one starts where the "
                + "one before it stops, so no value lands in two rules and none lands in "
                + "nothing. That is what the check is asking of yours.";
    }

    /**
     * Reads and indexes the corpus once.
     *
     * <p>Lazy because most requests never fail twice, and a clone that has not fetched the
     * corpora should not pay a startup cost for a directory that is not there. Synchronised on
     * the class rather than made fancy: this happens at most once per process.
     */
    private synchronized void load() {
        if (tables != null) {
            return;
        }
        List<Table> found = new ArrayList<>();
        if (!Files.isDirectory(root)) {
            log.info("no example corpus at {}; worked examples are off. "
                    + "scripts/fetch-corpora.sh creates it.", root.toAbsolutePath());
            tables = List.of();
            return;
        }

        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> files = walk
                    .filter(path -> path.toString().endsWith(".dmn"))
                    .sorted()
                    .toList();
            for (Path file : files) {
                if (found.size() >= MAX_TABLES) {
                    break;
                }
                collect(file, found);
            }
        } catch (Exception e) {
            log.warn("could not read the example corpus at {}", root, e);
        }

        tables = List.copyOf(found);
        if (!tables.isEmpty()) {
            Map<String, String> documents = new LinkedHashMap<>();
            tables.forEach(table -> documents.put(table.id(), table.searchable()));
            index = new Bm25(documents);
        }
        log.info("indexed {} worked decision tables from {}", tables.size(), root);
    }

    private static void collect(Path file, List<Table> into) {
        String xml;
        try {
            xml = Files.readString(file);
        } catch (Exception e) {
            return;
        }
        BpmnDocument doc;
        try {
            doc = BpmnDocument.parse(xml);
        } catch (Exception e) {
            // The TCK contains deliberately invalid models. Skipping them is the point of
            // parsing here rather than pattern-matching the text.
            return;
        }

        for (Element decision : doc.elements("decision")) {
            Element table = BpmnDocument.childElements(decision).stream()
                    .filter(child -> "decisionTable".equals(child.getLocalName()))
                    .findFirst().orElse(null);
            if (table == null) {
                continue;
            }
            List<Element> rules = BpmnDocument.childElements(table).stream()
                    .filter(child -> "rule".equals(child.getLocalName()))
                    .toList();
            if (rules.size() < MIN_RULES || rules.size() > MAX_RULES) {
                continue;
            }
            List<String> columns = DecisionEditor.columnsOf(table);
            if (columns.isEmpty()) {
                continue;
            }

            String rendered = renderRules(columns, rules);
            // Ranges are the whole reason this exists. A table of equalities cannot demonstrate
            // a boundary meeting its neighbour, so it is not a useful example however well it
            // matches the words.
            if (!rendered.matches("(?s).*[<>]=?\\s*-?\\d.*")
                    && !rendered.matches("(?s).*[\\[(]\\s*-?[\\d.]+\\s*\\.\\..*")) {
                continue;
            }

            String name = BpmnDocument.displayName(decision);
            into.add(new Table(file + "#" + name, name, file.getFileName().toString(),
                    normalise(table.getAttribute("hitPolicy")), columns.size(),
                    rangeScore(rules), rendered,
                    name + " " + String.join(" ", columns) + " " + rendered));
        }
    }

    /** How much of this table is boundaries: the share of rules whose first cell is a range. */
    private static double rangeScore(List<Element> rules) {
        long ranged = rules.stream()
                .map(rule -> BpmnDocument.childElements(rule).stream()
                        .filter(cell -> "inputEntry".equals(cell.getLocalName()))
                        .findFirst().map(DecisionExamples::textOf).orElse(""))
                .filter(text -> text.matches("\\s*[<>]=?\\s*-?[\\d.]+.*")
                        || text.matches("\\s*[\\[(]\\s*-?[\\d.]+\\s*\\.\\..*"))
                .count();
        return rules.isEmpty() ? 0 : (double) ranged / rules.size();
    }

    /** The same shape {@code show_decision} prints, so the model reads one format, not two. */
    private static String renderRules(List<String> columns, List<Element> rules) {
        StringBuilder out = new StringBuilder("  Columns: ").append(String.join(", ", columns));
        int number = 1;
        for (Element rule : rules) {
            out.append("\n    ").append(number++).append(". ");
            List<String> conditions = new ArrayList<>();
            List<String> results = new ArrayList<>();
            int column = 0;
            for (Element cell : BpmnDocument.childElements(rule)) {
                String text = textOf(cell);
                if ("inputEntry".equals(cell.getLocalName())) {
                    String label = column < columns.size() ? columns.get(column) : "input";
                    conditions.add("-".equals(text) || text.isEmpty()
                            ? "any " + label : label + ": " + text);
                    column++;
                } else if ("outputEntry".equals(cell.getLocalName())) {
                    results.add(text);
                }
            }
            out.append(String.join(", ", conditions)).append(" → ").append(String.join(", ", results));
        }
        return out.toString();
    }

    private static String textOf(Element cell) {
        return BpmnDocument.childElements(cell).stream()
                .filter(child -> "text".equals(child.getLocalName()))
                .map(child -> child.getTextContent().trim())
                .findFirst()
                .orElse("");
    }

    /** DMN leaves the attribute off for UNIQUE, which is 15 of the 643 tables in the corpus. */
    private static String normalise(String hitPolicy) {
        String trimmed = hitPolicy == null ? "" : hitPolicy.trim().toUpperCase(Locale.ROOT);
        return trimmed.isBlank() ? "UNIQUE" : trimmed;
    }
}
