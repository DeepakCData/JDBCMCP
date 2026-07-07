package com.cdata.mcp.compiler;

import com.cdata.mcp.config.Config;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class SnippetRunner {

    public record RunResult(String output, String stdout, String stderr, String error) {}

    public static RunResult run(Path classDir, String className, Connection connection) {
        String fqn = "com.cdata.mcp.snippets." + className;
        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        PrintStream oldOut = System.out;
        PrintStream oldErr = System.err;
        int timeout = Config.javaTimeoutSeconds();

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

            // Redirect process streams so the snippet's stdout/stderr is captured
            // rather than corrupting the MCP JSON-RPC channel on real stdout.
            System.setOut(new PrintStream(outBuf, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(errBuf, true, StandardCharsets.UTF_8));

            Future<Object> future = exec.submit(() -> runMethod.invoke(null, connection));
            try {
                Object result = timeout > 0 ? future.get(timeout, TimeUnit.SECONDS) : future.get();
                return new RunResult(
                        result != null ? result.toString() : "",
                        outBuf.toString(StandardCharsets.UTF_8),
                        errBuf.toString(StandardCharsets.UTF_8),
                        null);
            } catch (TimeoutException te) {
                future.cancel(true); // interrupt the runaway snippet
                return new RunResult("",
                        outBuf.toString(StandardCharsets.UTF_8),
                        errBuf.toString(StandardCharsets.UTF_8),
                        "Execution timed out after " + timeout + "s and was cancelled.");
            } catch (ExecutionException ee) {
                Throwable cause = ee.getCause();
                if (cause instanceof InvocationTargetException ite && ite.getCause() != null) {
                    cause = ite.getCause();
                }
                return new RunResult("",
                        outBuf.toString(StandardCharsets.UTF_8),
                        errBuf.toString(StandardCharsets.UTF_8),
                        cause != null ? cause.toString() : "execution failed");
            }
        } catch (Exception ex) {
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            return new RunResult("",
                    outBuf.toString(StandardCharsets.UTF_8),
                    errBuf.toString(StandardCharsets.UTF_8),
                    cause.getMessage());
        } finally {
            System.setOut(oldOut);
            System.setErr(oldErr);
            exec.shutdownNow();
            deleteDir(classDir.toFile());
        }
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
