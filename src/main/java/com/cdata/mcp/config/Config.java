package com.cdata.mcp.config;

/**
 * Central, externally-tunable limits for the JDBC MCP server.
 *
 * Every value can be overridden (in precedence order) by a JVM system property
 * or an environment variable, otherwise a safe default is used. This keeps the
 * QA engineer in control without code changes — e.g. raise the row cap for a
 * data-export run, or lower the query timeout when poking at a busy prod box.
 *
 *   queryTimeoutSeconds   -Djdbc.mcp.queryTimeout        / JDBC_MCP_QUERY_TIMEOUT
 *   defaultMaxRows        -Djdbc.mcp.maxRows             / JDBC_MCP_MAX_ROWS
 *   javaTimeoutSeconds    -Djdbc.mcp.javaTimeout         / JDBC_MCP_JAVA_TIMEOUT
 *   maxSessions           -Djdbc.mcp.maxSessions         / JDBC_MCP_MAX_SESSIONS
 *   sessionIdleMinutes    -Djdbc.mcp.sessionIdleMinutes  / JDBC_MCP_SESSION_IDLE_MINUTES
 *   maxCellBytes          -Djdbc.mcp.maxCellBytes        / JDBC_MCP_MAX_CELL_BYTES
 *   readOnlyDefault       -Djdbc.mcp.readOnly            / JDBC_MCP_READ_ONLY
 *   mitmProxyPort         -Djdbc.mcp.mitmPort            / JDBC_MCP_MITM_PORT
 *   mitmLogPath           -Djdbc.mcp.mitmLogPath         / JDBC_MCP_MITM_LOG_PATH
 *   noProxyDrivers        -Djdbc.mcp.noProxyDrivers      / JDBC_MCP_NO_PROXY_DRIVERS
 *   fileDrivers           -Djdbc.mcp.fileDrivers         / JDBC_MCP_FILE_DRIVERS
 *   driverLogVerbosity    -Djdbc.mcp.logVerbosity        / JDBC_MCP_LOG_VERBOSITY
 */
public final class Config {

    private Config() {}

    /** Per-statement query timeout in seconds (0 = no driver-enforced timeout). */
    public static int queryTimeoutSeconds() { return intCfg("jdbc.mcp.queryTimeout", "JDBC_MCP_QUERY_TIMEOUT", 30); }

    /** Default maximum rows materialized from a result set when the caller does not specify. */
    public static int defaultMaxRows() { return intCfg("jdbc.mcp.maxRows", "JDBC_MCP_MAX_ROWS", 1000); }

    /** Wall-clock timeout for an execute_java snippet in seconds (0 = unbounded). */
    public static int javaTimeoutSeconds() { return intCfg("jdbc.mcp.javaTimeout", "JDBC_MCP_JAVA_TIMEOUT", 30); }

    /** Maximum number of concurrently open sessions before connect is refused. */
    public static int maxSessions() { return intCfg("jdbc.mcp.maxSessions", "JDBC_MCP_MAX_SESSIONS", 50); }

    /** Idle minutes after which a session is auto-closed and evicted (0 = never). */
    public static int sessionIdleMinutes() { return intCfg("jdbc.mcp.sessionIdleMinutes", "JDBC_MCP_SESSION_IDLE_MINUTES", 30); }

    /** Maximum bytes/chars emitted for a single binary or large-text cell before truncation. */
    public static int maxCellBytes() { return intCfg("jdbc.mcp.maxCellBytes", "JDBC_MCP_MAX_CELL_BYTES", 65536); }

    /** When true, new sessions default to read-only (writes rejected) unless connect overrides it. */
    public static boolean readOnlyDefault() { return boolCfg("jdbc.mcp.readOnly", "JDBC_MCP_READ_ONLY", false); }

    /** Port the auto-managed local mitmproxy listens on (default 8889). */
    public static int mitmProxyPort() { return intCfg("jdbc.mcp.mitmPort", "JDBC_MCP_MITM_PORT", 8889); }

    /** Path where the mitmproxy addon writes its JSONL request log. */
    public static String mitmLogPath() {
        String def = System.getProperty("java.io.tmpdir") + java.io.File.separator + "jdbc_mcp_proxy.jsonl";
        return strCfg("jdbc.mcp.mitmLogPath", "JDBC_MCP_MITM_LOG_PATH", def);
    }

    /**
     * Drivers that are NOT HTTP-interceptable — genuine binary wire-protocol databases and
     * local/embedded engines. These skip the proxy. NOT an exhaustive driver list: anything
     * not listed here (and not a file driver) is assumed HTTP/cloud and proxyable — which
     * correctly covers HTTPS-based engines like Snowflake and BigQuery. Override via env if a
     * driver is misclassified. Comma-separated, lower-case short names.
     */
    public static java.util.Set<String> noProxyDrivers() {
        return csvSet("jdbc.mcp.noProxyDrivers", "JDBC_MCP_NO_PROXY_DRIVERS",
                "postgresql,mysql,sqlserver,oracle,db2,sybase,informix,teradata,vertica,saphana,"
                        + "redshift,cockroachdb,greenplum,alloydb,sqlite,access,duckdb");
    }

    /**
     * File/document drivers whose proxy need depends on whether the data location is a remote URI.
     * Comma-separated, lower-case short names. Override via env to add formats.
     */
    public static java.util.Set<String> fileDrivers() {
        return csvSet("jdbc.mcp.fileDrivers", "JDBC_MCP_FILE_DRIVERS",
                "excel,csv,json,xml,parquet,avro,orc");
    }

    /** CData Verbosity level used when auto-injecting driver request/response logging (1–5). */
    public static int driverLogVerbosity() { return intCfg("jdbc.mcp.logVerbosity", "JDBC_MCP_LOG_VERBOSITY", 5); }

    // ---------------------------------------------------------------------

    private static int intCfg(String prop, String env, int def) {
        String v = resolve(prop, env);
        if (v == null || v.isBlank()) return def;
        try { return Integer.parseInt(v.trim()); } catch (NumberFormatException e) { return def; }
    }

    private static boolean boolCfg(String prop, String env, boolean def) {
        String v = resolve(prop, env);
        if (v == null || v.isBlank()) return def;
        return Boolean.parseBoolean(v.trim());
    }

    private static String strCfg(String prop, String env, String def) {
        String v = resolve(prop, env);
        return (v != null && !v.isBlank()) ? v.trim() : def;
    }

    private static java.util.Set<String> csvSet(String prop, String env, String def) {
        String v = strCfg(prop, env, def);
        java.util.Set<String> set = new java.util.LinkedHashSet<>();
        for (String s : v.split(",")) {
            String t = s.trim().toLowerCase();
            if (!t.isEmpty()) set.add(t);
        }
        return set;
    }

    private static String resolve(String prop, String env) {
        String v = System.getProperty(prop);
        return (v != null) ? v : System.getenv(env);
    }
}
