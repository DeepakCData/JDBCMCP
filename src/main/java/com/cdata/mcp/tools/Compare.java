package com.cdata.mcp.tools;

import java.math.BigDecimal;

/** Value comparison helpers shared by assert_query and compare_queries. */
final class Compare {

    private Compare() {}

    /**
     * Compare actual vs expected using a comparator: eq, ne, gt, gte, lt, lte.
     * Numeric values are compared numerically; everything else by string equality/ordering.
     */
    static boolean evaluate(Object actual, Object expected, String comparator) {
        String op = comparator == null || comparator.isBlank() ? "eq" : comparator.trim().toLowerCase();

        BigDecimal na = asNumber(actual);
        BigDecimal ne = asNumber(expected);
        int cmp;
        boolean numeric = na != null && ne != null;
        if (numeric) {
            cmp = na.compareTo(ne);
        } else {
            cmp = String.valueOf(actual).compareTo(String.valueOf(expected));
        }

        return switch (op) {
            case "eq", "==", "="   -> numeric ? cmp == 0 : equalsLoose(actual, expected);
            case "ne", "!=", "<>"  -> numeric ? cmp != 0 : !equalsLoose(actual, expected);
            case "gt", ">"         -> cmp > 0;
            case "gte", ">=", "ge" -> cmp >= 0;
            case "lt", "<"         -> cmp < 0;
            case "lte", "<=", "le" -> cmp <= 0;
            default                -> false;
        };
    }

    private static boolean equalsLoose(Object a, Object b) {
        if (a == null || b == null) return a == b;
        return String.valueOf(a).equals(String.valueOf(b));
    }

    private static BigDecimal asNumber(Object o) {
        if (o == null) return null;
        if (o instanceof BigDecimal bd) return bd;
        if (o instanceof Number n) return new BigDecimal(n.toString());
        try { return new BigDecimal(o.toString().trim()); } catch (Exception e) { return null; }
    }
}
