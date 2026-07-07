package com.cdata.mcp.jdbc;

import com.cdata.mcp.config.Config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class SessionManager {

    private static final ConcurrentHashMap<String, ConnectionSession> sessions = new ConcurrentHashMap<>();

    // Daemon janitor that closes and removes idle sessions so forgotten
    // connections don't leak for the life of the process.
    private static final ScheduledExecutorService janitor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "jdbc-mcp-session-janitor");
                t.setDaemon(true);
                return t;
            });

    static {
        janitor.scheduleWithFixedDelay(SessionManager::evictIdle, 60, 60, TimeUnit.SECONDS);
    }

    public static void put(String id, ConnectionSession session) {
        sessions.put(id, session);
    }

    public static ConnectionSession get(String id) {
        ConnectionSession s = sessions.get(id);
        if (s != null) s.touch();
        return s;
    }

    public static ConnectionSession remove(String id) {
        return sessions.remove(id);
    }

    public static int size() {
        return sessions.size();
    }

    /** Snapshot of live sessions for the list_sessions tool. */
    public static List<Map<String, Object>> list() {
        long now = System.currentTimeMillis();
        List<Map<String, Object>> out = new ArrayList<>();
        for (ConnectionSession s : sessions.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("session_id", s.sessionId);
            m.put("read_only", s.isReadOnly());
            m.put("idle_seconds", (now - s.getLastAccessed()) / 1000);
            m.put("age_seconds", (now - s.getCreatedAt()) / 1000);
            m.put("total_queries", s.getTotalQueriesRun());
            m.put("total_rows_returned", s.getTotalRowsReturned());
            m.put("checks_recorded", s.getChecks().size());
            out.add(m);
        }
        return out;
    }

    static void evictIdle() {
        long idleMs = (long) Config.sessionIdleMinutes() * 60_000L;
        if (idleMs <= 0) return; // 0 disables idle eviction
        long now = System.currentTimeMillis();
        for (Map.Entry<String, ConnectionSession> e : sessions.entrySet()) {
            ConnectionSession s = e.getValue();
            if (now - s.getLastAccessed() > idleMs) {
                sessions.remove(e.getKey());
                try {
                    if (s.getProxyConnection() != null) s.getProxyConnection().close();
                } catch (Exception ignored) {
                    // best-effort close on eviction
                }
            }
        }
    }
}
