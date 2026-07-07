package com.cdata.mcp.jdbc;

import com.cdata.mcp.jdbc.proxy.InterceptedCall;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

public class ConnectionSession {

    public final String sessionId;
    private Connection proxyConnection;
    private boolean readOnly;

    private final long createdAt = System.currentTimeMillis();
    private volatile long lastAccessed = System.currentTimeMillis();

    private final List<InterceptedCall> callLog = new CopyOnWriteArrayList<>();

    // QA test-report accumulator — assertions/comparisons record results here.
    private final List<Map<String, Object>> checks = new CopyOnWriteArrayList<>();

    // cumulative stats
    private volatile int totalQueriesRun;
    private volatile int totalRowsReturned;
    private volatile int totalInterceptedCalls;
    private volatile long totalDurationMs;
    private volatile long totalEstimatedTokens;

    public ConnectionSession(String sessionId) {
        this.sessionId = sessionId;
    }

    public void setProxyConnection(Connection conn) {
        this.proxyConnection = conn;
    }

    public Connection getProxyConnection() {
        return proxyConnection;
    }

    public boolean isReadOnly() { return readOnly; }
    public void setReadOnly(boolean readOnly) { this.readOnly = readOnly; }

    public void touch() { this.lastAccessed = System.currentTimeMillis(); }
    public long getLastAccessed() { return lastAccessed; }
    public long getCreatedAt() { return createdAt; }

    public void beginCall() {
        callLog.clear();
    }

    public void addCall(InterceptedCall call) {
        callLog.add(call);
    }

    /** Patch the row count onto the most recent intercepted call (SELECT counts known post-iteration). */
    public void setLastCallRowCount(int rows) {
        if (!callLog.isEmpty()) callLog.get(callLog.size() - 1).rowCount = rows;
    }

    public List<InterceptedCall> endCall(int rowsReturned, long estimatedTokens) {
        List<InterceptedCall> snapshot = new ArrayList<>(callLog);
        totalQueriesRun++;
        totalRowsReturned += rowsReturned;
        totalInterceptedCalls += snapshot.size();
        for (InterceptedCall c : snapshot) totalDurationMs += c.durationMs;
        totalEstimatedTokens += estimatedTokens;
        return snapshot;
    }

    /** Add to the cumulative token total (response size is only known after building the response). */
    public void addEstimatedTokens(long tokens) { totalEstimatedTokens += tokens; }

    public void addCheck(Map<String, Object> check) { checks.add(check); }
    public List<Map<String, Object>> getChecks() { return new ArrayList<>(checks); }

    public int getTotalQueriesRun()        { return totalQueriesRun; }
    public int getTotalRowsReturned()      { return totalRowsReturned; }
    public int getTotalInterceptedCalls()  { return totalInterceptedCalls; }
    public long getTotalDurationMs()       { return totalDurationMs; }
    public long getTotalEstimatedTokens()  { return totalEstimatedTokens; }
}
