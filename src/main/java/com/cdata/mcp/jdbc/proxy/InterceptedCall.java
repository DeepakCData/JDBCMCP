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
    /** Number of statements/parameter sets in a batch; -1 for a non-batch call. */
    public final int batchSize;

    public InterceptedCall(String method, String sql, Map<Integer, Object> params,
                           long durationMs, int rowCount, String error) {
        this(method, sql, params, durationMs, rowCount, error, -1);
    }

    private InterceptedCall(String method, String sql, Map<Integer, Object> params,
                            long durationMs, int rowCount, String error, int batchSize) {
        this.method = method;
        this.sql = sql;
        this.params = params;
        this.durationMs = durationMs;
        this.rowCount = rowCount;
        this.error = error;
        this.batchSize = batchSize;
    }

    /** A batch execution, carrying how many statements/parameter sets it covered. */
    public static InterceptedCall batch(String method, String sql, Map<Integer, Object> params,
                                        long durationMs, int batchSize, int rowCount, String error) {
        return new InterceptedCall(method, sql, params, durationMs, rowCount, error, batchSize);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("method", method);
        if (sql != null) m.put("sql", sql);
        if (params != null && !params.isEmpty()) m.put("params", params);
        m.put("duration_ms", durationMs);
        if (batchSize >= 0) m.put("batch_size", batchSize);
        if (rowCount >= 0) m.put("row_count", rowCount);
        if (error != null) m.put("error", error);
        return m;
    }
}
