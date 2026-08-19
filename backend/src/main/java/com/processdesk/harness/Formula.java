package com.processdesk.harness;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;

/**
 * Evaluates an arithmetic formula the model wrote, with one variable substituted.
 *
 * <p>The model is good at stating a relationship — {@code (c * 9 / 5) + 32} — and less reliably
 * good at applying it to six numbers without a slip. Splitting those apart plays to what it does
 * well: it says the rule once, and the arithmetic is done here.
 *
 * <p>This matters because of a gap in the harness rather than a gap in the model. {@code expect}
 * checks the value being replaced, and the four gates check the shape and coverage of the result
 * — but nothing anywhere checks that a newly written number is the right number. A conversion
 * that produced 65 instead of 68 would pass every check and be wrong in a way no one could see.
 *
 * <p>Arithmetic only, and deliberately not a scripting engine. The formula is model output
 * derived from a file whose descriptions and annotations are in the model's context, so it is
 * untrusted input in the ordinary sense: there is no identifier to resolve, no method to call,
 * and nothing reachable but numbers.
 *
 * <p>{@link BigDecimal} rather than {@code double} because the values are business thresholds.
 * {@code 0.1 + 0.2} in binary floating point is {@code 0.30000000000000004}, and a decision table
 * whose boundary reads like that is one nobody will trust again.
 */
public final class Formula {

    /** Enough for the arithmetic a decision table needs; short enough to bound the work. */
    private static final int MAX_LENGTH = 200;
    private static final MathContext PRECISION = new MathContext(20, RoundingMode.HALF_UP);

    private final String source;
    private final java.util.Map<String, BigDecimal> values;
    private int at;

    private Formula(String source, java.util.Map<String, BigDecimal> values) {
        this.source = source;
        this.values = values;
    }

    /** Thrown with a message written for the model, so a bad formula is a recoverable turn. */
    public static class BadFormula extends IllegalArgumentException {
        BadFormula(String message) {
            super(message);
        }
    }

    /** Works the formula out with one named value, which is the common case. */
    public static BigDecimal evaluate(String expression, String variable, BigDecimal value) {
        java.util.Map<String, BigDecimal> values = names();
        if (variable != null && !variable.isBlank() && value != null) {
            values.put(variable.trim(), value);
        }
        return evaluate(expression, values);
    }

    /** A name lookup that does not care about case, since the model's will not either. */
    public static java.util.Map<String, BigDecimal> names() {
        return new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    }

    /**
     * Works the formula out with every name it may refer to.
     *
     * <p>Several names with one value each is as ordinary as one name with several values:
     * {@code (payment + debts) / (income / 12)} is a formula about three quantities, and asking
     * the model to fold two of them into constants before writing it down is asking it to do the
     * arithmetic this tool exists to take off it.
     *
     * @param expression the formula, e.g. {@code (c * 9 / 5) + 32}
     * @param values     what each name stands for; may be empty
     */
    public static BigDecimal evaluate(String expression, java.util.Map<String, BigDecimal> values) {
        if (expression == null || expression.isBlank()) {
            throw new BadFormula("The formula is empty.");
        }
        if (expression.length() > MAX_LENGTH) {
            throw new BadFormula("That formula is too long.");
        }
        Formula formula = new Formula(expression, values == null ? names() : values);
        BigDecimal result = formula.expression();
        formula.skipSpace();
        if (formula.at < formula.source.length()) {
            throw new BadFormula("I couldn't read the formula from \""
                    + formula.source.charAt(formula.at) + "\" onwards. " + formula.whatIsAllowed());
        }
        return tidy(result);
    }

    private String whatIsAllowed() {
        return "Use only numbers, "
                + (values.isEmpty() ? "" : "the names " + String.join(", ", values.keySet()) + ", ")
                + "+ - * / ( ) and round, abs, min, max.";
    }

