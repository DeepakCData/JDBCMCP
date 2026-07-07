package com.cdata.mcp.tools;

import com.cdata.mcp.config.Config;
import com.cdata.mcp.jdbc.ConnectionSession;
import com.cdata.mcp.jdbc.SessionManager;
import com.cdata.mcp.jdbc.proxy.ProxyConnection;
import com.cdata.mcp.mitm.MitmProxyManager;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;

import java.io.File;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.cdata.mcp.tools.JsonUtil.*;

public class ConnectTool {

    // Local mitmproxy for JDBC-MCP testing — auto-started by MitmProxyManager when needed.
    // The port is taken from Config (jdbc.mcp.mitmPort / JDBC_MCP_MITM_PORT, default 8889) so the
    // documented tunable actually controls both the auto-start and the injected ProxyPort.
    private static final String DEFAULT_PROXY_HOST = "localhost";

    /** How a driver reaches its data — drives the proxy decision. */
    private enum DriverCategory { TCP, FILE, HTTP }

    // The TCP/embedded ("no proxy") set and the file-driver set are NOT hardcoded here — they come
    // from Config (env-overridable). Anything not in either set is assumed HTTP/cloud and proxyable,
    // which correctly covers HTTPS engines like Snowflake/BigQuery without enumerating every connector.

    // Connection-string properties that hold the data location for file drivers.
    private static final String[] LOCATION_KEYS = {"URI", "DataSource", "Location", "Path", "File", "Folder"};

    // Proxy-routing properties stripped from a CData-style string when the connection is NOT proxied,
    // so a stale value (e.g. ProxyPort=8888 the server never manages) can't misroute a direct
    // connection. SSLServerCert is deliberately excluded — it controls TLS cert trust, not routing.
    private static final String[] PROXY_PROP_KEYS =
            {"ProxyServer", "ProxyPort", "ProxySSLType", "ProxyUser", "ProxyPassword", "ProxyAuthScheme"};

    // Remote URI schemes that require HTTP proxying for interception.
    private static final Pattern REMOTE_SCHEME = Pattern.compile(
            "(?i)^(https?|ftps?|sftp|s3|gs|azureblob|wasbs?|sharepoint|box|onedrive|dropbox|gdrive|googledrive)://.*");

