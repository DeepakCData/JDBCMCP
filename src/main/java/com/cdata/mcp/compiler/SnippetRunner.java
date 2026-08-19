package com.cdata.mcp.compiler;

import com.cdata.mcp.config.Config;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class SnippetRunner {

    /** Cap on captured snippet output, so a runaway print loop cannot exhaust the heap. */
    private static final int OUTPUT_LIMIT_BYTES = 1_048_576;

    public record RunResult(String output, String stdout, String stderr, String error) {}

    public static RunResult run(Path classDir, String className, Connection connection) {
        String fqn = "com.cdata.mcp.snippets." + className;
        int timeout = Config.javaTimeoutSeconds();

        // Route System.out/err per thread rather than swapping them globally for the call's
        // duration. A snippet that survives its timeout then keeps writing to its own buffer
        // instead of to the real stdout, which carries the MCP JSON-RPC frames.
        ThreadScopedStream.install();

        // Holder so the outer thread can read whatever the snippet produced before timing out.
        final ThreadScopedStream.Captures[] caps = new ThreadScopedStream.Captures[1];

        ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "jdbc-mcp-snippet");
            t.setDaemon(true);
            return t;
        });

        try (URLClassLoader loader = new URLClassLoader(
                new URL[]{classDir.toUri().toURL()},
                Thread.currentThread().getContextClassLoader())) {

            Class<?> clazz = loader.loadClass(fqn);
            Method runMethod = clazz.getMethod("run", Connection.class);

            Future<Object> future = exec.submit(() -> {
                // Capture is established on the snippet thread, so it stays bound to that thread
                // for as long as the thread lives — including past a timeout.
                caps[0] = ThreadScopedStream.begin(OUTPUT_LIMIT_BYTES);
                try {
                    return runMethod.invoke(null, connection);
                } finally {
                    ThreadScopedStream.end();
                }
            });

            try {
                Object result = timeout > 0 ? future.get(timeout, TimeUnit.SECONDS) : future.get();
                return new RunResult(
                        result != null ? result.toString() : "",
                        out(caps[0]), err(caps[0]), null);
            } catch (TimeoutException te) {
                future.cancel(true); // interrupt; a blocked snippet may ignore it and keep running
                return new RunResult("", out(caps[0]), err(caps[0]),
                        "Execution timed out after " + timeout + "s and was cancelled. If the snippet was "
                                + "blocked in a JDBC call it may still be running in the background; its "
                                + "output is discarded.");
            } catch (ExecutionException ee) {
                Throwable cause = ee.getCause();
                if (cause instanceof InvocationTargetException ite && ite.getCause() != null) {
                    cause = ite.getCause();
                }
                return new RunResult("", out(caps[0]), err(caps[0]),
                        cause != null ? cause.toString() : "execution failed");
            }
        } catch (Exception ex) {
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            return new RunResult("", out(caps[0]), err(caps[0]),
                    // toString(), not getMessage() — wrapper exceptions often have no
                    // message and would report a bare "null" as the runtime error.
                    cause.toString());
        } finally {
            exec.shutdownNow();
            deleteDir(classDir.toFile());
        }
    }

    private static String out(ThreadScopedStream.Captures c) {
        return c == null ? "" : c.out().text();
    }

    private static String err(ThreadScopedStream.Captures c) {
        return c == null ? "" : c.err().text();
    }

    private static void deleteDir(File dir) {
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) {
            if (f.isDirectory()) deleteDir(f);
            else f.delete();
        }
        dir.delete();
    }
}
