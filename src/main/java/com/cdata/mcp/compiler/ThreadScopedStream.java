package com.cdata.mcp.compiler;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * A {@link PrintStream} that sends output to a per-thread buffer when one is registered, and to the
 * original stream otherwise.
 *
 * <p>Snippet output used to be captured by swapping the process-global {@code System.out} for the
 * duration of the call and restoring it in a {@code finally}. That leaves a hole: a snippet that
 * outruns its timeout is only <em>interrupted</em>, and an interrupt does not stop a thread blocked
 * in JDBC or spinning in a loop. Once the tool returned and the global stream was restored, the
 * surviving thread's next {@code println} landed on the real stdout — which is the MCP JSON-RPC
 * channel. One runaway snippet with a print statement could emit an unparseable frame and break the
 * session.
 *
 * <p>Binding the buffer to the thread instead of to a time window closes that: a runaway thread
 * keeps writing into its own buffer forever, harmlessly, and concurrent calls cannot capture each
 * other's output. Buffers are capped so an infinite print loop cannot exhaust the heap.
 */
public final class ThreadScopedStream extends PrintStream {

    private static ThreadScopedStream installedOut;
    private static ThreadScopedStream installedErr;

    /** Per-thread capture target for THIS stream; null means "pass through". */
    private final ThreadLocal<Capture> capture = new ThreadLocal<>();
    private final PrintStream passthrough;

    private ThreadScopedStream(PrintStream passthrough) {
        super(OutputStream.nullOutputStream(), true, StandardCharsets.UTF_8);
        this.passthrough = passthrough;
    }

    /** The stdout and stderr buffers bound to one thread. */
    public record Captures(Capture out, Capture err) {}

    /** A bounded buffer: writes past the limit are dropped and counted. */
    public static final class Capture {
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private final int limitBytes;
        private long dropped;

        Capture(int limitBytes) { this.limitBytes = limitBytes; }

        synchronized void write(byte[] b, int off, int len) {
            int room = limitBytes - buffer.size();
            if (room <= 0) { dropped += len; return; }
            int take = Math.min(room, len);
            buffer.write(b, off, take);
            dropped += (len - take);
        }

        public synchronized String text() {
            String s = buffer.toString(StandardCharsets.UTF_8);
            return dropped > 0 ? s + "\n…[" + dropped + " more bytes suppressed]" : s;
        }
    }

    /** Installs the routing streams over System.out/System.err. Idempotent. */
    public static synchronized void install() {
        if (!(System.out instanceof ThreadScopedStream)) {
            installedOut = new ThreadScopedStream(System.out);
            System.setOut(installedOut);
        }
        if (!(System.err instanceof ThreadScopedStream)) {
            installedErr = new ThreadScopedStream(System.err);
            System.setErr(installedErr);
        }
    }

    /** Begin capturing the calling thread's stdout and stderr into separate buffers. */
    public static synchronized Captures begin(int limitBytes) {
        install();
        Capture out = new Capture(limitBytes);
        Capture err = new Capture(limitBytes);
        installedOut.capture.set(out);
        installedErr.capture.set(err);
        return new Captures(out, err);
    }

    /** Stop capturing the calling thread's output. */
    public static synchronized void end() {
        if (installedOut != null) installedOut.capture.remove();
        if (installedErr != null) installedErr.capture.remove();
    }

    @Override
    public void write(int b) {
        write(new byte[]{(byte) b}, 0, 1);
    }

    @Override
    public void write(byte[] b, int off, int len) {
        Capture c = capture.get();
        if (c != null) c.write(b, off, len);
        else passthrough.write(b, off, len);
    }

    @Override
    public void flush() {
        if (capture.get() == null) passthrough.flush();
    }

    @Override
    public void close() {
        // Never close the underlying stream — it is the process's real stdout/stderr.
        flush();
    }
}