    public static McpSchema.Tool tool() {
        return McpSchema.Tool.builder()
                .name("connect")
                .description("""
                        Open a JDBC connection and return a session_id for subsequent calls.

                        Proxy is decided automatically per driver (override with use_proxy):
                          • HTTP/cloud drivers (Salesforce, SAP ERP, SharePoint, Google Drive, ServiceNow, and
                            HTTPS engines like Snowflake/BigQuery): mitmproxy is auto-started on localhost:8889 and
                            ProxyServer/ProxyPort/ProxySSLType/SSLServerCert are force-injected (existing values overridden).
                          • Binary-protocol / embedded databases (postgresql, mysql, sqlserver, oracle, db2, sqlite,
                            access, …): connected directly, proxy skipped. This set is config-overridable, not hardcoded.
                          • File drivers (excel, csv, json, xml, …): decided by the data location — a remote URI
                            (https://, s3://, sharepoint://, …) is proxied; a local path is not.
                        The response reports driver_category, proxy_applied, proxy_reason, mitm_status
                        ("started" | "already_running" | "external_proxy" | "skipped:<reason>" | "n/a"),
                        logfile_path, proxy_fallback and proxy_fallback_reason.

                        Whenever the proxy is NOT used (or falls back), the server automatically injects the CData
                        driver's own Logfile=<temp>;Verbosity=5 (for CData-style connections) so requests/responses
                        are still captured to a file — returned as logfile_path. Read that file to analyse the traffic.

                        Automatic proxy fallback: if mitmproxy fails to start (mitm_status starts with "skipped:")
                        OR the proxied connection itself fails, the server automatically strips the proxy properties,
                        injects driver-native logging instead, and retries the connection directly.
                        proxy_fallback=true and proxy_fallback_reason explain why the fallback was triggered.

                        Options:
                          • use_proxy = "auto" (default) | "always" | "never". "always" forces proxy injection +
                            mitm auto-start for any non-TCP driver; "never" skips it (driver logging then kicks in).
                          • verbose_log tri-state: unset = auto (log only when not proxying); true = always log
                            (driver-layer capture even alongside the proxy); false = never log.
                          • proxy_host / proxy_port route through an external corporate proxy instead of the local
                            mitmproxy (auto-start is skipped for non-localhost hosts).
                          • read_only = true rejects writes for this session (recommended against shared/prod data).
                          • trust_all_certs = true sets SSLServerCert=* even when not proxying (CData drivers).
                          • set_jvm_proxy = true also sets JVM-global http(s).proxyHost properties.
                          • strip_proxy_props = true (default) removes stale Proxy* routing props
                            (ProxyServer/ProxyPort/ProxySSLType/ProxyUser/ProxyPassword/ProxyAuthScheme)
                            from the connection string whenever the connection is NOT proxied — so a
                            leftover value the server doesn't manage (e.g. ProxyPort=8888) can't misroute
                            a direct connection (MySQL, Postgres, …). Removed keys are returned as
                            stripped_proxy_props. SSLServerCert is left untouched. Set false to keep them.""")
                .inputSchema(schema(
                        Map.of(
                                "connection_string", strProp("JDBC connection URL, e.g. jdbc:saperp:Server=host;User=..."),
                                "driver_name",       strProp("(Optional) Short CData driver name (e.g. 'saperp', 'googledrive'). Improves driver-category detection."),
                                "use_proxy",         strProp("(Optional) 'auto' (default), 'always', or 'never'. Controls mitmproxy auto-start + proxy injection."),
                                "verbose_log",       boolProp("(Optional) Tri-state. Unset=auto (driver logging on only when not proxying); true=always log; false=never. Logs HTTP requests/responses to logfile_path via CData Logfile/Verbosity."),
                                "proxy_host",        strProp("(Optional) External proxy host. Defaults to localhost (auto-started mitmproxy)."),
                                "proxy_port",        strProp("(Optional) External proxy port. Defaults to 8889."),
                                "read_only",         boolProp("(Optional) If true, block all writes for this session. Default from server config."),
                                "trust_all_certs",   boolProp("(Optional) If true, set SSLServerCert=* even when not proxying. Default false."),
                                "set_jvm_proxy",     boolProp("(Optional) If true, also set JVM-global http(s).proxyHost. Default false."),
                                "strip_proxy_props", boolProp("(Optional) If true (default), remove stale Proxy* routing props from the connection string when NOT proxying. Reported as stripped_proxy_props.")
                        ),
                        List.of("connection_string")
                ))
                .build();
    }

