package com.cdata.mcp.jdbc;

import java.sql.SQLTimeoutException;
import java.sql.Statement;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Wall-clock budget for a single tool call.
 *
 * <p>{@link Statement#setQueryTimeout} only bounds statement <em>execution</em> — the JDBC
 * spec says nothing about ResultSet iteration, and lazily-paging drivers (every CData HTTP
 * connector) do the bulk of their work in {@code next()}. A client-side aggregate over a
 * large table therefore returns from {@code executeQuery()} in seconds and then pages for
 * minutes, entirely outside the driver's timeout. {@code setQueryTimeout} is still applied,
 * but it cannot be the only guard.
 *
 * <p>Two mechanisms, layered:
 * <ul>
 *   <li>The deadline, checked by {@code ProxyResultSet} before each fetch. Deterministic and
 *       driver-independent: it needs no cooperation from the driver, only that {@code next()}
 *       eventually returns.</li>
 *   <li>{@link #arm(Statement)}, a watchdog that cancels the statement when a single call
 *       blocks past the deadline and never returns to a check point. Best-effort —
 *       {@code cancel()} support genuinely varies by driver.</li>
 * </ul>
 */
public final class QueryBudget {

    /** Shared budget for calls that are not time-bounded (timeout of 0 or none). */
    public static final QueryBudget UNBOUNDED = new QueryBudget(0L, 0);

    private static final ScheduledExecutorService WATCHDOG =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "jdbc-mcp-query-watchdog");
                t.setDaemon(true);
                return t;
            });

    /** Handle returned by {@link #arm}; closing it cancels the pending watchdog. */
    public interface Disarm extends AutoCloseable {
        @Override void close();   // narrowed: never throws, so try-with-resources stays clean
    }

    /** Absolute {@link System#nanoTime()} deadline; 0 means unbounded. */
    private final long deadlineNanos;
    private final int timeoutSeconds;

    private QueryBudget(long deadlineNanos, int timeoutSeconds) {
        this.deadlineNanos = deadlineNanos;
        this.timeoutSeconds = timeoutSeconds;
    }

    /** A budget running from now. A timeout of {@code <= 0} yields {@link #UNBOUNDED}. */
    public static QueryBudget ofSeconds(int timeoutSeconds) {
        if (timeoutSeconds <= 0) return UNBOUNDED;
        return new QueryBudget(System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds),
                               timeoutSeconds);
    }

    public boolean isBounded() {
        return deadlineNanos != 0L;
    }

    public boolean expired() {
        // Subtraction rather than '>' so the comparison survives nanoTime wrap-around.
        return isBounded() && System.nanoTime() - deadlineNanos >= 0;
    }

    /**
     * Throw if the budget is spent. {@code phase} names what was in progress, e.g.
     * "fetching rows", and is surfaced to the caller.
     */
    public void check(String phase) throws SQLTimeoutException {
        check(phase, null);
    }

    /**
     * Throw if the budget is spent, including a diagnosis of where the time went.
     *
     * <p>The generic advice ("raise timeout_seconds, or narrow the query") named two responses that
     * suit opposite causes, with nothing to choose between them. {@code diagnosis} supplies the
     * evidence — execute vs fetch time, rows pulled, HTTP round trips — and is only invoked once the
     * budget has actually expired, so it costs nothing on the hot path.
     *
     * @param diagnosis lazily-built explanation, or null for the plain message
     */
    public void check(String phase, java.util.function.Supplier<String> diagnosis) throws SQLTimeoutException {
        if (!expired()) return;
        String detail = null;
        if (diagnosis != null) {
            // A failure to explain the timeout must never replace the timeout itself.
            try { detail = diagnosis.get(); } catch (Throwable ignored) { }
        }
        String message = (detail != null && !detail.isBlank())
                ? detail
                : "Query exceeded its " + timeoutSeconds + "s budget while " + phase
                        + ". The driver returned from execute() and kept paging, which"
                        + " setQueryTimeout does not cover. Raise timeout_seconds, or narrow"
                        + " the query so the driver returns fewer rows.";
        throw new SQLTimeoutException(message, "HYT00");
    }

    /** The configured budget in seconds; 0 when unbounded. */
    public int timeoutSeconds() {
        return timeoutSeconds;
    }

    /**
     * Schedule a best-effort {@link Statement#cancel()} at the deadline, covering calls that
     * block past it and never reach a check point. Always close the handle when the statement
     * is done, so a cancel cannot fire into a later, unrelated call on the same statement.
     */
    public Disarm arm(Statement st) {
        if (!isBounded()) return () -> { };
        long delay = Math.max(0L, deadlineNanos - System.nanoTime());
        ScheduledFuture<?> pending = WATCHDOG.schedule(() -> {
            // A driver that does not support cancel() simply leaves the deadline to
            // ProxyResultSet; nothing here is worth failing the call over.
            try { st.cancel(); } catch (Throwable ignored) { }
        }, delay, TimeUnit.NANOSECONDS);
        return () -> pending.cancel(false);
    }
}
