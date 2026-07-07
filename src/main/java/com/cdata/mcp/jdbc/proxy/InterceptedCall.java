package com.cdata.mcp.jdbc.proxy;

import java.util.LinkedHashMap;
import java.util.Map;

public class InterceptedCall {

    public final String method;
    public final String sql;
    public final Map<Integer, Object> params;
    public final long durationMs;
    // Mutable: for SELECTs the row count is unknown at execute time and is
    // patched in by the tool layer after the result set has been iterated.
    public int rowCount;
    public final String error;

    public InterceptedCall(String method, String sql, Map<Integer, Object> params,
                           long durationMs, int rowCount, String error) {
        this.method = method;
        this.sql = sql;
        this.params = params;
        this.durationMs = durationMs;
        this.rowCount = rowCount;
        this.error = error;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("method", method);
        if (sql != null) m.put("sql", sql);
        if (params != null && !params.isEmpty()) m.put("params", params);
        m.put("duration_ms", durationMs);
        if (rowCount >= 0) m.put("row_count", rowCount);
        if (error != null) m.put("error", error);
        return m;
    }
}
