package com.cdata.mcp.tools;

import com.cdata.mcp.jdbc.ConnectionSession;
import com.cdata.mcp.jdbc.SessionManager;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
                        Use table_pattern (SQL LIKE) to filter by table name, e.g. "Z%" for SAP Z-tables.""")
                .inputSchema(schema(
                        Map.of(
                                "session_id",      strProp("Session ID from connect"),
                                "table_pattern",   strProp("(Optional) SQL LIKE pattern for table name, e.g. Z% or null for all"),
                                "metadata_style",  strProp("(Optional) 'standard' (default) or 'cdata' for CData sys_* system views"),
                                "include_procs",   strProp("(Optional, cdata style only) 'true' to also return stored procedure metadata")
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

        try {
            if ("cdata".equalsIgnoreCase(style)) {
                return cdataStyle(session.getProxyConnection(), tablePattern, includeProcs);
            } else {
                return standardStyle(session.getProxyConnection(), tablePattern);
            }
        } catch (Exception e) {
            return error("Metadata retrieval failed: " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // Standard JDBC DatabaseMetaData
    // -----------------------------------------------------------------------
    private static McpSchema.CallToolResult standardStyle(Connection conn, String tablePattern) throws Exception {
        DatabaseMetaData meta = conn.getMetaData();
        List<String> tableNames = new ArrayList<>();
        try (ResultSet tables = meta.getTables(null, null, tablePattern, new String[]{"TABLE", "VIEW"})) {
            while (tables.next()) tableNames.add(tables.getString("TABLE_NAME"));
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (String table : tableNames) {
            try (ResultSet cols = meta.getColumns(null, null, table, null)) {
                while (cols.next()) {
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
        }
        return ok(result);
    }

    // -----------------------------------------------------------------------
    // CData sys_* system view style
    // -----------------------------------------------------------------------
    private static McpSchema.CallToolResult cdataStyle(Connection conn, String tablePattern, boolean includeProcs) throws Exception {
        List<Map<String, Object>> result = new ArrayList<>();

        // sys_tablecolumns: CatalogName, SchemaName, TableName, ColumnName, DataTypeName, DataType,
        //                   Length, NumericPrecision, NumericScale, IsNullable, IsKey, IsAutoIncrement
        String colSql = tablePattern != null && !tablePattern.isBlank()
                ? "SELECT * FROM sys_tablecolumns WHERE TableName LIKE ?"
                : "SELECT * FROM sys_tablecolumns ORDER BY TableName, OrdinalPosition";

        try (PreparedStatement ps = colSql.contains("?")
                ? conn.prepareStatement(colSql) : null;
             Statement st = colSql.contains("?") ? null : conn.createStatement()) {

            ResultSet rs;
            if (ps != null) {
                ps.setString(1, tablePattern);
                rs = ps.executeQuery();
            } else {
                rs = st.executeQuery(colSql);
            }

            try (ResultSet cols = rs) {
                while (cols.next()) {
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
                 ResultSet rs = st.executeQuery(procColSql)) {
                while (rs.next()) {
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

        return ok(result);
    }

    private static Object safeGet(ResultSet rs, String col) {
        try { return rs.getObject(col); } catch (Exception e) { return null; }
    }
}
