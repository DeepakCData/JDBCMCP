package com.cdata.mcp.mitm;

import com.cdata.mcp.config.Config;

import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Manages a local mitmproxy (mitmdump) process for intercepting HTTP traffic
 * from CData HTTP-based JDBC drivers. Started lazily on the first HTTP-driver
 * connect() call; killed automatically when the JVM exits.
 *
 * Only auto-starts when the target proxy host is localhost / 127.0.0.1.
 * External corporate proxies are left untouched.
 */
public final class MitmProxyManager {

    private static final Object LOCK = new Object();
    private static volatile Process process = null;
    private static volatile int managedPort = -1;
    private static volatile boolean shutdownHookRegistered = false;

    private MitmProxyManager() {}

    /**
     * Ensures mitmproxy is listening on {@code port}.
     *
     * @return one of:
     *   "already_running"  — port was already in use before this call
     *   "started"          — mitmdump was spawned and the port is now ready
     *   "skipped:<reason>" — mitmdump could not be started (not on PATH, etc.)
     */
    public static String ensureRunning(int port) {
        // Fast path — if the port is already bound, nothing to do.
        if (isPortInUse(port)) {
            return "already_running";
        }

        synchronized (LOCK) {
            // Double-checked inside the lock.
            if (isPortInUse(port)) return "already_running";
            if (process != null && process.isAlive() && managedPort == port) return "already_running";

            try {
                Path script = extractAddonScript();

                ProcessBuilder pb = new ProcessBuilder(
                        "mitmdump",
                        "--listen-port", String.valueOf(port),
                        "-s", script.toString(),
                        "--ssl-insecure",
                        "--quiet"
                );
                pb.redirectErrorStream(true);
                pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
                // Pass the resolved log path to the addon so the -Djdbc.mcp.mitmLogPath system-property
                // form is honored too (the Python addon only reads the JDBC_MCP_MITM_LOG_PATH env var).
                pb.environment().put("JDBC_MCP_MITM_LOG_PATH", Config.mitmLogPath());

                process = pb.start();
                managedPort = port;

                registerShutdownHook();

                if (!waitForPort(port, 5_000)) {
                    return "skipped:mitmdump_did_not_bind_within_5s";
                }
                return "started";

            } catch (IOException e) {
                // mitmdump not on PATH or failed — non-fatal; proxy params are still injected
                // so the user gets a clear connection-refused error rather than a silent miss.
                return "skipped:" + e.getMessage().replace('\n', ' ');
            }
        }
    }

    /** Forcibly stops the mitmproxy process if this manager started it. */
    public static void stop() {
        synchronized (LOCK) {
            if (process != null) {
                process.destroyForcibly();
                process = null;
                managedPort = -1;
            }
        }
    }

    // -------------------------------------------------------------------------

    private static boolean isPortInUse(int port) {
        try (ServerSocket ss = new ServerSocket(port)) {
            return false;
        } catch (IOException e) {
            return true;
        }
    }

    private static boolean waitForPort(int port, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (isPortInUse(port)) return true;
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /**
     * Extracts the bundled proxy_addon.py from the JAR into the system temp
     * directory so mitmdump can load it. Overwrites on every start so updates
     * in the JAR are always picked up.
     */
    private static Path extractAddonScript() throws IOException {
        Path dest = Path.of(System.getProperty("java.io.tmpdir"), "jdbc_mcp_proxy_addon.py");
        try (InputStream is = MitmProxyManager.class.getResourceAsStream("/proxy_addon.py")) {
            if (is == null) {
                throw new IOException("proxy_addon.py not found in JAR resources");
            }
            Files.copy(is, dest, StandardCopyOption.REPLACE_EXISTING);
        }
        return dest;
    }

    private static void registerShutdownHook() {
        if (shutdownHookRegistered) return;
        shutdownHookRegistered = true;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            Process p = process;
            if (p != null && p.isAlive()) {
                p.destroyForcibly();
            }
        }, "jdbc-mcp-mitm-shutdown"));
    }
}
