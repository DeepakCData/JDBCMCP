package com.cdata.mcp.tools;

import com.cdata.mcp.jdbc.ConnectionSession;
import com.cdata.mcp.jdbc.SessionManager;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;

import com.cdata.mcp.config.Config;
import com.cdata.mcp.jdbc.QueryBudget;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.cdata.mcp.tools.JsonUtil.*;

public class GetMetadataTool {

    public static McpSchema.Tool tool() {
        return McpSchema.Tool.builder()
                .name("get_metadata")
                .description("""
                        Retrieve table and column metadata from the database.
                        metadata_style controls how metadata is fetched:
                          • "standard"  — uses JDBC DatabaseMetaData.getTables / getColumns (works for all drivers)
                          • "cdata"     — queries CData sys_tables / sys_tablecolumns / sys_procedures /
                                          sys_procedureparameters system views (CData drivers only, more detail)
                        Defaults to "standard" if omitted.
                        Use table_pattern (SQL LIKE) to filter by table name, e.g. "Z%" for SAP Z-tables.
                        Returns {metadata, count, truncated, table_count}. Capped by max_rows: without a
                        table_pattern a wide connector returns every column of every table, so filter
                        rather than fetching everything.""")
                .inputSchema(schema(
                        Map.of(
                                "session_id",      strProp("Session ID from connect"),
                                "table_pattern",   strProp("(Optional) SQL LIKE pattern for table name, e.g. Z% or null for all"),
                                "metadata_style",  strProp("(Optional) 'standard' (default) or 'cdata' for CData sys_* system views"),
                                "include_procs",   strProp("(Optional, cdata style only) 'true' to also return stored procedure metadata"),
                                "max_rows",        intProp("(Optional) Maximum metadata entries to return. Defaults to server config."),
                                "timeout_seconds", intProp("(Optional, cdata style only) Query timeout in seconds. Defaults to server config.")
                        ),
                        List.of("session_id")
                ))
                .build();
    }

    public static McpSchema.CallToolResult handle(McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        Map<String, Object> args = request.arguments();
        String sessionId     = (String) args.get("session_id");
        String tablePattern  = (String) args.get("table_pattern");
        String style         = (String) args.get("metadata_style");
        boolean includeProcs = "true".equalsIgnoreCase((String) args.get("include_procs"));

        if (sessionId == null) return error("session_id is required");
        ConnectionSession session = SessionManager.get(sessionId);
        if (session == null) return error("Session not found: " + sessionId);

        Integer maxRowsArg = asInt(args.get("max_rows"));
        int cap = (maxRowsArg != null && maxRowsArg > 0) ? maxRowsArg : Config.defaultMaxRows();
        int timeout = ExecuteQueryTool.resolveTimeout(args);

        session.beginCall();
        try {
            if ("cdata".equalsIgnoreCase(style)) {
                return cdataStyle(session, tablePattern, includeProcs, cap, timeout);
            } else {
                // DatabaseMetaData exposes no timeout knob, so the cap is the only bound here.
                return standardStyle(session.getProxyConnection(), tablePattern, cap);
            }
        } catch (Exception e) {
            return errorWithTrace("Metadata retrieval failed: " + describe(e), session);
        }
    }

    // -----------------------------------------------------------------------
    // Standard JDBC DatabaseMetaData
    // -----------------------------------------------------------------------
    private static McpSchema.CallToolResult standardStyle(Connection conn, String tablePattern, int cap) throws Exception {
        DatabaseMetaData meta = conn.getMetaData();

        // getTables still runs, to keep the TABLE/VIEW filter that getColumns cannot express.
        Set<String> wanted = new LinkedHashSet<>();
        try (ResultSet tables = meta.getTables(null, null, tablePattern, new String[]{"TABLE", "VIEW"})) {
            while (tables.next()) wanted.add(tables.getString("TABLE_NAME"));
        }

        // One getColumns call for the whole pattern. This was one call per table — an N+1 costing
        // hundreds of round trips on a wide connector, the very pattern the QA checklists say to watch.
        List<Map<String, Object>> result = new ArrayList<>();
        boolean truncated = false;
        try (ResultSet cols = meta.getColumns(null, null, tablePattern, null)) {
            while (cols.next()) {
                String table = cols.getString("TABLE_NAME");
                if (!wanted.contains(table)) continue;   // drop non-TABLE/VIEW objects getColumns returns
                if (result.size() >= cap) { truncated = true; break; }
                Map<String, Object> col = new LinkedHashMap<>();
                col.put("table",     table);
                col.put("column",    cols.getString("COLUMN_NAME"));
                col.put("type_name", cols.getString("TYPE_NAME"));
                col.put("data_type", cols.getInt("DATA_TYPE"));
                col.put("size",      cols.getInt("COLUMN_SIZE"));
                col.put("nullable",  cols.getInt("NULLABLE") == DatabaseMetaData.columnNullable);
                result.add(col);
            }
        }
        return metadataResponse(result, truncated, cap, wanted.size());
    }

