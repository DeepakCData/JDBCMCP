package com.cdata.mcp.jdbc;

import java.sql.SQLException;
import java.util.regex.Pattern;

/**
 * Enforces a session's {@code read_only} flag inside the JDBC proxy layer.
 *
 * <p>The flag used to be checked per tool, and only two of the eight tools that execute SQL did so.
 * Two paths wrote freely on a read-only session: {@code execute_query} routes anything starting with
 * {@code EXEC} through {@code Statement.execute()}, and CData procedures write; and
 * {@code execute_java} hands arbitrary snippet code a live {@link java.sql.Connection}. A guard in
 * the proxy is the only placement that covers the second case, because there is no statement for a
 * tool to inspect — the snippet builds its own.
 *
 * <p><b>This is a guard, not a sandbox.</b> It stops SQL that is not plainly a read. It cannot stop
 * code that deliberately escapes the proxy — {@code connection.getMetaData().getConnection()} and
 * {@code unwrap()} both hand back the underlying connection. Treat {@code read_only} as protection
 * against an accidental write, not as a boundary against hostile code.
 */
public final class ReadOnlyGuard {

    private ReadOnlyGuard() {}

    /** SQLState 25006 — "read only SQL transaction", the standard state for this refusal. */
    private static final String READ_ONLY_STATE = "25006";

    /**
     * Statements that only read. Leading comments and whitespace are skipped so a commented query
     * is not mistaken for something else. EXPLAIN/DESCRIBE/SHOW are reads on every engine here;
     * EXEC/CALL are not, because a stored procedure's body is opaque.
     */
    private static final Pattern READ_SHAPED = Pattern.compile(
            "(?is)^\\s*(?:(?:/\\*.*?\\*/|--[^\\n]*\\n)\\s*)*(?:\\(\\s*)?(SELECT|WITH|EXPLAIN|DESCRIBE|DESC|SHOW|PRAGMA)\\b.*");

    /**
     * Throws when {@code sql} would write on a read-only session.
     *
     * @param session the session the statement belongs to; a null or writable session permits everything
     * @param sql     the statement text, or null when it is not known (batch execution)
     * @param method  the JDBC method being called, used only in the message
     */
    public static void check(ConnectionSession session, String sql, String method) throws SQLException {
        if (session == null || !session.isReadOnly()) return;
        if (sql != null && READ_SHAPED.matcher(sql).matches()) return;

        String detail = (sql == null || sql.isBlank())
                ? method + " on a statement whose SQL is not a plain read"
                : method + ": " + firstWords(sql);
        throw new SQLException(
                "Session is read-only; refused " + detail
                        + ". Only SELECT/WITH/EXPLAIN/DESCRIBE/SHOW are permitted. EXEC and CALL are treated"
                        + " as writes because a procedure body is opaque. Reconnect with read_only=false to"
                        + " allow writes.",
                READ_ONLY_STATE);
    }

    /** Refuse a positioned ResultSet update on a read-only session. */
    public static void checkResultSetWrite(ConnectionSession session, String method) throws SQLException {
        if (session == null || !session.isReadOnly()) return;
        throw new SQLException(
                "Session is read-only; refused ResultSet." + method
                        + ". Reconnect with read_only=false to allow writes.",
                READ_ONLY_STATE);
    }

    /** True when the method name is a positioned ResultSet mutation. */
    public static boolean isResultSetWrite(String method) {
        return method.equals("insertRow") || method.equals("updateRow") || method.equals("deleteRow")
                || method.startsWith("update");   // updateInt, updateString, …
    }

    /** First few words of a statement, for an error message that does not echo a whole query. */
    private static String firstWords(String sql) {
        String flat = sql.trim().replaceAll("\\s+", " ");
        return flat.length() <= 60 ? flat : flat.substring(0, 60) + "…";
    }
}