    public static McpSchema.CallToolResult handle(McpSyncServerExchange exchange, McpSchema.CallToolRequest request) {
        Map<String, Object> args = request.arguments();
        String url        = (String) args.get("connection_string");
        String driverName = (String) args.get("driver_name");
        String proxyHost  = (String) args.get("proxy_host");
        String proxyPort  = (String) args.get("proxy_port");
        boolean readOnly      = asBool(args.get("read_only"), Config.readOnlyDefault());
        boolean trustAllCerts = asBool(args.get("trust_all_certs"), false);
        boolean setJvmProxy   = asBool(args.get("set_jvm_proxy"), false);
        boolean stripProxyProps = asBool(args.get("strip_proxy_props"), true);

        Object upRaw = args.get("use_proxy");
        String useProxy = (upRaw instanceof String s && !s.isBlank()) ? s.toLowerCase().trim() : "auto";

        // verbose_log is tri-state: unset = auto (log iff not proxying), true = always, false = never.
        Object vlRaw = args.get("verbose_log");
        Boolean verboseLogOpt = (vlRaw == null) ? null : asBool(vlRaw, false);

        if (url == null || url.isBlank()) return error("connection_string is required");

        if (SessionManager.size() >= Config.maxSessions()) {
            return error("Maximum open sessions reached (" + Config.maxSessions()
                    + "). Disconnect an existing session or raise jdbc.mcp.maxSessions.");
        }

        // Classify the driver and decide whether to proxy this specific connection.
        DriverCategory category = classify(url, driverName);
        boolean cdataStyle = isCDataStyle(url);
        StringBuilder reason = new StringBuilder();
        boolean shouldProxy = decideProxy(category, useProxy, url, reason);
        // Safety net: never inject CData proxy props into a native JDBC URL, even if a driver was
        // misclassified as HTTP — it would corrupt the connection string. Connect it directly instead.
        if (shouldProxy && !cdataStyle) {
            shouldProxy = false;
            reason.append(";downgraded:native_url");
        }

        String sessionId = UUID.randomUUID().toString();
        String effectiveUrl = url;
        boolean proxyApplied = false;
        String mitmStatus = "n/a";
        boolean proxyFallback = false;
        String proxyFallbackReason = "";

        // Caller-supplied proxy_host/proxy_port hoisted so they're accessible in the fallback path.
        String pHost = (proxyHost != null && !proxyHost.isBlank()) ? proxyHost : DEFAULT_PROXY_HOST;
        String pPort = (proxyPort != null && !proxyPort.isBlank()) ? proxyPort : String.valueOf(Config.mitmProxyPort());

        if (shouldProxy) {
            // Auto-start mitmproxy only when routing to localhost — external corporate proxies are untouched.
            if (DEFAULT_PROXY_HOST.equals(pHost) || "127.0.0.1".equals(pHost)) {
                try {
                    mitmStatus = MitmProxyManager.ensureRunning(Integer.parseInt(pPort));
                } catch (NumberFormatException e) {
                    mitmStatus = "skipped:invalid_port=" + pPort;
                }
            } else {
                mitmStatus = "external_proxy";
            }

            if (mitmStatus.startsWith("skipped:")) {
                // mitmproxy failed to start — fall back to driver-native logging immediately rather than
                // injecting proxy props that would send the driver to a port nobody is listening on.
                proxyFallback = true;
                proxyFallbackReason = "mitm_not_started:" + mitmStatus;
                // proxyApplied stays false; strip/log logic below will inject driver logs instead.
            } else {
                effectiveUrl = injectCDataProxy(effectiveUrl, pHost, pPort);
                proxyApplied = true;
                if (setJvmProxy) applyJvmProxy(pHost, pPort);
            }
        }

        // When NOT proxying (direct, or fallback from failed mitm-start), apply trustAllCerts if requested.
        if (!proxyApplied && trustAllCerts && cdataStyle) {
            effectiveUrl = overrideOrAppend(effectiveUrl, "SSLServerCert", "*");
        }

        // When NOT proxying, drop any stale proxy-routing props the caller left in the string.
        // The server only manages/overrides these on the proxied path; on a direct connection a
        // leftover ProxyPort=8888 would misroute the driver (connection refused), so strip them.
        List<String> strippedProxyProps = new ArrayList<>();
        if (!proxyApplied && cdataStyle && stripProxyProps) {
            effectiveUrl = stripProxyRoutingProps(effectiveUrl, strippedProxyProps);
        }

        // Driver-native request/response capture via the CData Logfile/Verbosity properties.
        // Default (verbose_log unset): auto — enable whenever we are NOT proxying, so there is always
        // a capture channel even if the driver was misclassified or the proxy was unavailable.
        // Only injected for CData-style strings — native JDBC URLs would reject these properties.
        boolean doLog = (verboseLogOpt == null) ? !proxyApplied : verboseLogOpt;
        String logfilePath = "";
        if (doLog && cdataStyle) {
            logfilePath = System.getProperty("java.io.tmpdir") + File.separator + "jdbc_mcp_" + sessionId + ".log";
            effectiveUrl = injectDriverLog(effectiveUrl, logfilePath);
        }

        Connection real = null;
        try {
            real = DriverManager.getConnection(effectiveUrl);
        } catch (Exception firstError) {
            if (proxyApplied) {
                // Trigger 2: proxied connection failed — could be mitmproxy refusing the binary protocol,
                // an SSL mismatch, or the proxy being unavailable. Strip proxy props and retry with
                // driver-native logging.
                proxyFallback = true;
                proxyFallbackReason = "proxy_error:" + redact(firstError.getMessage());
                proxyApplied = false;
                strippedProxyProps = new ArrayList<>();
                logfilePath = System.getProperty("java.io.tmpdir") + File.separator + "jdbc_mcp_" + sessionId + ".log";
                String fallbackUrl = buildDirectUrl(url, cdataStyle, stripProxyProps, trustAllCerts, strippedProxyProps, logfilePath);
                try {
                    real = DriverManager.getConnection(fallbackUrl);
                } catch (Exception secondError) {
                    return error("Connection failed (proxy then direct): " + redact(secondError.getMessage()));
                }
            } else {
                return error("Connection failed: " + redact(firstError.getMessage()));
            }
        }

        // Trigger 3: proxy connected but isn't actually intercepting traffic. Run a quick probe and
        // check whether the mitmproxy log grew. If nothing was captured, the driver is bypassing the
        // proxy (wrong ProxySSLType, driver-internal HTTP client ignoring system proxy, etc.) — fall
        // back to driver-native logging which definitely captures the driver's own HTTP requests.
        if (proxyApplied) {
            File logFile = new File(Config.mitmLogPath());
            long sizeBefore = logFile.exists() ? logFile.length() : -1L;
            try (Statement probe = real.createStatement()) {
                probe.setQueryTimeout(5);
                try (ResultSet prs = probe.executeQuery("SELECT 1")) {
                    while (prs.next()) {} // drain
                }
            } catch (Exception ignored) {}
            long sizeAfter = logFile.exists() ? logFile.length() : -1L;

            if (sizeAfter <= sizeBefore) {
                // Proxy running but nothing was intercepted for this connection.
                proxyFallback = true;
                proxyFallbackReason = "proxy_no_capture";
                proxyApplied = false;
                try { real.close(); } catch (Exception ignored) {}
                strippedProxyProps = new ArrayList<>();
                logfilePath = System.getProperty("java.io.tmpdir") + File.separator + "jdbc_mcp_" + sessionId + ".log";
                String fallbackUrl = buildDirectUrl(url, cdataStyle, stripProxyProps, trustAllCerts, strippedProxyProps, logfilePath);
                try {
                    real = DriverManager.getConnection(fallbackUrl);
                } catch (Exception reconnectError) {
                    return error("Connection failed (proxy no-capture fallback): " + redact(reconnectError.getMessage()));
                }
            }
        }

        // Best-effort JDBC read-only hint; tool-layer guards are the real enforcement.
        if (readOnly) {
            try { real.setReadOnly(true); } catch (Exception ignored) {}
        }

        ConnectionSession session = new ConnectionSession(sessionId);
        session.setReadOnly(readOnly);
        Connection proxy = ProxyConnection.wrap(real, session);
        session.setProxyConnection(proxy);
        SessionManager.put(sessionId, session);

        try {
            DatabaseMetaData meta = real.getMetaData();
            return ok(Map.ofEntries(
                    Map.entry("session_id",           sessionId),
                    Map.entry("driver_version",       String.valueOf(meta.getDriverVersion())),
                    Map.entry("driver_category",      category.name().toLowerCase()),
                    Map.entry("proxy_applied",        proxyApplied),
                    Map.entry("proxy_reason",         reason.toString()),
                    Map.entry("mitm_status",          mitmStatus),
                    Map.entry("logfile_path",         logfilePath),
                    Map.entry("read_only",            readOnly),
                    Map.entry("trust_all_certs",      trustAllCerts || proxyApplied),
                    Map.entry("stripped_proxy_props", strippedProxyProps),
                    Map.entry("proxy_fallback",       proxyFallback),
                    Map.entry("proxy_fallback_reason", proxyFallbackReason)
            ));
        } catch (Exception e) {
            // Redact secrets from the connection string before surfacing the error.
            return error("Connection failed (metadata): " + redact(e.getMessage()));
        }
    }