    /**
     * Wrap metadata rows with a count and a truncation flag.
     *
     * <p>This used to return a bare JSON array with no cap: called without a table_pattern against a
     * connector with hundreds of tables it returned every column of every table, exhausting the
     * caller's context before any test could run.
     */
    private static McpSchema.CallToolResult metadataResponse(List<Map<String, Object>> rows,
                                                            boolean truncated, int cap, int tableCount) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("metadata", rows);
        response.put("count", rows.size());
        response.put("truncated", truncated);
        if (tableCount >= 0) response.put("table_count", tableCount);
        if (truncated) {
            response.put("warning", "Metadata truncated at " + cap
                    + " entries. Narrow with table_pattern, or raise max_rows.");
        }
        return ok(response);
    }

    // -----------------------------------------------------------------------
    // CData sys_* system view style
    // -----------------------------------------------------------------------
    private static McpSchema.CallToolResult cdataStyle(ConnectionSession session, String tablePattern,
                                                       boolean includeProcs, int cap, int timeout) throws Exception {
        Connection conn = session.getProxyConnection();
        List<Map<String, Object>> result = new ArrayList<>();
        boolean truncated = false;

        // sys_tablecolumns: CatalogName, SchemaName, TableName, ColumnName, DataTypeName, DataType,
        //                   Length, NumericPrecision, NumericScale, IsNullable, IsKey, IsAutoIncrement
        String colSql = tablePattern != null && !tablePattern.isBlank()
                ? "SELECT * FROM sys_tablecolumns WHERE TableName LIKE ?"
                : "SELECT * FROM sys_tablecolumns ORDER BY TableName, OrdinalPosition";

        try (PreparedStatement ps = colSql.contains("?")
                ? conn.prepareStatement(colSql) : null;
             Statement st = colSql.contains("?") ? null : conn.createStatement();
             QueryBudget.Disarm d = ExecuteQueryTool.applyLimits(session, ps != null ? ps : st, timeout, cap)) {

            ResultSet rs;
            if (ps != null) {
                ps.setString(1, tablePattern);
                rs = ps.executeQuery();
            } else {
                rs = st.executeQuery(colSql);
            }

            try (ResultSet cols = rs) {
                while (cols.next()) {
                    if (result.size() >= cap) { truncated = true; break; }
                    Map<String, Object> col = new LinkedHashMap<>();
                    col.put("table",           safeGet(cols, "TableName"));
                    col.put("column",          safeGet(cols, "ColumnName"));
                    col.put("type_name",       safeGet(cols, "DataTypeName"));
                    col.put("data_type",       safeGet(cols, "DataType"));
                    col.put("length",          safeGet(cols, "Length"));
                    col.put("precision",       safeGet(cols, "NumericPrecision"));
                    col.put("scale",           safeGet(cols, "NumericScale"));
                    col.put("nullable",        safeGet(cols, "IsNullable"));
                    col.put("is_key",          safeGet(cols, "IsKey"));
                    col.put("is_auto_increment", safeGet(cols, "IsAutoIncrement"));
                    col.put("_kind",           "column");
                    result.add(col);
                }
            }
        }

        if (includeProcs) {
            // sys_procedureparameters: CatalogName, SchemaName, ProcedureName, ColumnName, Direction,
            //                          DataTypeName, DataType, MaxLength, IsRequired
            String procColSql = "SELECT * FROM sys_procedureparameters ORDER BY ProcedureName, OrdinalPosition";
            try (Statement st = conn.createStatement();
                 QueryBudget.Disarm d = ExecuteQueryTool.applyLimits(session, st, timeout, cap);
                 ResultSet rs = st.executeQuery(procColSql)) {
                while (rs.next()) {
                    if (result.size() >= cap) { truncated = true; break; }
                    Map<String, Object> p = new LinkedHashMap<>();
                    p.put("procedure",   safeGet(rs, "ProcedureName"));
                    p.put("param",       safeGet(rs, "ColumnName"));
                    p.put("direction",   safeGet(rs, "Direction"));
                    p.put("type_name",   safeGet(rs, "DataTypeName"));
                    p.put("data_type",   safeGet(rs, "DataType"));
                    p.put("max_length",  safeGet(rs, "MaxLength"));
                    p.put("is_required", safeGet(rs, "IsRequired"));
                    p.put("_kind",       "proc_param");
                    result.add(p);
                }
            }
        }

        return metadataResponse(result, truncated, cap, -1);
    }

    private static Object safeGet(ResultSet rs, String col) {
        try { return rs.getObject(col); } catch (Exception e) { return null; }
    }
}
