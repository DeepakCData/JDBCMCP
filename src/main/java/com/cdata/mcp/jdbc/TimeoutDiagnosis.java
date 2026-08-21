package com.cdata.mcp.jdbc;


/**
 * Explains <em>why</em> a query ran out of its wall-clock budget.
 *
 * <p>The bare message ("raise timeout_seconds, or narrow the query") left the caller guessing
 * between causes that call for opposite responses. Raising the budget is right for a slow backend
 * and wrong for an N+1; narrowing the query is right for row volume and useless when the driver is
 * aggregating locally. All the evidence needed to tell them apart already exists — the call log
 * holds how long {@code execute()} took, the ResultSet proxy counts rows, and the capture log holds
 * one entry per HTTP round trip — so the timeout now reports the shape instead of a generic hint.
 *
 * <p>Deliberately does <b>not</b> retry or raise the budget itself. If the ticket under test is a
 * performance regression, a silent retry at a higher budget turns a real FAIL into a PASS. The
 * decision belongs to the caller, who knows what is being verified.
 */
public final class TimeoutDiagnosis {

    private TimeoutDiagnosis() {}

    /** What the capture log shows for one tool call. */
    private record Traffic(int requests, long totalMs) {}

    /**
     * A human-readable explanation, indented for inclusion in an exception message.
     *
     * @param phase          what was in progress, e.g. "fetching rows"
     * @param timeoutSeconds the budget that was exceeded
     * @param elapsedMs      wall-clock time since the tool call began
     * @param executeMs      time the driver spent inside execute(), or -1 if not known
     * @param rowsFetched    rows pulled through the ResultSet proxy during this call
     * @param captureFrom    capture-log byte offset when the call began, or -1 if not captured
     */
    public static String explain(String phase, int timeoutSeconds, long elapsedMs, long executeMs,
                                 long rowsFetched, long captureFrom) {
        Traffic traffic = scanCapture(captureFrom);
        long fetchMs = (executeMs >= 0) ? Math.max(0, elapsedMs - executeMs) : elapsedMs;

        StringBuilder sb = new StringBuilder();
        sb.append("Query exceeded its ").append(timeoutSeconds).append("s budget while ").append(phase).append(".");
        sb.append("\n  elapsed: ").append(ms(elapsedMs));
        if (executeMs >= 0) {
            sb.append(" (execute() returned in ").append(ms(executeMs))
              .append(", ").append(ms(fetchMs)).append(" spent ").append(phase).append(")");
        }
        sb.append("\n  rows fetched: ").append(rowsFetched);
        if (traffic != null) {
            sb.append("\n  HTTP requests during this call: ").append(traffic.requests());
            if (traffic.requests() > 0) {
                sb.append(" (").append(ms(traffic.totalMs())).append(" total");
                sb.append(", avg ").append(traffic.totalMs() / traffic.requests()).append("ms each)");
            }
        } else {
            sb.append("\n  HTTP requests during this call: not captured (proxy inactive for this session)");
        }
        sb.append("\n  shape: ").append(shape(traffic, elapsedMs, rowsFetched));
        sb.append("\n  ").append(advice(traffic, elapsedMs, rowsFetched));
        return sb.toString();
    }

    /**
     * Classify the cause from the ratio of HTTP time to fetch time and the request count.
     * Kept coarse on purpose: it points at the right next step, it does not pretend to be a profiler.
     */
    private static String shape(Traffic t, long elapsedMs, long rows) {
        if (t == null) return "unknown — no capture available for this session";
        if (t.requests() == 0) {
            return "client-side work — no HTTP traffic at all during this call, so the driver is "
                    + "computing locally (an aggregate or a filter it could not push down)";
        }
        double httpShare = httpShare(t, elapsedMs);
        long avg = t.totalMs() / t.requests();

        if (httpShare < 0.2) {
            return "client-side work — HTTP accounts for only " + pct(httpShare)
                    + " of the time, so the driver is processing after the responses arrive";
        }
        // Request count is what distinguishes paging from a single slow call. Latency is reported
        // either way: a paginated scan over a slow API is both, and pretending otherwise by
        // requiring fast requests here mislabelled it "mixed" with no usable advice.
        if (t.requests() >= 10) {
            return "row volume / pagination — " + t.requests() + " requests averaging " + avg
                    + "ms each (" + pct(httpShare) + " of elapsed)"
                    + (rows > 0 ? ", " + rows + " rows returned so far" : ", no rows returned yet")
                    + "; the driver is walking pages";
        }
        if (t.requests() <= 3) {
            return "backend latency — only " + t.requests() + " request(s), averaging " + avg
                    + "ms each (" + pct(httpShare) + " of elapsed); the API itself is slow";
        }
        return "mixed — " + t.requests() + " requests averaging " + avg + "ms, "
                + pct(httpShare) + " of elapsed";
    }

    /** The action that actually fits the shape. */
    private static String advice(Traffic t, long elapsedMs, long rows) {
        if (t == null || t.requests() == 0) {
            return "next: narrow the query so the driver does less local work (filter, project fewer "
                    + "columns, or aggregate server-side). Raising timeout_seconds only buys time for "
                    + "work that scales with the table.";
        }
        double httpShare = httpShare(t, elapsedMs);
        long avg = t.totalMs() / t.requests();
        if (httpShare < 0.2) {
            return "next: narrow the query. The backend is not the bottleneck.";
        }
        if (t.requests() >= 10) {
            String base = "next: add a WHERE clause or lower max_rows to cut round trips. If the request "
                    + "count scales with the rows returned, that is an N+1 and is itself a finding — "
                    + "record it rather than raising the budget.";
            if (avg >= 1000) {
                base += " Each page also costs " + avg + "ms, so a budget large enough to finish would "
                        + "have to be several times the current one — check whether that is acceptable "
                        + "before raising it.";
            }
            return base;
        }
        if (t.requests() <= 3) {
            return "next: raising timeout_seconds is reasonable here — the work is genuinely waiting on "
                    + "the API. If the ticket is about performance, record the latency as the result "
                    + "instead of retrying.";
        }
        return "next: decide from the trace — raise timeout_seconds only if the time is spent waiting on "
                + "the API, otherwise narrow the query.";
    }

    /** HTTP time as a share of the whole call. Clamped: requests can overlap, so raw ratios exceed 1. */
    private static double httpShare(Traffic t, long elapsedMs) {
        if (elapsedMs <= 0) return 1.0;
        return Math.min(1.0, (double) t.totalMs() / elapsedMs);
    }

    /** Entries written since {@code fromOffset}, and their total round-trip time. */
    private static Traffic scanCapture(long fromOffset) {
        CaptureLog.Scan scan = CaptureLog.scan(fromOffset, -1);
        return (scan == null) ? null : new Traffic(scan.entries(), scan.totalMs());
    }

    private static String ms(long v) {
        return (v >= 1000) ? String.format("%.1fs", v / 1000.0) : v + "ms";
    }

    private static String pct(double share) {
        return Math.round(share * 100) + "%";
    }
}