    /** Classify a driver into TCP (direct), FILE (location-dependent), or HTTP (proxyable). */
    private static DriverCategory classify(String url, String driverName) {
        String key   = (driverName == null) ? "" : driverName.toLowerCase().replaceAll("[^a-z0-9]", "");
        String lower = (url == null) ? "" : url.toLowerCase();

        if (matchesDriver(key, lower, Config.noProxyDrivers())) return DriverCategory.TCP;
        if (matchesDriver(key, lower, Config.fileDrivers()))    return DriverCategory.FILE;
        // Everything else (incl. HTTPS engines like Snowflake/BigQuery and all SaaS connectors).
        return DriverCategory.HTTP;
    }

    /** True if the short driver name, or the JDBC URL sub-protocol, is in the given set. */
    private static boolean matchesDriver(String key, String lowerUrl, Set<String> set) {
        if (!key.isEmpty() && set.contains(key)) return true;
        for (String d : set) {
            if (lowerUrl.startsWith("jdbc:" + d + ":") || lowerUrl.startsWith("jdbc:" + d + ";")) return true;
        }
        return false;
    }

    /**
     * True if the connection string is CData-style (semicolon-delimited properties) rather than a
     * native JDBC URL (jdbc:subprotocol://host...). CData-only properties — ProxyServer, SSLServerCert,
     * Logfile, Verbosity — must only be injected into CData-style strings; a native driver would reject them.
     */
    private static boolean isCDataStyle(String url) {
        String props = stripJdbcPrefix(url).trim();
        // CData strings are semicolon-delimited Key=Value pairs with no "//host" authority.
        // Native URLs are jdbc:sub://host... (has //) or jdbc:sub:value (no '=', e.g. jdbc:sqlite:C:/db).
        return !props.startsWith("//") && props.contains("=");
    }

