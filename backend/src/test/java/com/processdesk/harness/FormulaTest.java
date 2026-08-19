package com.processdesk.harness;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The arithmetic the model is told not to do in its head.
 *
 * <p>Worth its own tests because it is the one place a wrong answer is invisible: {@code expect}
 * checks the value being replaced and the gates check the shape of the result, so a number that
 * is merely incorrect passes everything.
 */
class FormulaTest {

    private static String eval(String formula, String variable, String value) {
        return Formula.evaluate(formula, variable,
                value == null ? null : new BigDecimal(value)).toPlainString();
    }

    @Test
    @DisplayName("the conversion this was built for")
    void celsiusToFahrenheit() {
        assertEquals("68", eval("(c * 9 / 5) + 32", "c", "20"));
        assertEquals("64.4", eval("(c * 9 / 5) + 32", "c", "18"));
        assertEquals("32", eval("(c * 9 / 5) + 32", "c", "0"));
        assertEquals("-40", eval("(c * 9 / 5) + 32", "c", "-40"));
    }

    @Test
    @DisplayName("decimals stay decimal, because a threshold like 0.30000000000000004 is unusable")
    void noBinaryFloatingPointNoise() {
        assertEquals("0.3", eval("0.1 + 0.2", "", null));
        assertEquals("0.36", eval("x * 1.2", "x", "0.3"));
        // The DTI thresholds from the lending model, shifted by a tenth.
        assertEquals("0.396", eval("x * 1.1", "x", "0.36"));
    }

    @Test
    @DisplayName("an expression with no variable is just worked out")
    void constantExpressions() {
        assertEquals("110", eval("(100 + 10)", "", null));
        assertEquals("2.5", eval("10 / 4", "", null));
        assertEquals("-7", eval("3 - 10", "", null));
    }

    @Test
    @DisplayName("precedence and brackets")
    void precedence() {
        assertEquals("14", eval("2 + 3 * 4", "", null));
        assertEquals("20", eval("(2 + 3) * 4", "", null));
        assertEquals("-6", eval("-2 * 3", "", null));
        assertEquals("8", eval("2 * -3 + 14", "", null));
    }

    @Test
    @DisplayName("rounding, for thresholds that have to stay tidy")
    void functions() {
        assertEquals("64", eval("round((c * 9 / 5) + 32)", "c", "18"));
        assertEquals("64.4", eval("round((c * 9 / 5) + 32, 1)", "c", "18"));
        assertEquals("5", eval("abs(0 - 5)", "", null));
        assertEquals("3", eval("min(3, 9)", "", null));
        assertEquals("9", eval("max(3, 9)", "", null));
    }

    @Test
    @DisplayName("trailing zeros are stripped, so 68.00 reads as 68")
    void tidyOutput() {
        assertEquals("68", eval("68.00", "", null));
        assertEquals("0.15", eval("0.150", "", null));
        assertEquals("100", eval("50 * 2", "", null));
    }

    @Test
    @DisplayName("nothing but arithmetic is reachable")
    void nothingElseEvaluates() {
        // Not a sandbox with holes in it — there is no name resolution at all, so these are
        // simply unreadable rather than dangerous.
        for (String hostile : new String[]{
                "System.exit(0)", "java.lang.Runtime.getRuntime()", "1; drop table rules",
                "process.exit()", "__import__('os')", "${jndi:ldap://x}"}) {
            assertThrows(Formula.BadFormula.class, () -> eval(hostile, "", null), hostile);
        }
    }

    @Test
    @DisplayName("a bad formula explains itself, so the model can try again")
    void badFormulasAreRecoverable() {
        assertTrue(assertThrows(Formula.BadFormula.class,
                () -> eval("(2 + 3", "", null)).getMessage().contains("closing bracket"));
        assertTrue(assertThrows(Formula.BadFormula.class,
                () -> eval("10 / 0", "", null)).getMessage().contains("divides by zero"));
        assertTrue(assertThrows(Formula.BadFormula.class,
                () -> eval("sqrt(9)", "", null)).getMessage().contains("round, abs, min and max"));
        assertTrue(assertThrows(Formula.BadFormula.class,
                () -> eval("", "", null)).getMessage().contains("empty"));
    }

    @Test
    @DisplayName("a variable with no value given says so rather than guessing one")
    void missingVariable() {
        assertTrue(assertThrows(Formula.BadFormula.class,
                () -> eval("c * 2", "c", null)).getMessage().contains("no value was given"));
    }

    @Test
    @DisplayName("several names, one value each")
    void severalNames() {
        java.util.Map<String, BigDecimal> values = Formula.names();
        values.put("payment", new BigDecimal("1200"));
        values.put("debts", new BigDecimal("400"));
        values.put("income", new BigDecimal("96000"));

        assertEquals("0.2",
                Formula.evaluate("(payment + debts) / (income / 12)", values).toPlainString());
    }

    @Test
    @DisplayName("names are matched however they are cased")
    void namesIgnoreCase() {
        java.util.Map<String, BigDecimal> values = Formula.names();
        values.put("Rate", new BigDecimal("1.5"));

        assertEquals("15", Formula.evaluate("rate * 10", values).toPlainString());
        assertEquals("15", Formula.evaluate("RATE * 10", values).toPlainString());
    }

    @Test
    @DisplayName("a name with no value names the ones that do have values")
    void unboundNameIsExplained() {
        java.util.Map<String, BigDecimal> values = Formula.names();
        values.put("payment", new BigDecimal("1200"));

        String message = assertThrows(Formula.BadFormula.class,
                () -> Formula.evaluate("payment + debts", values)).getMessage();
        assertTrue(message.contains("\"debts\""), message);
        assertTrue(message.contains("payment"), message);
    }

    @Test
    @DisplayName("an unknown function is still reported as a function, not as a missing value")
    void functionsAreNotMistakenForNames() {
        // "sqrt(9)" has to complain about sqrt not existing, not about sqrt having no value.
        assertTrue(assertThrows(Formula.BadFormula.class,
                () -> eval("sqrt(9)", "", null)).getMessage().contains("round, abs, min and max"));
    }

    @Test
    @DisplayName("an unreasonably long formula is refused rather than worked through")
    void boundedWork() {
        assertThrows(Formula.BadFormula.class, () -> eval("1 + ".repeat(200) + "1", "", null));
    }
}
