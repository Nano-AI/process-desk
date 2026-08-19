package com.processdesk.harness;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Okapi BM25 over a small set of documents.
 *
 * <p>Used to decide which decisions a request is about, so the model can be shown those
 * rather than the whole file. A decision model is mostly English once you look at it —
 * decision names, column headings, descriptions, and the FEEL inside a literal expression
 * all read as words — so term overlap is a better signal here than it first appears.
 *
 * <p>Deliberately not embeddings. This runs in microseconds, needs no model call and no
 * quota, and is exact about why it ranked something: the terms matched. On a metered
 * provider that matters, and a wrong ranking is inspectable rather than mysterious.
 */
final class Bm25 {

    /** Standard parameters. k1 damps repeated terms; b controls length normalisation. */
    private static final double K1 = 1.5;
    private static final double B = 0.75;

    /**
     * Words carried by almost every request, which would otherwise let any decision match.
     * Kept short on purpose: "score", "risk", "category" are the words that discriminate
     * here, and an aggressive list would remove them.
     */
    private static final Set<String> NOISE = Set.of(
            "a", "an", "and", "are", "as", "at", "be", "but", "by", "can", "do", "does", "for",
            "from", "has", "have", "i", "if", "in", "is", "it", "its", "me", "of", "on", "or",
            "please", "should", "so", "than", "that", "the", "then", "there", "this", "to", "up",
            "want", "was", "we", "what", "when", "where", "which", "why", "will", "with", "you");

    private final List<String> ids = new ArrayList<>();
    private final List<Map<String, Integer>> frequencies = new ArrayList<>();
    private final Map<String, Integer> documentsContaining = new HashMap<>();
    private double averageLength;

    /** @param documents id to searchable text, in the order results should tie-break */
    Bm25(Map<String, String> documents) {
        int totalLength = 0;
        for (Map.Entry<String, String> entry : documents.entrySet()) {
            List<String> terms = tokenize(entry.getValue());
            Map<String, Integer> counts = new HashMap<>();
            for (String term : terms) {
                counts.merge(term, 1, Integer::sum);
            }
            ids.add(entry.getKey());
            frequencies.add(counts);
            counts.keySet().forEach(term -> documentsContaining.merge(term, 1, Integer::sum));
            totalLength += terms.size();
        }
        averageLength = ids.isEmpty() ? 0 : (double) totalLength / ids.size();
    }

    /** Document ids whose score is above zero, best first. */
    List<String> rank(String query) {
        List<String> terms = tokenize(query);
        Map<String, Double> scores = new LinkedHashMap<>();
        for (int i = 0; i < ids.size(); i++) {
            double score = score(terms, frequencies.get(i));
            if (score > 0) {
                scores.put(ids.get(i), score);
            }
        }
        return scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .map(Map.Entry::getKey)
                .toList();
    }

    private double score(List<String> queryTerms, Map<String, Integer> document) {
        int length = document.values().stream().mapToInt(Integer::intValue).sum();
        double total = 0;
        for (String term : queryTerms) {
            Integer frequency = document.get(term);
            if (frequency == null) {
                continue;
            }
            int containing = documentsContaining.getOrDefault(term, 0);
            // The usual BM25 idf goes negative for a term in more than half the documents,
            // which would make a common word count against a match. Floored at a small
            // positive: with a handful of decisions, "risk" appearing in several of them is
            // weak evidence, not evidence of the opposite.
            double idf = Math.max(0.01,
                    Math.log(1 + (ids.size() - containing + 0.5) / (containing + 0.5)));
            double normalised = averageLength == 0 ? 0 : length / averageLength;
            total += idf * (frequency * (K1 + 1)) / (frequency + K1 * (1 - B + B * normalised));
        }
        return total;
    }

    /**
     * Splits text into comparable words.
     *
     * <p>Identifiers in these files are compounds — {@code Borrower.EmploymentStatus},
     * {@code Pre-bureauRiskCategory} — and a user types "employment status". Splitting on
     * case boundaries as well as punctuation is what lets those meet. Numbers are kept:
     * "under 18" and a rule holding {@code <18} share a term worth matching on.
     */
    static List<String> tokenize(String text) {
        if (text == null) {
            return List.of();
        }
        String spaced = text
                .replaceAll("([a-z0-9])([A-Z])", "$1 $2")
                .replaceAll("[^A-Za-z0-9]+", " ")
                .toLowerCase(Locale.ROOT);

        List<String> terms = new ArrayList<>();
        for (String word : spaced.split("\\s+")) {
            if (word.isBlank() || NOISE.contains(word)) {
                continue;
            }
            terms.add(stem(word));
        }
        return terms;
    }

    /**
     * Enough stemming to let "scores" find "score", and no more. A real stemmer would also
     * fold "category" and "categorise", which is not worth a dependency for text this short.
     */
    private static String stem(String word) {
        if (word.length() > 3 && word.endsWith("s") && !word.endsWith("ss")) {
            return word.substring(0, word.length() - 1);
        }
        return word;
    }
}
