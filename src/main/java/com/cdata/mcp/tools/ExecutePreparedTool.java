package com.cdata.mcp.tools;

import com.cdata.mcp.config.Config;
import com.cdata.mcp.jdbc.ConnectionSession;
import com.cdata.mcp.jdbc.ResultSetSerializer;
import com.cdata.mcp.jdbc.SessionManager;
import com.cdata.mcp.jdbc.TokenEstimator;
import com.cdata.mcp.jdbc.proxy.InterceptedCall;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.cdata.mcp.tools.JsonUtil.*;

public class ExecutePreparedTool {

    public static McpSchema.Tool tool() {
        return McpSchema.Tool.builder()
                .name("execute_prepared")
                .description("""
                        Execute a parameterized JDBC query using PreparedStatement with proper type binding.
                        Use for:
                          • Parameterized SELECTs where ? placeholders are bound to typed values
                          • Avoiding SQL injection risk when value content is externally sourced
                          • Testing driver PreparedStatement binding (captures bound params in intercepted_calls)
                        params is a JSON array of values. Each entry may be a scalar (string, number, boolean)
                        or an object with {"type": "int|long|double|float|boolean|string|null", "value": ...}
                        to force a specific JDBC setter. If scalar, type is inferred from JSON type.
                        Write statements (non-SELECT) are rejected on read_only sessions. SELECTs are capped by max_rows.""")
                .inputSchema(schema(
                        Map.of(
                                "session_id", strProp("Session ID from connect"),
                                "sql",        strProp("SQL with ? placeholders, e.g. SELECT * FROM Orders WHERE CustomerId = ?"),
                                "params",     Map.of(
                                        "type",        "array",
                                        "description", "Ordered list of parameter values to bind to ? placeholders",
                                        "items",       Map.of("type", "object")
                                ),
                                "max_rows",        intProp("(Optional, SELECT only) Maximum rows to return. Defaults to server config."),
                                "timeout_seconds", intProp("(Optional) Statement timeout in seconds. Defaults to server config.")
                        ),
                        List.of("session_id", "sql")
                ))
                .build();
    }

    public static McpSchema.CallToolResult handle(McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        Map<String, Object> args = request.arguments();
        String sessionId = (String) args.get("session_id");
        String sql       = (String) args.get("sql");
        Object paramsRaw = args.get("params");

        if (sessionId == null) return error("session_id is required");
        if (sql == null || sql.isBlank()) return error("sql is required");

        ConnectionSession session = SessionManager.get(sessionId);
        if (session == null) return error("Session not found: " + sessionId);

        String trimmedUpper = sql.trim().toUpperCase();
        boolean isQuery = trimmedUpper.startsWith("SELECT") || trimmedUpper.startsWith("WITH");
        if (!isQuery && session.isReadOnly()) {
            return error("Session is read-only; only SELECT/WITH statements are allowed. Reconnect with read_only=false to allow writes.");
        }

        List<?> params = paramsRaw instanceof List<?> list ? list : List.of();

        Integer maxRowsArg = asInt(args.get("max_rows"));
        int maxRows = (maxRowsArg != null && maxRowsArg > 0) ? maxRowsArg : Config.defaultMaxRows();
        Integer timeoutArg = asInt(args.get("timeout_seconds"));
        int timeout = (timeoutArg != null && timeoutArg >= 0) ? timeoutArg : Config.queryTimeoutSeconds();

        session.beginCall();
        try (PreparedStatement ps = session.getProxyConnection().prepareStatement(sql)) {
            try { ps.setQueryTimeout(timeout); } catch (Exception ignored) {}
            if (isQuery) {
                try { ps.setMaxRows(maxRows + 1); } catch (Exception ignored) {}
            }
            bindParams(ps, params);

            Map<String, Object> response = new LinkedHashMap<>();
            if (isQuery) {
                ResultSetSerializer.SerializedResult ser;
                try (ResultSet rs = ps.executeQuery()) {
                    ser = ResultSetSerializer.serialize(rs, maxRows);
                }
                session.setLastCallRowCount(ser.rows().size());
                List<InterceptedCall> calls = session.endCall(ser.rows().size(), 0);

                response.put("rows",              ser.rows());
                response.put("row_count",         ser.rows().size());
                response.put("truncated",         ser.truncated());
                response.put("columns",           ser.columns());
                response.put("intercepted_calls", calls.stream().map(InterceptedCall::toMap).collect(Collectors.toList()));

                long tokens = TokenEstimator.estimate(toJson(response));
                session.addEstimatedTokens(tokens);
                response.put("_meta", Map.of(
                        "estimated_tokens",     tokens,
                        "session_total_tokens", session.getTotalEstimatedTokens(),
                        "row_cap",              maxRows));
            } else {
                int updateCount = ps.executeUpdate();
                List<InterceptedCall> calls = session.endCall(0, 0);

                response.put("update_count",      updateCount);
                response.put("intercepted_calls", calls.stream().map(InterceptedCall::toMap).collect(Collectors.toList()));

                long tokens = TokenEstimator.estimate(toJson(response));
                session.addEstimatedTokens(tokens);
                response.put("_meta", Map.of(
                        "estimated_tokens",     tokens,
                        "session_total_tokens", session.getTotalEstimatedTokens()));
            }
            return ok(response);
        } catch (Exception e) {
            return error("execute_prepared failed: " + e.getMessage());
        }
    }

    /** Bind each entry in params to the PreparedStatement using appropriate typed setter. */
    private static void bindParams(PreparedStatement ps, List<?> params) throws Exception {
        for (int i = 0; i < params.size(); i++) {
            int idx = i + 1;
            Object p = params.get(i);

            if (p == null) {
                ps.setNull(idx, java.sql.Types.VARCHAR);
                continue;
            }

            // Typed object: {"type": "int", "value": 42}
            if (p instanceof Map<?, ?> m) {
                @SuppressWarnings("unchecked") Map<String, Object> typed = (Map<String, Object>) m;
                String type  = String.valueOf(typed.getOrDefault("type", "string"));
                Object value = typed.get("value");
                if (value == null) { ps.setNull(idx, java.sql.Types.VARCHAR); continue; }
                switch (type.toLowerCase()) {
                    case "int", "integer" -> ps.setInt(idx, toInt(value));
                    case "long"           -> ps.setLong(idx, toLong(value));
                    case "double"         -> ps.setDouble(idx, toDouble(value));
                    case "float"          -> ps.setFloat(idx, (float) toDouble(value));
                    case "boolean"        -> ps.setBoolean(idx, Boolean.parseBoolean(String.valueOf(value)));
                    case "null"           -> ps.setNull(idx, java.sql.Types.VARCHAR);
                    default               -> ps.setString(idx, String.valueOf(value));
                }
                continue;
            }

            // Scalar — infer from JSON type
            if (p instanceof Boolean b)  { ps.setBoolean(idx, b); continue; }
            if (p instanceof Integer n)  { ps.setInt(idx, n); continue; }
            if (p instanceof Long n)     { ps.setLong(idx, n); continue; }
            if (p instanceof Double n)   { ps.setDouble(idx, n); continue; }
            if (p instanceof Number n)   { ps.setDouble(idx, n.doubleValue()); continue; }
            ps.setString(idx, String.valueOf(p));
        }
    }

    private static int    toInt(Object v)    { return v instanceof Number n ? n.intValue()    : Integer.parseInt(String.valueOf(v)); }
    private static long   toLong(Object v)   { return v instanceof Number n ? n.longValue()   : Long.parseLong(String.valueOf(v)); }
    private static double toDouble(Object v) { return v instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(v)); }
}