    /**
     * Decides whether to proxy this connection and records a human-readable reason.
     * use_proxy="always"/"never" force the outcome; "auto" derives it from the driver category
     * (and, for file drivers, whether the data location is a remote URI).
     */
    private static boolean decideProxy(DriverCategory category, String useProxy, String url, StringBuilder reasonOut) {
        switch (useProxy) {
            case "always":
                if (category == DriverCategory.TCP) { reasonOut.append("skipped:tcp_cannot_proxy"); return false; }
                reasonOut.append("applied:forced"); return true;
            case "never":
                reasonOut.append("skipped:forced"); return false;
            default: // auto
                switch (category) {
                    case TCP:
                        reasonOut.append("skipped:tcp_driver"); return false;
                    case HTTP:
                        reasonOut.append("applied:http_driver"); return true;
                    case FILE:
                        String loc = extractDataLocation(url);
                        if (loc == null)        { reasonOut.append("skipped:file_location_unknown"); return false; }
                        if (looksRemote(loc))   { reasonOut.append("applied:file_remote_uri");       return true; }
                        reasonOut.append("skipped:file_local_path"); return false;
                    default:
                        reasonOut.append("skipped:unknown"); return false;
                }
        }
    }

    /** Extracts the first data-location property (URI/DataSource/Location/Path/File/Folder) from the URL. */
    private static String extractDataLocation(String url) {
        if (url == null) return null;
        String props = stripJdbcPrefix(url);
        for (String k : LOCATION_KEYS) {
            Matcher m = Pattern.compile("(?i)(?:^|;)\\s*" + k + "\\s*=\\s*([^;]*)").matcher(props);
            if (m.find()) {
                String v = m.group(1).trim();
                if (!v.isEmpty()) return v;
            }
        }
        return null;
    }

    /** True if a data location is a remote URI scheme that needs HTTP proxying. */
    private static boolean looksRemote(String location) {
        return location != null && REMOTE_SCHEME.matcher(location.trim()).matches();
    }

    /** Strips the "jdbc:<driver>:" prefix so property parsing starts at the first property. */
    private static String stripJdbcPrefix(String url) {
        int first = url.indexOf(':');
        if (first < 0) return url;
        int second = url.indexOf(':', first + 1);
        if (second < 0) return url;
        return url.substring(second + 1);
    }

    /**
     * Injects (overriding any existing values) the CData HTTP proxy properties so traffic flows
     * through the mitmproxy regardless of what the caller supplied — including overriding a pinned
     * SSLServerCert, which would otherwise reject mitmproxy's interception certificate.
     */
    private static String injectCDataProxy(String url, String proxyHost, String proxyPort) {
        url = overrideOrAppend(url, "ProxyServer",   proxyHost);
        url = overrideOrAppend(url, "ProxyPort",     proxyPort);
        url = overrideOrAppend(url, "ProxySSLType",  "TUNNEL");
        url = overrideOrAppend(url, "SSLServerCert", "*");
        return url;
    }

