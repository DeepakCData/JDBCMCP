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

    // CData driver log this session writes to (empty when the session captures via the proxy).
    // Remembered so disconnect can reclaim it instead of leaving it in temp forever.
    private String logfilePath = "";

    // True when this session asked for set_jvm_proxy and therefore holds the process-global proxy
    // system properties; released on close so the setting does not outlive the session.
    private boolean jvmProxyApplied;

    private final long createdAt = System.currentTimeMillis();
    private volatile long lastAccessed = System.currentTimeMillis();

    private final List<InterceptedCall> callLog = new CopyOnWriteArrayList<>();

    // Wall-clock budget for the in-flight tool call; read by ProxyResultSet on every fetch.
    private volatile QueryBudget budget = QueryBudget.UNBOUNDED;

    // Per-call evidence for diagnosing a timeout: when the call started, how many rows have been
    // pulled through the ResultSet proxy, and where the capture log stood at the start so the HTTP
    // round trips belonging to this call can be counted.
    private volatile long callStartedNanos = System.nanoTime();
    private volatile long rowsFetchedInCall;
    private volatile long captureOffsetAtCallStart = -1L;

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

    public String getLogfilePath() { return logfilePath; }
    public void setLogfilePath(String logfilePath) { this.logfilePath = (logfilePath != null) ? logfilePath : ""; }

    public boolean isJvmProxyApplied() { return jvmProxyApplied; }
    public void setJvmProxyApplied(boolean applied) { this.jvmProxyApplied = applied; }

    public void touch() { this.lastAccessed = System.currentTimeMillis(); }
    public long getLastAccessed() { return lastAccessed; }
    public long getCreatedAt() { return createdAt; }

    public void beginCall() {
        callLog.clear();
        // Reset first: a budget left over from the previous call would expire this one.
        budget = QueryBudget.UNBOUNDED;
        callStartedNanos = System.nanoTime();
        rowsFetchedInCall = 0;
        captureOffsetAtCallStart = currentCaptureLength();
    }

    /** Counted by ProxyResultSet on every row that comes back, for the timeout diagnosis. */
    public void recordRowFetched() {
        rowsFetchedInCall++;
    }

    public long getRowsFetchedInCall() { return rowsFetchedInCall; }

    /**
     * Why the in-flight call ran out of budget: elapsed vs execute time, rows pulled, and the HTTP
     * round trips the capture log recorded since the call began.
     */
    public String diagnoseTimeout(String phase, int timeoutSeconds) {
        long elapsedMs = (System.nanoTime() - callStartedNanos) / 1_000_000L;
        return TimeoutDiagnosis.explain(phase, timeoutSeconds, elapsedMs, lastExecuteDurationMs(),
                rowsFetchedInCall, captureOffsetAtCallStart);
    }

    /** Duration of the most recent execute* call in this call's log, or -1 when none is recorded. */
    private long lastExecuteDurationMs() {
        for (int i = callLog.size() - 1; i >= 0; i--) {
            InterceptedCall c = callLog.get(i);
            if (c.method != null && c.method.startsWith("execute")) return c.durationMs;
        }
        return -1L;
    }

    private static long currentCaptureLength() {
        try {
            java.io.File f = new java.io.File(com.cdata.mcp.config.Config.mitmLogPath());
            return f.exists() ? f.length() : 0L;
        } catch (Exception e) {
            return -1L;
        }
    }

    /** Set by the tool layer once the call's timeout is known; null clears the bound. */
    public void setBudget(QueryBudget budget) {
        this.budget = (budget != null) ? budget : QueryBudget.UNBOUNDED;
    }

    public QueryBudget getBudget() { return budget; }

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
