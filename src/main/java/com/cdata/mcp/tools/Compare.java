package com.cdata.mcp.tools;

import java.math.BigDecimal;

/**
 * Value comparison helpers shared by assert_query and compare_queries.
 *
 * <p>Two rules exist to stop a comparison from inventing a verdict:
 *
 * <p><b>Null never orders.</b> {@code String.valueOf(null)} is the text {@code "null"}, so a
 * lexicographic fallback made a NULL column satisfy {@code >= 100} — {@code "null"} sorts after
 * {@code "100"}. A null on either side now fails every ordering comparator, and only participates
 * in eq/ne.
 *
 * <p><b>Numeric coercion needs a number.</b> Coercing both sides through BigDecimal made
 * {@code "007"} equal {@code "7"} and {@code "01234"} equal {@code "1234"} — silently passing
 * assertions on zero-padded ids, zip codes and order numbers. Numeric comparison is now used only
 * when at least one side is an actual number (a JSON number in {@code expected_value}, or a
 * numeric column). Two strings compare as strings, exactly.
 */
final class Compare {

    private Compare() {}

    /** How a comparison was decided, so a recorded verdict can be audited. */
    enum Mode { NUMERIC, STRING, NULL, UNSUPPORTED }

    /** Result of a comparison: the verdict plus how it was reached. */
    record Result(boolean passed, Mode mode, String note) {}

    /**
     * Compare actual vs expected using a comparator: eq, ne, gt, gte, lt, lte.
     * Equivalent to {@link #compare} but discarding the reasoning.
     */
    static boolean evaluate(Object actual, Object expected, String comparator) {
        return compare(actual, expected, comparator).passed();
    }

    /** Compare actual vs expected, reporting how the decision was made. */
    static Result compare(Object actual, Object expected, String comparator) {
        String op = normalize(comparator);
        boolean ordering = isOrdering(op);

        if (actual == null || expected == null) {
            // Ordering against an unknown value has no answer — say so instead of guessing.
            if (ordering) {
                return new Result(false, Mode.NULL,
                        "cannot order a null value; " + (actual == null ? "actual" : "expected") + " is null");
            }
            boolean bothNull = actual == null && expected == null;
            return switch (op) {
                case "eq" -> new Result(bothNull, Mode.NULL, bothNull ? "both null" : "one side is null");
                case "ne" -> new Result(!bothNull, Mode.NULL, bothNull ? "both null" : "one side is null");
                default   -> new Result(false, Mode.UNSUPPORTED, "unknown comparator: " + op);
            };
        }

        BigDecimal na = asNumber(actual);
        BigDecimal nb = asNumber(expected);
        // A number on either side makes this a numeric question; two strings stay strings so that
        // "007" and "7" are the different values they are.
        boolean numeric = na != null && nb != null && (isNumber(actual) || isNumber(expected));

        int cmp = numeric ? na.compareTo(nb) : String.valueOf(actual).compareTo(String.valueOf(expected));
        Mode mode = numeric ? Mode.NUMERIC : Mode.STRING;

        boolean passed = switch (op) {
            case "eq"  -> numeric ? cmp == 0 : String.valueOf(actual).equals(String.valueOf(expected));
            case "ne"  -> numeric ? cmp != 0 : !String.valueOf(actual).equals(String.valueOf(expected));
            case "gt"  -> cmp > 0;
            case "gte" -> cmp >= 0;
            case "lt"  -> cmp < 0;
            case "lte" -> cmp <= 0;
            default    -> false;
        };

        if (!isKnown(op)) return new Result(false, Mode.UNSUPPORTED, "unknown comparator: " + op);

        String note = numeric ? "compared numerically"
                : (ordering ? "compared as strings (lexicographic)" : "compared as strings (exact)");
        return new Result(passed, mode, note);
    }

    /** Canonical comparator name; accepts the symbolic spellings too. */
    private static String normalize(String comparator) {
        String op = (comparator == null || comparator.isBlank()) ? "eq" : comparator.trim().toLowerCase();
        return switch (op) {
            case "eq", "==", "="   -> "eq";
            case "ne", "!=", "<>"  -> "ne";
            case "gt", ">"         -> "gt";
            case "gte", ">=", "ge" -> "gte";
            case "lt", "<"         -> "lt";
            case "lte", "<=", "le" -> "lte";
            default                -> op;
        };
    }

    private static boolean isOrdering(String op) {
        return op.equals("gt") || op.equals("gte") || op.equals("lt") || op.equals("lte");
    }

    private static boolean isKnown(String op) {
        return isOrdering(op) || op.equals("eq") || op.equals("ne");
    }

    /** True for values that are genuinely numeric rather than numeric-looking text. */
    private static boolean isNumber(Object o) {
        return o instanceof Number;
    }

    private static BigDecimal asNumber(Object o) {
        if (o == null) return null;
        if (o instanceof BigDecimal bd) return bd;
        if (o instanceof Number n) return new BigDecimal(n.toString());
        try { return new BigDecimal(o.toString().trim()); } catch (Exception e) { return null; }
    }
}