    /** Trailing zeros are noise in a threshold: 68.00 is 68, and 0.150 is 0.15. */
    private static BigDecimal tidy(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0, RoundingMode.HALF_UP) : stripped;
    }

    private BigDecimal expression() {
        BigDecimal left = term();
        for (;;) {
            skipSpace();
            if (eat('+')) {
                left = left.add(term(), PRECISION);
            } else if (eat('-')) {
                left = left.subtract(term(), PRECISION);
            } else {
                return left;
            }
        }
    }

    private BigDecimal term() {
        BigDecimal left = factor();
        for (;;) {
            skipSpace();
            if (eat('*')) {
                left = left.multiply(factor(), PRECISION);
            } else if (eat('/')) {
                BigDecimal divisor = factor();
                if (divisor.signum() == 0) {
                    throw new BadFormula("That formula divides by zero.");
                }
                left = left.divide(divisor, PRECISION);
            } else {
                return left;
            }
        }
    }

    private BigDecimal factor() {
        skipSpace();
        if (eat('-')) {
            return factor().negate();
        }
        if (eat('+')) {
            return factor();
        }
        if (eat('(')) {
            BigDecimal inside = expression();
            skipSpace();
            if (!eat(')')) {
                throw new BadFormula("That formula is missing a closing bracket.");
            }
            return inside;
        }

        int start = at;
        while (at < source.length() && (Character.isDigit(source.charAt(at)) || source.charAt(at) == '.')) {
            at++;
        }
        if (at > start) {
            try {
                return new BigDecimal(source.substring(start, at));
            } catch (NumberFormatException e) {
                throw new BadFormula("\"" + source.substring(start, at) + "\" isn't a number.");
            }
        }

        while (at < source.length() && Character.isLetter(source.charAt(at))) {
            at++;
        }
        if (at == start) {
            throw new BadFormula("I couldn't read the formula. " + whatIsAllowed());
        }
        String name = source.substring(start, at);

        BigDecimal bound = values.get(name);
        if (bound != null) {
            return bound;
        }
        // A name followed by "(" is being called, so report it as an unknown function rather
        // than an unset value — otherwise "sqrt(9)" complains that sqrt has no value.
        int lookahead = at;
        while (lookahead < source.length() && Character.isWhitespace(source.charAt(lookahead))) {
            lookahead++;
        }
        if (lookahead < source.length() && source.charAt(lookahead) == '(') {
            return function(name);
        }
        throw new BadFormula("The formula uses \"" + name + "\", but no value was given for it."
                + (values.isEmpty() ? "" : " Values were given for: "
                        + String.join(", ", values.keySet()) + "."));
    }

    /** round(a), round(a, places), abs(a), min(a, b), max(a, b). */
    private BigDecimal function(String name) {
        skipSpace();
        if (!eat('(')) {
            throw new BadFormula("I don't know what \"" + name + "\" means in a formula. "
                    + whatIsAllowed());
        }
        List<BigDecimal> args = new java.util.ArrayList<>();
        skipSpace();
        if (!eat(')')) {
            do {
                args.add(expression());
                skipSpace();
            } while (eat(','));
            if (!eat(')')) {
                throw new BadFormula("That formula is missing a closing bracket.");
            }
        }

        return switch (name.toLowerCase(java.util.Locale.ROOT)) {
            case "round" -> switch (args.size()) {
                case 1 -> args.get(0).setScale(0, RoundingMode.HALF_UP);
                case 2 -> args.get(0).setScale(args.get(1).intValue(), RoundingMode.HALF_UP);
                default -> throw new BadFormula("round takes a number, and optionally how many "
                        + "decimal places.");
            };
            case "abs" -> one(name, args).abs();
            case "min" -> two(name, args).get(0).min(two(name, args).get(1));
            case "max" -> two(name, args).get(0).max(two(name, args).get(1));
            default -> throw new BadFormula("I don't know a function called \"" + name
                    + "\". You can use round, abs, min and max.");
        };
    }

    private static BigDecimal one(String name, List<BigDecimal> args) {
        if (args.size() != 1) {
            throw new BadFormula(name + " takes one number.");
        }
        return args.get(0);
    }

    private static List<BigDecimal> two(String name, List<BigDecimal> args) {
        if (args.size() != 2) {
            throw new BadFormula(name + " takes two numbers.");
        }
        return args;
    }

    private void skipSpace() {
        while (at < source.length() && Character.isWhitespace(source.charAt(at))) {
            at++;
        }
    }

    private boolean eat(char symbol) {
        skipSpace();
        if (at < source.length() && source.charAt(at) == symbol) {
            at++;
            return true;
        }
        return false;
    }
}
