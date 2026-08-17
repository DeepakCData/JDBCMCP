package com.cdata.mcp.jdbc.proxy;

import com.cdata.mcp.jdbc.ConnectionSession;
import com.cdata.mcp.jdbc.QueryBudget;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.ResultSet;

/**
 * Wraps a ResultSet so the session's wall-clock budget is enforced across row fetching —
 * the phase {@code setQueryTimeout} does not reach (see {@link com.cdata.mcp.jdbc.QueryBudget}).
 *
 * <p>The check runs <em>before</em> each fetch, so an expired budget costs at most the page
 * already in flight rather than the rest of the table.
 */
public class ProxyResultSet implements InvocationHandler {

    private final ResultSet real;
    private final ConnectionSession session;

    public ProxyResultSet(ResultSet real, ConnectionSession session) {
        this.real = real;
        this.session = session;
    }

    /** Returns null for a null result set, so callers can wrap unconditionally. */
    public static ResultSet wrap(ResultSet real, ConnectionSession session) {
        if (real == null) return null;
        return (ResultSet) Proxy.newProxyInstance(
                real.getClass().getClassLoader(),
                new Class[]{ResultSet.class},
                new ProxyResultSet(real, session));
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        // next() is the only paging call: SQLTimeoutException is a SQLException, so it is
        // declared on next() and propagates without being wrapped as undeclared.
        if (!method.getName().equals("next")) {
            return ProxyInvoke.call(method, real, args);
        }

        QueryBudget budget = session.getBudget();
        budget.check("fetching rows");
        Object hasMore = ProxyInvoke.call(method, real, args);

        // A statement cancelled by the watchdog ends iteration by returning false rather
        // than throwing. Without this second check the caller cannot tell a timed-out scan
        // from a complete one, and a truncated result gets reported as the whole answer.
        if (Boolean.FALSE.equals(hasMore)) budget.check("fetching rows");

        return hasMore;
    }
}