    /**
     * Removes proxy-routing properties (PROXY_PROP_KEYS) from a CData-style connection string,
     * recording the keys that were present in {@code removedOut}. Called only when the connection is
     * NOT proxied (TCP driver, local file, or use_proxy="never"), so a stale value such as
     * ProxyPort=8888 — which the server does not manage for a direct connection — cannot misroute the
     * driver into a connection refusal. SSLServerCert is intentionally left in place: it controls TLS
     * server-cert trust, not proxy routing, and removing it could break a legitimate TLS connection.
     */
    private static String stripProxyRoutingProps(String url, List<String> removedOut) {
        for (String key : PROXY_PROP_KEYS) {
            // Common case: ";Key=value" anywhere in the property list (one or more occurrences).
            Matcher m = Pattern.compile("(?i);\\s*" + Pattern.quote(key) + "\\s*=[^;]*").matcher(url);
            if (m.find()) {
                removedOut.add(key);
                url = m.replaceAll("");
                continue;
            }
            // Edge case: the key is the very first property right after "jdbc:<driver>:".
            int prefixEnd = url.indexOf(':', url.indexOf(':') + 1);
            if (prefixEnd >= 0 && prefixEnd + 1 < url.length()) {
                String afterPrefix = url.substring(prefixEnd + 1);
                if (afterPrefix.regionMatches(true, 0, key + "=", 0, key.length() + 1)) {
                    int valEnd = afterPrefix.indexOf(';');
                    String rest = (valEnd >= 0) ? afterPrefix.substring(valEnd + 1) : "";
                    url = url.substring(0, prefixEnd + 1) + rest;
                    removedOut.add(key);
                }
            }
        }
        return url;
    }

    /** Injects the CData driver's own request/response logging (overriding any existing values). */
    private static String injectDriverLog(String url, String logfilePath) {
        url = overrideOrAppend(url, "Logfile",   logfilePath);
        url = overrideOrAppend(url, "Verbosity", String.valueOf(Config.driverLogVerbosity()));
        return url;
    }

    /**
     * Replaces an existing key=value pair (case-insensitive key match) in a CData
     * semicolon-delimited connection string, or appends it if the key is absent.
     */
    private static String overrideOrAppend(String url, String key, String value) {
        // Match ";key=<anything up to next ;>" — covers mid-string occurrences
        Pattern p = Pattern.compile("(?i)(;)" + Pattern.quote(key) + "=[^;]*");
        Matcher m = p.matcher(url);
        if (m.find()) {
            return m.replaceFirst(Matcher.quoteReplacement(";" + key + "=" + value));
        }
        // Also handle the first property after the JDBC prefix "jdbc:driver:KEY=val"
        int prefixEnd = url.indexOf(':', url.indexOf(':') + 1);
        if (prefixEnd >= 0 && prefixEnd + 1 < url.length()) {
            String afterPrefix = url.substring(prefixEnd + 1);
            if (afterPrefix.toLowerCase().startsWith(key.toLowerCase() + "=")) {
                int valEnd = afterPrefix.indexOf(';');
                String rest = (valEnd >= 0) ? afterPrefix.substring(valEnd) : "";
                return url.substring(0, prefixEnd + 1) + key + "=" + value + rest;
            }
        }
        return url + ";" + key + "=" + value;
    }

    /** Sets JVM-wide HTTP/HTTPS proxy system properties for the driver's underlying HTTP client. */
    private static void applyJvmProxy(String proxyHost, String proxyPort) {
        System.setProperty("http.proxyHost",  proxyHost);
        System.setProperty("http.proxyPort",  proxyPort);
        System.setProperty("https.proxyHost", proxyHost);
        System.setProperty("https.proxyPort", proxyPort);
    }

    /**
     * Builds a direct (non-proxy) CData connection URL from the original URL:
     * strips stale proxy-routing props, optionally injects SSLServerCert=* and driver logging.
     * Used by all three proxy fallback paths so the logic stays in one place.
     */
    private static String buildDirectUrl(String url, boolean cdataStyle, boolean stripProxyProps,
                                         boolean trustAllCerts, List<String> strippedOut, String logfilePath) {
        String result = url;
        if (cdataStyle && stripProxyProps) result = stripProxyRoutingProps(result, strippedOut);
        if (cdataStyle && trustAllCerts)   result = overrideOrAppend(result, "SSLServerCert", "*");
        if (cdataStyle && !logfilePath.isEmpty()) result = injectDriverLog(result, logfilePath);
        return result;
    }
}
